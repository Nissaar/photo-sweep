<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Tests\unit\Listener;

require_once __DIR__ . '/../stubs/NodeRestoredEvent.php';

use OCA\Files_Trashbin\Events\NodeRestoredEvent;
use OCA\PhotoSweep\Db\DecisionMapper;
use OCA\PhotoSweep\Db\MediaMapper;
use OCA\PhotoSweep\Db\Scan;
use OCA\PhotoSweep\Db\ScanMapper;
use OCA\PhotoSweep\Listener\FileEventListener;
use OCA\PhotoSweep\Service\IndexService;
use OCA\PhotoSweep\Tests\unit\Service\FileMocks;
use OCP\Files\Events\Node\NodeDeletedEvent;
use OCP\Files\Folder;
use OCP\Files\IRootFolder;
use OCP\IUser;
use PHPUnit\Framework\MockObject\MockObject;
use PHPUnit\Framework\TestCase;
use Psr\Log\LoggerInterface;

class FileEventListenerTest extends TestCase {
	use FileMocks;

	private MediaMapper&MockObject $mediaMapper;
	private DecisionMapper&MockObject $decisionMapper;
	private ScanMapper&MockObject $scanMapper;
	private IndexService&MockObject $indexService;
	private IRootFolder&MockObject $rootFolder;
	private FileEventListener $listener;

	protected function setUp(): void {
		parent::setUp();
		$this->mediaMapper = $this->createMock(MediaMapper::class);
		$this->decisionMapper = $this->createMock(DecisionMapper::class);
		$this->scanMapper = $this->createMock(ScanMapper::class);
		$this->indexService = $this->createMock(IndexService::class);
		$this->rootFolder = $this->createMock(IRootFolder::class);
		$this->rootFolder->method('getUserFolder')->willReturn($this->userFolder([]));

		// Only alice uses the app.
		$this->scanMapper->method('find')->willReturnCallback(
			static fn (string $userId): ?Scan => $userId === 'alice' ? new Scan() : null,
		);

		$this->listener = new FileEventListener(
			$this->mediaMapper,
			$this->decisionMapper,
			$this->scanMapper,
			$this->indexService,
			$this->rootFolder,
			$this->createMock(LoggerInterface::class),
		);
	}

	public function testADeletedPhotoLeavesTheIndex(): void {
		$this->mediaMapper->expects(self::once())->method('removeByFileId')->with('alice', 4);
		$this->listener->handle(new NodeDeletedEvent($this->file(4, '/Photos/a.jpg')));
	}

	public function testIgnoresPeopleWhoDoNotUseTheApp(): void {
		$this->mediaMapper->expects(self::never())->method('removeByFileId');
		$this->listener->handle(new NodeDeletedEvent($this->file(4, '/Photos/a.jpg', 'bob')));
	}

	public function testIgnoresFilesThatAreNotMedia(): void {
		$this->mediaMapper->expects(self::never())->method('removeByFileId');
		$this->listener->handle(new NodeDeletedEvent($this->file(4, '/Notes/a.txt', 'alice', true, 0, 'text/plain')));
	}

	public function testADeletedFolderTakesItsPhotosWithIt(): void {
		// One event for the folder, none for what was in it.
		$user = $this->createMock(IUser::class);
		$user->method('getUID')->willReturn('alice');
		$folder = $this->createMock(Folder::class);
		$folder->method('getOwner')->willReturn($user);
		$folder->method('getPath')->willReturn('/alice/files/Photos/2019');

		$this->mediaMapper->expects(self::once())->method('removeUnderPath')->with('alice', '/Photos/2019');
		$this->mediaMapper->expects(self::never())->method('removeByFileId');

		$this->listener->handle(new NodeDeletedEvent($folder));
	}

	public function testAFailureNeverBlocksTheDelete(): void {
		$this->mediaMapper->method('removeByFileId')->willThrowException(new \RuntimeException('database gone'));

		$this->listener->handle(new NodeDeletedEvent($this->file(4, '/Photos/a.jpg')));
		$this->addToAssertionCount(1);
	}

	public function testARestoreFromTheTrashPutsThePhotoBack(): void {
		$file = $this->file(4, '/Photos/a.jpg');
		$this->decisionMapper->expects(self::once())->method('removeByFileId')->with('alice', 4);
		$this->indexService->expects(self::once())->method('indexFiles')->with('alice', [4]);

		$this->listener->handle(new NodeRestoredEvent($file, $file));
	}
}
