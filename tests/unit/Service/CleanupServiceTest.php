<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Tests\unit\Service;

use OCA\PhotoSweep\Db\Decision;
use OCA\PhotoSweep\Db\DecisionMapper;
use OCA\PhotoSweep\Db\MediaMapper;
use OCA\PhotoSweep\Db\Verdict;
use OCA\PhotoSweep\Service\ApplyRefusedException;
use OCA\PhotoSweep\Service\CleanupMode;
use OCA\PhotoSweep\Service\CleanupService;
use OCA\PhotoSweep\Service\ConfigService;
use OCA\PhotoSweep\Service\Failure;
use OCA\PhotoSweep\Service\IndexService;
use OCA\PhotoSweep\Service\TrashService;
use OCP\Files\File;
use OCP\Files\Folder;
use OCP\Files\IRootFolder;
use OCP\Files\NotFoundException;
use OCP\Lock\ILockingProvider;
use OCP\Lock\LockedException;
use PHPUnit\Framework\MockObject\MockObject;
use PHPUnit\Framework\TestCase;
use Psr\Log\LoggerInterface;

/**
 * The part of the app that deletes things, so the part most worth pinning down.
 */
class CleanupServiceTest extends TestCase {
	use FileMocks;

	private IRootFolder&MockObject $rootFolder;
	private DecisionMapper&MockObject $decisionMapper;
	private MediaMapper&MockObject $mediaMapper;
	private ConfigService&MockObject $configService;
	private TrashService&MockObject $trashService;
	private IndexService&MockObject $indexService;
	private ILockingProvider&MockObject $locking;
	private LoggerInterface&MockObject $logger;
	private CleanupService $service;

	protected function setUp(): void {
		parent::setUp();
		$this->rootFolder = $this->createMock(IRootFolder::class);
		$this->decisionMapper = $this->createMock(DecisionMapper::class);
		$this->mediaMapper = $this->createMock(MediaMapper::class);
		$this->configService = $this->createMock(ConfigService::class);
		$this->trashService = $this->createMock(TrashService::class);
		$this->indexService = $this->createMock(IndexService::class);
		$this->locking = $this->createMock(ILockingProvider::class);
		$this->logger = $this->createMock(LoggerInterface::class);

		$this->configService->method('getMode')->willReturn(CleanupMode::TRASH);
		$this->configService->method('getTargetFolder')->willReturn('/To Be Deleted');
		$this->trashService->method('isAvailable')->willReturn(true);

		$this->service = new CleanupService(
			$this->rootFolder,
			$this->decisionMapper,
			$this->mediaMapper,
			$this->configService,
			$this->trashService,
			$this->indexService,
			$this->locking,
			$this->l10n(),
			$this->logger,
		);
	}

	public function testActsOnlyOnTheIdsTheUserWasShown(): void {
		// A verdict given on the phone after the web list loaded is pending too, but
		// the user confirmed a list that did not include it.
		$shown = $this->file(1, '/Photos/a.jpg');
		$this->withFiles([1 => $shown]);
		$this->decisionMapper->expects(self::never())->method('findPending');
		$this->decisionMapper->expects(self::once())
			->method('findPendingByFileIds')
			->with('alice', [1, 99])
			->willReturn([$this->pending(1, '/Photos/a.jpg')]);
		$shown->expects(self::once())->method('delete');

		$result = $this->service->apply('alice', [1, 99]);

		self::assertSame(1, $result->succeeded);
		self::assertSame([], $result->failures);
	}

	public function testWithoutAListEveryPendingDeleteIsCarriedOut(): void {
		// What clients from before the list was sent have always had.
		$this->withFiles([1 => $this->file(1, '/Photos/a.jpg')]);
		$this->decisionMapper->expects(self::never())->method('findPendingByFileIds');
		$this->decisionMapper->expects(self::once())
			->method('findPending')
			->with('alice', Verdict::DELETE)
			->willReturn([$this->pending(1, '/Photos/a.jpg')]);

		self::assertSame(1, $this->service->apply('alice')->succeeded);
	}

	public function testAnEmptyListDoesNothing(): void {
		$this->decisionMapper->method('findPendingByFileIds')->willReturn([]);
		$this->decisionMapper->expects(self::never())->method('markApplied');

		self::assertSame(0, $this->service->apply('alice', [])->succeeded);
	}

