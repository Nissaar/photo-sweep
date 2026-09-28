<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Tests\unit\Service;

use OCA\PhotoSweep\Db\DateSource;
use OCA\PhotoSweep\Db\DecisionMapper;
use OCA\PhotoSweep\Db\Media;
use OCA\PhotoSweep\Db\MediaMapper;
use OCA\PhotoSweep\Db\Scan;
use OCA\PhotoSweep\Db\ScanMapper;
use OCA\PhotoSweep\Service\ConfigService;
use OCA\PhotoSweep\Service\DateResolver;
use OCA\PhotoSweep\Service\IndexService;
use OCA\PhotoSweep\Service\MediaFinder;
use OCA\PhotoSweep\Service\ResolvedDate;
use OCP\Files\IRootFolder;
use PHPUnit\Framework\MockObject\MockObject;
use PHPUnit\Framework\TestCase;
use Psr\Log\LoggerInterface;

class IndexScanTest extends TestCase {
	use FileMocks;

	private MediaMapper&MockObject $mediaMapper;
	private ScanMapper&MockObject $scanMapper;
	private MediaFinder&MockObject $finder;
	private Scan $scan;
	private IndexService $service;

	protected function setUp(): void {
		parent::setUp();
		$rootFolder = $this->createMock(IRootFolder::class);
		$rootFolder->method('getUserFolder')->willReturn($this->userFolder([]));

		$this->mediaMapper = $this->createMock(MediaMapper::class);
		$this->mediaMapper->method('existingRowIds')->willReturn([]);

		$this->scan = new Scan();
		$this->scan->setUserId('alice');
		$this->scan->setCursorMtime(0);
		$this->scan->setCursorFileId(0);
		$this->scan->setComplete(false);
		$this->scan->setRunning(false);
		$this->scan->setFound(0);
		$this->scan->setStartedAt(0);
		$this->scan->setUpdatedAt(0);
		$this->scanMapper = $this->createMock(ScanMapper::class);
		$this->scanMapper->method('findOrCreate')->willReturn($this->scan);

		$decisionMapper = $this->createMock(DecisionMapper::class);
		// Photos collected into /Old before the folder setting was changed.
		$decisionMapper->method('appliedFolders')->willReturn(['/Old']);

		$config = $this->createMock(ConfigService::class);
		$config->method('getSourceFolder')->willReturn('/');
		$config->method('getTargetFolder')->willReturn('/To Be Deleted');
		$config->method('getTimeZone')->willReturn(new \DateTimeZone('UTC'));

		$dates = $this->createMock(DateResolver::class);
		$dates->method('preloadMetadata')->willReturn([]);
		$dates->method('resolve')->willReturn(new ResolvedDate(1_720_000_000, DateSource::MTIME));

		$this->finder = $this->createMock(MediaFinder::class);

		$this->service = new IndexService(
			$rootFolder,
			$this->mediaMapper,
			$this->scanMapper,
			$decisionMapper,
			$this->finder,
			$dates,
			$config,
			$this->l10n(),
			$this->createMock(LoggerInterface::class),
		);
	}

	public function testIndexesOnlyTheUsersOwnLibrary(): void {
		$this->finder->method('findBatch')->willReturnOnConsecutiveCalls([
			$this->file(1, '/Photos/mine.jpg', mtime: 100),
			$this->file(2, '/Shared/bobs.jpg', 'bob', mtime: 110),
			$this->file(3, '/Team/group.jpg', 'alice', false, mtime: 120),
			$this->file(4, '/To Be Deleted/collected.jpg', mtime: 130),
			$this->file(5, '/Old/collected earlier.jpg', mtime: 140),
		], []);

		$inserted = [];
		$this->mediaMapper->method('insert')->willReturnCallback(static function (Media $m) use (&$inserted): Media {
			$inserted[] = $m->getFileId();
			return $m;
		});

		$this->service->scan('alice');

		self::assertSame([1], $inserted);
	}

	public function testResumesFromTheLastFileAndOnlyAnEmptyPageEndsThePass(): void {
		// The first page is short — the finder drops files already passed — and that
		// must not be read as the end, or everything after it would be purged as stale.
		$calls = [];
		$this->finder->method('findBatch')->willReturnCallback(
			function ($scope, int $mtime, int $fileId) use (&$calls): array {
				$calls[] = [$mtime, $fileId];
				return match (count($calls)) {
					1 => [$this->file(1, '/Photos/a.jpg', mtime: 100), $this->file(7, '/Photos/b.jpg', mtime: 150)],
					2 => [$this->file(3, '/Photos/c.jpg', mtime: 200)],
					default => [],
				};
			},
		);
		$this->mediaMapper->method('insert')->willReturnArgument(0);
		$this->mediaMapper->expects(self::once())->method('removeStale');

		$scan = $this->service->scan('alice');

		self::assertSame([[0, 0], [150, 7], [200, 3]], $calls);
		self::assertTrue($scan->getComplete());
		self::assertSame(3, $scan->getFound());
	}

	public function testAnInterruptedRunKeepsItsCursor(): void {
		$this->finder->method('findBatch')->willReturn([$this->file(8, '/Photos/a.jpg', mtime: 400)]);
		$this->mediaMapper->method('insert')->willReturnArgument(0);
		$this->mediaMapper->expects(self::never())->method('removeStale');

		$scan = $this->service->scan('alice', false, null, 1);

		self::assertFalse($scan->getComplete());
		self::assertSame(400, $scan->getCursorMtime());
		self::assertSame(8, $scan->getCursorFileId());
	}

	public function testAFailedScanTellsTheUserWithoutTheRawError(): void {
		$this->finder->method('findBatch')->willThrowException(
			new \RuntimeException('SQLSTATE[42S02]: Base table oc_filecache not found'),
		);

		$scan = $this->service->scan('alice');

		self::assertNotNull($scan->getError());
		self::assertStringNotContainsString('SQLSTATE', (string)$scan->getError());
		self::assertLessThanOrEqual(255, strlen((string)$scan->getError()));
	}
}
