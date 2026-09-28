<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Tests\unit\Service;

use OCA\PhotoSweep\Db\Decision;
use OCA\PhotoSweep\Db\DecisionMapper;
use OCA\PhotoSweep\Db\Media;
use OCA\PhotoSweep\Db\MediaMapper;
use OCA\PhotoSweep\Db\Verdict;
use OCA\PhotoSweep\Service\CleanupMode;
use OCA\PhotoSweep\Service\DecisionService;
use OCA\PhotoSweep\Service\IndexService;
use OCP\AppFramework\Db\DoesNotExistException;
use OCP\DB\Exception as DbException;
use OCP\Files\File;
use OCP\Files\IRootFolder;
use PHPUnit\Framework\MockObject\MockObject;
use PHPUnit\Framework\TestCase;
use Psr\Log\LoggerInterface;

class DecisionServiceTest extends TestCase {
	use FileMocks;

	private DecisionMapper&MockObject $decisionMapper;
	private MediaMapper&MockObject $mediaMapper;
	private IRootFolder&MockObject $rootFolder;
	private IndexService&MockObject $indexService;
	private DecisionService $service;

	protected function setUp(): void {
		parent::setUp();
		$this->decisionMapper = $this->createMock(DecisionMapper::class);
		$this->mediaMapper = $this->createMock(MediaMapper::class);
		$this->rootFolder = $this->createMock(IRootFolder::class);
		$this->indexService = $this->createMock(IndexService::class);
		$this->service = new DecisionService(
			$this->decisionMapper,
			$this->mediaMapper,
			$this->rootFolder,
			$this->indexService,
			$this->createMock(LoggerInterface::class),
		);
	}

	public function testRecordingCopiesWhatTheReviewScreenNeeds(): void {
		$this->mediaMapper->method('findByFileId')->willReturn($this->media(5, '/Photos/a.jpg'));
		$this->decisionMapper->expects(self::once())
			->method('upsert')
			->willReturnCallback(static fn (Decision $d): Decision => $d);

		$decision = $this->service->record('alice', 5, Verdict::DELETE);

		self::assertSame(Verdict::DELETE, $decision->getVerdict());
		self::assertFalse($decision->getApplied());
		self::assertSame('2024-07', $decision->getYearMonth());
		self::assertSame('/Photos/a.jpg', $decision->getOriginPath());
	}

	public function testRefusesAnUnknownVerdict(): void {
		$this->decisionMapper->expects(self::never())->method('upsert');
		$this->expectException(\InvalidArgumentException::class);
		$this->service->record('alice', 5, 'maybe');
	}

	public function testUndoLeavesAnAppliedVerdictAlone(): void {
		$decision = $this->decision(5, CleanupMode::TRASH);
		$this->decisionMapper->method('findByFileId')->willReturn($decision);
		$this->decisionMapper->expects(self::never())->method('delete');

		self::assertFalse($this->service->undo('alice', 5));
	}

	public function testTheBatchSkipsWhatIsExpectedToFail(): void {
		// A photo that left the library while the phone was offline, and an entry
		// the client mangled. Neither is an error.
		$this->mediaMapper->method('findByFileId')->willReturnCallback(function (string $userId, int $fileId): Media {
			if ($fileId === 2) {
				throw new DoesNotExistException('gone');
			}
			return $this->media($fileId, '/Photos/' . $fileId . '.jpg');
		});
		$this->decisionMapper->method('upsert')->willReturnArgument(0);

		$result = $this->service->recordMany('alice', [
			['fileId' => 1, 'verdict' => Verdict::KEEP],
			['fileId' => 2, 'verdict' => Verdict::DELETE],
			['fileId' => 3, 'verdict' => 'nonsense'],
		]);

		self::assertSame(['recorded' => 1, 'skipped' => [2, 3]], $result);
	}

	public function testTheBatchDoesNotHideADatabaseFailure(): void {
		// Reported as skipped, the phone would drop a verdict it ought to retry.
		$this->mediaMapper->method('findByFileId')->willReturn($this->media(1, '/Photos/a.jpg'));
		$this->decisionMapper->method('upsert')->willThrowException(new DbException('connection lost'));

		$this->expectException(DbException::class);
		$this->service->recordMany('alice', [['fileId' => 1, 'verdict' => Verdict::KEEP]]);
	}