	public function testRefusesAPermanentDeleteNobodyAgreedTo(): void {
		$trash = $this->createMock(TrashService::class);
		$trash->method('isAvailable')->willReturn(false);
		$service = $this->serviceWith(trash: $trash);

		$this->locking->expects(self::never())->method('acquireLock');
		$this->decisionMapper->expects(self::never())->method('findPending');

		try {
			$service->apply('alice');
			self::fail('Expected the apply to be refused');
		} catch (ApplyRefusedException $e) {
			self::assertSame(ApplyRefusedException::TRASH_UNAVAILABLE, $e->getErrorCode());
			self::assertNotSame('', $e->getMessage());
		}
	}

	public function testDeletesPermanentlyOnceTheUserHasAgreed(): void {
		$trash = $this->createMock(TrashService::class);
		$trash->method('isAvailable')->willReturn(false);
		$service = $this->serviceWith(trash: $trash);

		$file = $this->file(1, '/Photos/a.jpg');
		$this->withFiles([1 => $file]);
		$this->decisionMapper->method('findPending')->willReturn([$this->pending(1, '/Photos/a.jpg')]);
		$file->expects(self::once())->method('delete');

		self::assertSame(1, $service->apply('alice', null, true)->succeeded);
	}

	public function testFolderModeNeedsNoPermission(): void {
		// Nothing is deleted in folder mode, so a missing trash changes nothing.
		$trash = $this->createMock(TrashService::class);
		$trash->method('isAvailable')->willReturn(false);
		$service = $this->serviceWith(trash: $trash, mode: CleanupMode::FOLDER);

		$this->decisionMapper->method('findPending')->willReturn([]);

		self::assertSame(0, $service->apply('alice')->succeeded);
	}

	public function testRefusesASecondRunWhileOneIsGoing(): void {
		$this->locking->method('acquireLock')->willThrowException(new LockedException('photosweep/apply/x'));
		$this->decisionMapper->expects(self::never())->method('findPending');
		$this->locking->expects(self::never())->method('releaseLock');

		try {
			$this->service->apply('alice');
			self::fail('Expected the apply to be refused');
		} catch (ApplyRefusedException $e) {
			self::assertSame(ApplyRefusedException::APPLY_RUNNING, $e->getErrorCode());
		}
	}

	public function testTheLockIsPerUserAndAlwaysReleased(): void {
		$this->decisionMapper->method('findPending')->willThrowException(new \RuntimeException('database gone'));

		$this->locking->expects(self::once())
			->method('acquireLock')
			->with('photosweep/apply/' . md5('alice'), ILockingProvider::LOCK_EXCLUSIVE);
		$this->locking->expects(self::once())
			->method('releaseLock')
			->with('photosweep/apply/' . md5('alice'), ILockingProvider::LOCK_EXCLUSIVE);

		$this->expectException(\RuntimeException::class);
		$this->service->apply('alice');
	}

	public function testAFileThatCannotBeFoundStaysPending(): void {
		// Offline external storage looks exactly like this. Calling it done would
		// drop the photo from the index while it is still on disk.
		$this->withFiles([]);
		$this->decisionMapper->method('findPending')->willReturn([$this->pending(7, '/Photos/gone.jpg')]);
		$this->decisionMapper->expects(self::never())->method('markApplied');
		$this->mediaMapper->expects(self::never())->method('removeByFileIds');

		$result = $this->service->apply('alice');

		self::assertSame(0, $result->succeeded);
		self::assertSame([7], $this->failedIds($result->failures));
		self::assertStringContainsString('Could not find this file', $result->failures[0]->reason);
	}

	/**
	 * @dataProvider notOwnFiles
	 */
	public function testNeverTouchesAFileThatIsNotTheUsersOwn(string $owner, bool $home): void {
		// A share, or a group folder that reports the viewer as its owner.
		$file = $this->file(3, '/Shared/b.jpg', $owner, $home);
		$this->withFiles([3 => $file]);
		$this->decisionMapper->method('findPending')->willReturn([$this->pending(3, '/Shared/b.jpg')]);
		$file->expects(self::never())->method('delete');
		$file->expects(self::never())->method('move');
		$this->decisionMapper->expects(self::never())->method('markApplied');

		$result = $this->service->apply('alice');

		self::assertSame([3], $this->failedIds($result->failures));
	}

	public function notOwnFiles(): array {
		return [
			'shared by someone else' => ['bob', true],
			'group folder' => ['alice', false],
		];
	}

	public function testReportsAFailureWithoutRepeatingTheException(): void {
		$file = $this->file(1, '/Photos/a.jpg');
		$file->method('delete')->willThrowException(
			new \RuntimeException('SQLSTATE[HY000]: /var/www/html/data/alice/files is not writable'),
		);
		$this->withFiles([1 => $file]);
		$this->decisionMapper->method('findPending')->willReturn([$this->pending(1, '/Photos/a.jpg')]);
		$this->logger->expects(self::once())->method('warning');

		$result = $this->service->apply('alice');

		self::assertSame([1], $this->failedIds($result->failures));
		self::assertStringNotContainsString('SQLSTATE', $result->failures[0]->reason);
		self::assertStringNotContainsString('/var/www', $result->failures[0]->reason);
	}

	public function testRecordsProgressAsItGoes(): void {
		// 120 deletes are recorded in three writes, so a run cut short by a timeout
		// has already said what it did.
		$files = [];
		$pending = [];
		for ($id = 1; $id <= 120; $id++) {
			$files[$id] = $this->file($id, '/Photos/' . $id . '.jpg');
			$pending[] = $this->pending($id, '/Photos/' . $id . '.jpg');
		}
		$this->withFiles($files);
		$this->decisionMapper->method('findPending')->willReturn($pending);

		$sizes = [];
		$this->decisionMapper->expects(self::exactly(3))
			->method('markApplied')
			->willReturnCallback(static function (string $userId, array $ids) use (&$sizes): void {
				$sizes[] = count($ids);
			});

		self::assertSame(120, $this->service->apply('alice')->succeeded);
		self::assertSame([50, 50, 20], $sizes);
	}

	public function testWhatWasDoneIsRecordedEvenIfTheRunDies(): void {
		$first = $this->file(1, '/Photos/a.jpg');
		$this->withFiles([1 => $first]);
		$this->decisionMapper->method('findPending')->willReturn([$this->pending(1, '/Photos/a.jpg')]);
		$progress = static function (): void {
			throw new \RuntimeException('the request was cut off');
		};

		$this->decisionMapper->expects(self::once())->method('markApplied')->with('alice', [1]);

		$this->expectException(\RuntimeException::class);
		$this->service->apply('alice', null, false, $progress);
	}

	public function testFolderModeRecordsWhereEachPhotoWent(): void {
		$service = $this->serviceWith(mode: CleanupMode::FOLDER);
		$target = $this->targetFolder();
		$file = $this->file(1, '/Photos/a.jpg');
		$this->withFiles([1 => $file], ['/To Be Deleted' => $target]);
		$this->decisionMapper->method('findPending')->willReturn([$this->pending(1, '/Photos/a.jpg')]);

		$file->expects(self::once())->method('move')->with('/alice/files/To Be Deleted/a.jpg');
		$this->decisionMapper->expects(self::once())
			->method('markApplied')
			->with('alice', [1], self::anything(), CleanupMode::FOLDER, '/To Be Deleted');

		$result = $service->apply('alice');

		self::assertSame(1, $result->succeeded);
		self::assertSame('/To Be Deleted', $result->targetFolder);
	}

	public function testARetryDoesNotMoveCollectedPhotosAgain(): void {
		// The first run finished on the server after the client gave up. Moving the
		// file again would rename it "a (2).jpg" next to itself.
		$service = $this->serviceWith(mode: CleanupMode::FOLDER);
		$file = $this->file(1, '/To Be Deleted/a.jpg');
		$this->withFiles([1 => $file], ['/To Be Deleted' => $this->targetFolder()]);
		$this->decisionMapper->method('findPending')->willReturn([$this->pending(1, '/Photos/a.jpg')]);

		$file->expects(self::never())->method('move');
		// And where it came from is not overwritten with where it is now.
		$this->decisionMapper->expects(self::never())->method('update');
		$this->decisionMapper->expects(self::once())->method('markApplied')->with('alice', [1]);

		self::assertSame(1, $service->apply('alice')->succeeded);
	}

	public function testTheOriginalLocationIsTakenWhenTheFileIsMoved(): void {
		// Swiped while in /Photos, moved to /Albums in Files before applying: a
		// restore should put it back in /Albums.
		$file = $this->file(1, '/Albums/a.jpg');
		$this->withFiles([1 => $file]);
		$decision = $this->pending(1, '/Photos/a.jpg');
		$this->decisionMapper->method('findPending')->willReturn([$decision]);

		$this->decisionMapper->expects(self::once())
			->method('update')
			->with(self::callback(static fn (Decision $d): bool => $d->getOriginPath() === '/Albums/a.jpg'));

		$this->service->apply('alice');
	}

	public function testAFolderThatCannotBeCreatedIsReportedPlainly(): void {
		$service = $this->serviceWith(mode: CleanupMode::FOLDER);
		$userFolder = $this->userFolder([]);
		$userFolder->method('get')->willThrowException(new NotFoundException('nope'));
		$userFolder->method('newFolder')->willThrowException(new \RuntimeException('disk quota /var/www exceeded'));
		$this->rootFolder->method('getUserFolder')->willReturn($userFolder);
		$this->decisionMapper->method('findPending')->willReturn([$this->pending(1, '/Photos/a.jpg')]);

		$result = $service->apply('alice');

		self::assertSame(0, $result->succeeded);
		self::assertNotNull($result->error);
		self::assertStringNotContainsString('/var/www', $result->error);
	}