	public function testThePendingListIsBounded(): void {
		$this->decisionMapper->expects(self::once())
			->method('findPending')
			->with('alice', Verdict::DELETE, DecisionService::PENDING_LIMIT)
			->willReturn([]);

		$this->service->pending('alice');
	}

	public function testATrashedFileSeenAgainHasBeenRestored(): void {
		$this->withFiles([1 => $this->file(1, '/Photos/a.jpg')]);
		$this->indexService->method('collectionPrefixes')->willReturn(['/To Be Deleted/']);
		$this->decisionMapper->method('findApplied')->willReturn([
			$this->decision(1, CleanupMode::TRASH),
			$this->decision(2, CleanupMode::TRASH),
		]);

		$this->decisionMapper->expects(self::once())->method('removeByFileIds')->with('alice', [1]);
		$this->indexService->expects(self::once())->method('indexFiles')->with('alice', [1]);

		$left = $this->service->applied('alice');

		self::assertSame([2], $this->ids($left));
	}

	public function testCollectedPhotosSurviveAChangeOfFolder(): void {
		// 1 was collected into /To Be Deleted, then the setting was changed to
		// /Later. It is exactly where it was put, so it has not been restored and its
		// undo must not be thrown away.
		$this->withFiles([
			1 => $this->file(1, '/To Be Deleted/a.jpg'),
			2 => $this->file(2, '/Photos/b.jpg'),
		]);
		$this->indexService->method('collectionPrefixes')->willReturn(['/Later/']);
		$this->decisionMapper->method('findApplied')->willReturn([
			$this->decision(1, CleanupMode::FOLDER, '/To Be Deleted'),
			$this->decision(2, CleanupMode::FOLDER, '/To Be Deleted'),
		]);

		$this->decisionMapper->expects(self::once())->method('removeByFileIds')->with('alice', [2]);

		self::assertSame([1], $this->ids($this->service->applied('alice')));
	}

	public function testOlderRowsAreJudgedAgainstEveryCollectionFolder(): void {
		// Applied before the folder was recorded. Still in an old collection folder,
		// which another row remembers, so still collected.
		$this->withFiles([1 => $this->file(1, '/To Be Deleted/a.jpg')]);
		$this->indexService->method('collectionPrefixes')->willReturn(['/Later/', '/To Be Deleted/']);
		$this->decisionMapper->method('findApplied')->willReturn([$this->decision(1, CleanupMode::FOLDER)]);

		$this->decisionMapper->expects(self::never())->method('removeByFileIds');

		self::assertSame([1], $this->ids($this->service->applied('alice')));
	}

	public function testAFileThatIsStillGoneKeepsItsRow(): void {
		$this->withFiles([]);
		$this->decisionMapper->method('findApplied')->willReturn([$this->decision(1, CleanupMode::TRASH)]);
		$this->decisionMapper->expects(self::never())->method('removeByFileIds');

		self::assertSame([1], $this->ids($this->service->applied('alice')));
	}

	/**
	 * @param array<int, File> $files
	 */
	private function withFiles(array $files): void {
		$this->rootFolder->method('getUserFolder')->willReturn($this->userFolder($files));
	}

	private function media(int $fileId, string $path): Media {
		$media = new Media();
		$media->setUserId('alice');
		$media->setFileId($fileId);
		$media->setTakenAt(1_720_000_000);
		$media->setYearMonth('2024-07');
		$media->setName(basename($path));
		$media->setPath($path);
		$media->setSize(100);
		$media->setIsVideo(false);
		return $media;
	}

	private function decision(int $fileId, string $mode, ?string $folder = null): Decision {
		$decision = new Decision();
		$decision->setUserId('alice');
		$decision->setFileId($fileId);
		$decision->setVerdict(Verdict::DELETE);
		$decision->setApplied(true);
		$decision->setAppliedMode($mode);
		$decision->setAppliedFolder($folder);
		return $decision;
	}

	/**
	 * @param Decision[] $decisions
	 * @return int[]
	 */
	private function ids(array $decisions): array {
		return array_map(static fn (Decision $d): int => $d->getFileId(), $decisions);
	}
}