	public function testRestoreAccountsForEveryIdAskedFor(): void {
		$this->decisionMapper->method('findByFileIds')->willReturn([
			$this->pending(1, '/Photos/a.jpg'),
			$this->applied(2, 'something-new'),
		]);

		$result = $this->service->restore('alice', [1, 2, 3]);

		self::assertSame(0, $result['restored']);
		self::assertSame([1, 2, 3], $this->failedIds($result['failures']));
	}

	public function testATrashRestoreIsNotIndexedTwice(): void {
		// The trash app's restore event has already put 1 back in the index.
		$this->decisionMapper->method('findByFileIds')->willReturn([
			$this->applied(1, CleanupMode::TRASH),
			$this->applied(2, CleanupMode::TRASH),
		]);
		$this->trashService->method('restore')->willReturn([1, 2]);
		$this->mediaMapper->method('existingRowIds')->willReturn([1 => 10]);

		$this->indexService->expects(self::once())->method('indexFiles')->with('alice', [2]);

		self::assertSame(2, $this->service->restore('alice', [1, 2])['restored']);
	}

	public function testMovesACollectedPhotoBackWhereItCameFrom(): void {
		$file = $this->file(1, '/To Be Deleted/a.jpg');
		$photos = $this->createMock(Folder::class);
		$photos->method('getPath')->willReturn('/alice/files/Photos');
		$photos->method('nodeExists')->willReturn(false);
		$this->withFiles([1 => $file], ['/Photos' => $photos]);
		$this->decisionMapper->method('findByFileIds')->willReturn([$this->applied(1, CleanupMode::FOLDER, '/Photos/a.jpg')]);

		$file->expects(self::once())->method('move')->with('/alice/files/Photos/a.jpg');
		$this->decisionMapper->expects(self::once())->method('removeByFileIds')->with('alice', [1]);
		$this->indexService->expects(self::once())->method('indexFiles')->with('alice', [1]);

		self::assertSame(1, $this->service->restore('alice', [1])['restored']);
	}

	private function serviceWith(?TrashService $trash = null, string $mode = CleanupMode::TRASH): CleanupService {
		$config = $this->createMock(ConfigService::class);
		$config->method('getMode')->willReturn($mode);
		$config->method('getTargetFolder')->willReturn('/To Be Deleted');
		$this->configService = $config;
		return new CleanupService(
			$this->rootFolder,
			$this->decisionMapper,
			$this->mediaMapper,
			$config,
			$trash ?? $this->trashService,
			$this->indexService,
			$this->locking,
			$this->l10n(),
			$this->logger,
		);
	}

	/**
	 * @param array<int, File> $files
	 * @param array<string, Folder> $folders by path relative to the user's files
	 */
	private function withFiles(array $files, array $folders = []): void {
		$userFolder = $this->userFolder($files);
		$userFolder->method('get')->willReturnCallback(static function (string $path) use ($folders): Folder {
			if (isset($folders[$path])) {
				return $folders[$path];
			}
			throw new NotFoundException($path);
		});
		$this->rootFolder->method('getUserFolder')->willReturn($userFolder);
	}

	private function targetFolder(): Folder&MockObject {
		$target = $this->createMock(Folder::class);
		$target->method('getPath')->willReturn('/alice/files/To Be Deleted');
		$target->method('nodeExists')->willReturn(false);
		return $target;
	}

	private function pending(int $fileId, string $origin): Decision {
		$decision = new Decision();
		$decision->setId($fileId + 1000);
		$decision->setUserId('alice');
		$decision->setFileId($fileId);
		$decision->setVerdict(Verdict::DELETE);
		$decision->setApplied(false);
		$decision->setOriginPath($origin);
		$decision->resetUpdatedFields();
		return $decision;
	}

	private function applied(int $fileId, string $mode, string $origin = '/Photos/x.jpg'): Decision {
		$decision = $this->pending($fileId, $origin);
		$decision->setApplied(true);
		$decision->setAppliedMode($mode);
		return $decision;
	}

	/**
	 * @param Failure[] $failures
	 * @return int[]
	 */
	private function failedIds(array $failures): array {
		$ids = array_map(static fn (Failure $f): int => $f->fileId, $failures);
		sort($ids);
		return $ids;
	}
}
