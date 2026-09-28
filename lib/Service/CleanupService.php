<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Service;

use OCA\PhotoSweep\Db\Decision;
use OCA\PhotoSweep\Db\DecisionMapper;
use OCA\PhotoSweep\Db\MediaMapper;
use OCA\PhotoSweep\Db\Verdict;
use OCP\Files\File;
use OCP\Files\Folder;
use OCP\Files\IRootFolder;
use OCP\Files\NotFoundException;
use OCP\Files\NotPermittedException;
use OCP\IL10N;
use OCP\Lock\ILockingProvider;
use OCP\Lock\LockedException;
use Psr\Log\LoggerInterface;

/**
 * The only part of the app that changes files.
 *
 * Two rules hold throughout. A verdict is marked applied only *after* the file has
 * actually moved, so an interrupted run leaves work pending and safe to retry rather
 * than claiming something it did not do. And each file is handled on its own, so one
 * permission error costs one photo instead of the whole batch.
 *
 * Reasons given back to the user are short and translated. What actually went wrong
 * — a storage or database message, which can name server paths — goes to the log.
 */
class CleanupService {

	/**
	 * Files carried out between writes of progress.
	 *
	 * A run of three thousand deletes outlives a proxy's timeout. The request keeps
	 * going on the server, but if progress were only written at the end, a user who
	 * tapped again would find everything still pending. Every fifty, what is done is
	 * recorded as done.
	 */
	private const PROGRESS_CHUNK = 50;

	public function __construct(
		private IRootFolder $rootFolder,
		private DecisionMapper $decisionMapper,
		private MediaMapper $mediaMapper,
		private ConfigService $configService,
		private TrashService $trashService,
		private IndexService $indexService,
		private ILockingProvider $lockingProvider,
		private IL10N $l10n,
		private LoggerInterface $logger,
	) {
	}

	/**
	 * Carries out pending DELETE verdicts.
	 *
	 * Callers are responsible for having asked first — nothing here confirms anything.
	 *
	 * @param int[]|null $fileIds the photos the user was shown and confirmed. Only
	 *                            pending deletes among them are carried out. Null means
	 *                            every pending delete, which is what clients that do
	 *                            not send a list have always had.
	 * @param bool $permanent the user has been told that, with the trash app off,
	 *                        these deletes cannot be undone
	 * @param null|callable(int, int): void $onProgress receives (done, total)
	 * @throws ApplyRefusedException before anything is touched
	 */
	public function apply(
		string $userId,
		?array $fileIds = null,
		bool $permanent = false,
		?callable $onProgress = null,
	): ApplyResult {
		$mode = $this->configService->getMode($userId);

		// The trash app can be switched off at any time after the user chose trash
		// mode, and a client may never have shown the warning. Deleting permanently
		// on the strength of a setting made under different conditions is not
		// something to do quietly.
		if ($mode === CleanupMode::TRASH && !$permanent && !$this->trashService->isAvailable()) {
			throw new ApplyRefusedException(
				ApplyRefusedException::TRASH_UNAVAILABLE,
				$this->l10n->t('The trash is turned off on this server, so these files would be deleted permanently.'),
			);
		}

		// One run per user at a time. A second tap while the first request is still
		// working — typically after a timeout — would otherwise move the same photos
		// again, and in folder mode rename them "IMG (2).jpg" beside themselves.
		$lockKey = 'photosweep/apply/' . md5($userId);
		try {
			$this->lockingProvider->acquireLock($lockKey, ILockingProvider::LOCK_EXCLUSIVE, 'Photo Sweep apply for ' . $userId);
		} catch (LockedException $e) {
			throw new ApplyRefusedException(
				ApplyRefusedException::APPLY_RUNNING,
				$this->l10n->t('Your photos are already being deleted. Wait for that to finish, then try again.'),
				$e,
			);
		}

		try {
			return $this->applyLocked($userId, $mode, $fileIds, $onProgress);
		} finally {
			$this->lockingProvider->releaseLock($lockKey, ILockingProvider::LOCK_EXCLUSIVE);
		}
	}

	/**
	 * @param int[]|null $fileIds
	 * @param null|callable(int, int): void $onProgress
	 */
	private function applyLocked(string $userId, string $mode, ?array $fileIds, ?callable $onProgress): ApplyResult {
		$pending = $fileIds === null
			? $this->decisionMapper->findPending($userId, Verdict::DELETE)
			: $this->decisionMapper->findPendingByFileIds($userId, $fileIds);

		if ($pending === []) {
			return new ApplyResult($mode, 0);
		}

		$userFolder = $this->rootFolder->getUserFolder($userId);
		$target = null;
		$targetPath = null;
		if ($mode === CleanupMode::FOLDER) {
			try {
				$target = $this->ensureTargetFolder($userFolder, $this->configService->getTargetFolder($userId));
				$targetPath = $userFolder->getRelativePath($target->getPath());
			} catch (\Throwable $e) {
				$this->logger->warning('Could not create the collection folder', [
					'exception' => $e,
					'app' => 'photosweep',
				]);
				return new ApplyResult(
					$mode,
					0,
					[],
					$this->configService->getTargetFolder($userId),
					$this->l10n->t('Could not create the folder to collect the photos in. Nothing was moved.'),
				);
			}
		}
		$targetPrefix = $targetPath === null ? null : rtrim($targetPath, '/') . '/';

		$total = count($pending);
		$done = 0;
		$succeeded = 0;
		$unrecorded = [];
		$failures = [];

		try {
			foreach ($pending as $decision) {
				$done++;
				$fileId = $decision->getFileId();
				try {
					$reason = $this->applyOne($userId, $userFolder, $decision, $mode, $target, $targetPrefix);
					if ($reason === null) {
						$unrecorded[] = $fileId;
						$succeeded++;
					} else {
						$failures[$fileId] = $reason;
					}
				} catch (NotPermittedException $e) {
					$failures[$fileId] = $this->l10n->t('You do not have permission to change this file.');
				} catch (\Throwable $e) {
					$this->logger->warning('Could not apply a verdict', [
						'exception' => $e,
						'fileId' => $fileId,
						'app' => 'photosweep',
					]);
					$failures[$fileId] = $this->l10n->t('Could not change this file. The server log has the details.');
				} finally {
					if (count($unrecorded) >= self::PROGRESS_CHUNK) {
						$this->recordApplied($userId, $unrecorded, $mode, $targetPath);
						$unrecorded = [];
					}
					if ($onProgress !== null) {
						$onProgress($done, $total);
					}
				}
			}
		} finally {
			$this->recordApplied($userId, $unrecorded, $mode, $targetPath);
		}

		return new ApplyResult(
			$mode,
			$succeeded,
			ApplyResult::failuresFrom($failures),
			$targetPath,
		);
	}

	/**
	 * Carries out one verdict.
	 *
	 * @return string|null why it was not carried out, or null if it was
	 */
	private function applyOne(
		string $userId,
		Folder $userFolder,
		Decision $decision,
		string $mode,
		?Folder $target,
		?string $targetPrefix,
	): ?string {
		$file = $this->findFile($userFolder, $decision->getFileId());
		if ($file === null) {
			// Not the same thing as already deleted. External storage that is offline,
			// or a mount that failed to set up, looks exactly like this, and recording
			// the verdict as carried out would drop the photo from the index while
			// leaving it on disk. It stays pending, to be tried again.
			return $this->l10n->t('Could not find this file. Its storage may be offline.');
		}

		// Checked again here, not just when indexing. The index may predate this
		// check, or the file may have been moved into a share or a group folder
		// since, and deleting or moving it now would take it from someone else.
		if (!OwnFiles::isOwn($file, $userId)) {
			return $this->l10n->t('This file is not in your own storage, so Photo Sweep will not change it.');
		}

		$relativePath = $userFolder->getRelativePath($file->getPath());

		// Already collected: a retry after a run that timed out on the client but
		// finished its moves on the server. Moving it again would only rename it.
		if ($targetPrefix !== null && $relativePath !== null && str_starts_with($relativePath, $targetPrefix)) {
			return null;
		}

		// Where it lives now, not where it was when it was swiped: it may have been
		// moved in Files since, and a restore should put it back where it last was.
		if ($relativePath !== null && $relativePath !== $decision->getOriginPath()) {
			$decision->setOriginPath($relativePath);
			$this->decisionMapper->update($decision);
		}

		if ($mode === CleanupMode::TRASH) {
			$file->delete();
		} else {
			/** @var Folder $target */
			$file->move($target->getPath() . '/' . $this->uniqueName($target, $file->getName()));
		}
		return null;
	}

	/**
	 * @param int[] $fileIds
	 */
	private function recordApplied(string $userId, array $fileIds, string $mode, ?string $targetPath): void {
		if ($fileIds === []) {
			return;
		}
		$this->decisionMapper->markApplied($userId, $fileIds, time(), $mode, $targetPath);
		// They have left the library, so they must leave the month grid too.
		$this->mediaMapper->removeByFileIds($userId, $fileIds);
	}

	/**
	 * Puts already-applied items back.
	 *
	 * Trashed files come back through the trash, moved files are moved home. Either
	 * way the verdict is dropped entirely rather than flipped to KEEP: the photo
	 * returns to the library undecided, which is what "I changed my mind" means.
	 *
	 * Every id asked for is accounted for: restored, or listed with the reason it
	 * was not.
	 *
	 * @param int[] $fileIds
	 * @return array{restored: int, failures: Failure[]}
	 */
	public function restore(string $userId, array $fileIds): array {
		if ($fileIds === []) {
			return ['restored' => 0, 'failures' => []];
		}

		$byId = [];
		foreach ($this->decisionMapper->findByFileIds($userId, $fileIds) as $decision) {
			$byId[$decision->getFileId()] = $decision;
		}

		$byMode = [CleanupMode::TRASH => [], CleanupMode::FOLDER => []];
		$failures = [];
		foreach (array_unique($fileIds) as $fileId) {
			$decision = $byId[$fileId] ?? null;
			if ($decision === null || !$decision->getApplied()) {
				$failures[$fileId] = $this->l10n->t('Photo Sweep has not deleted or moved this file, so there is nothing to restore.');
				continue;
			}
			// Rows from before the mode was recorded were all trash-mode deletes.
			$mode = $decision->getAppliedMode() ?? CleanupMode::TRASH;
			if (!isset($byMode[$mode])) {
				$failures[$fileId] = $this->l10n->t('Photo Sweep does not know how this file was removed, so it cannot bring it back.');
				continue;
			}
			$byMode[$mode][] = $decision;
		}

		$restoredIds = [];
		$trashRestoredIds = [];

		if ($byMode[CleanupMode::TRASH] !== []) {
			$wanted = array_map(static fn (Decision $d): int => $d->getFileId(), $byMode[CleanupMode::TRASH]);
			if (!$this->trashService->isAvailable()) {
				foreach ($wanted as $id) {
					$failures[$id] = $this->l10n->t('The trash is turned off on this server, so this file cannot be brought back.');
				}
			} else {
				$trashRestoredIds = $this->trashService->restore($userId, $wanted);
				$restoredIds = array_merge($restoredIds, $trashRestoredIds);
				foreach (array_diff($wanted, $trashRestoredIds) as $id) {
					$failures[$id] = $this->l10n->t('This file is no longer in the trash. The trash may have been emptied.');
				}
			}
		}

		if ($byMode[CleanupMode::FOLDER] !== []) {
			$userFolder = $this->rootFolder->getUserFolder($userId);
			foreach ($byMode[CleanupMode::FOLDER] as $decision) {
				try {
					$reason = $this->moveBack($userFolder, $decision);
					if ($reason === null) {
						$restoredIds[] = $decision->getFileId();
					} else {
						$failures[$decision->getFileId()] = $reason;
					}
				} catch (\Throwable $e) {
					$this->logger->warning('Could not move a collected file back', [
						'exception' => $e,
						'fileId' => $decision->getFileId(),
						'app' => 'photosweep',
					]);
					$failures[$decision->getFileId()] = $this->l10n->t('Could not move this file back. The server log has the details.');
				}
			}
		}

		if ($restoredIds !== []) {
			$this->decisionMapper->removeByFileIds($userId, $restoredIds);
			// Back in the library, so back in the index. Applying removed these rows
			// because the files had left; without this the photo would be on disk but
			// absent from every month, and could not even be marked again.
			//
			// A trash restore has usually been indexed already, by the listener that
			// hears the trash app's restore event. Only what it did not reach is done
			// here, rather than reading every restored file a second time.
			$toIndex = $restoredIds;
			if ($trashRestoredIds !== []) {
				$indexed = $this->mediaMapper->existingRowIds($userId, $trashRestoredIds);
				$toIndex = array_values(array_filter(
					$restoredIds,
					static fn (int $id): bool => !isset($indexed[$id]),
				));
			}
			try {
				$this->indexService->indexFiles($userId, $toIndex);
			} catch (\Throwable $e) {
				// The files are restored either way, which is the part that matters.
				// A stale index is corrected by the next scan.
				$this->logger->warning('Restored files could not be re-indexed', [
					'exception' => $e,
					'app' => 'photosweep',
				]);
			}
		}

		return ['restored' => count($restoredIds), 'failures' => ApplyResult::failuresFrom($failures)];
	}

	/**
	 * @return string|null why it could not be moved back, or null if it was
	 */
	private function moveBack(Folder $userFolder, Decision $decision): ?string {
		$file = $this->findFile($userFolder, $decision->getFileId());
		if ($file === null) {
			return $this->l10n->t('This file is no longer in the folder it was collected in.');
		}

		$origin = $decision->getOriginPath();
		if ($origin === null || $origin === '') {
			return $this->l10n->t('Where this file came from was not recorded, so it cannot be put back.');
		}

		// dirname() answers "." for a file that lived at the top of the library, which
		// ensureTargetFolder would then try to create as a folder called ".".
		$parentPath = dirname('/' . ltrim($origin, '/'));
		if ($parentPath === '.') {
			$parentPath = '/';
		}
		$parent = $this->ensureTargetFolder($userFolder, $parentPath);
		$file->move($parent->getPath() . '/' . $this->uniqueName($parent, basename($origin)));
		return null;
	}

	/**
	 * Finds a file by id, or null if it has gone.
	 */
	private function findFile(Folder $userFolder, int $fileId): ?File {
		$node = $userFolder->getFirstNodeById($fileId);
		return $node instanceof File ? $node : null;
	}

	/**
	 * Returns the folder at [$path], creating it and any missing parents.
	 */
	private function ensureTargetFolder(Folder $userFolder, string $path): Folder {
		$path = '/' . trim($path, '/');
		if ($path === '/') {
			return $userFolder;
		}

		try {
			$node = $userFolder->get($path);
			if ($node instanceof Folder) {
				return $node;
			}
			throw new NotPermittedException('A file already exists at ' . $path);
		} catch (NotFoundException $e) {
			// Create it below.
		}

		$current = $userFolder;
		foreach (array_filter(explode('/', $path), static fn (string $p): bool => $p !== '') as $segment) {
			try {
				$next = $current->get($segment);
			} catch (NotFoundException $e) {
				$next = $current->newFolder($segment);
			}
			if (!$next instanceof Folder) {
				throw new NotPermittedException('A file already exists at ' . $next->getPath());
			}
			$current = $next;
		}
		return $current;
	}

	/**
	 * A name that does not collide inside [$folder].
	 *
	 * Two photos called IMG_0001.jpg from different folders end up side by side once
	 * they are collected, and silently overwriting one of them would destroy the very
	 * file the mode exists to preserve.
	 */
	private function uniqueName(Folder $folder, string $name): string {
		if (!$folder->nodeExists($name)) {
			return $name;
		}

		$extension = pathinfo($name, PATHINFO_EXTENSION);
		$stem = $extension === '' ? $name : substr($name, 0, -(strlen($extension) + 1));
		$suffix = $extension === '' ? '' : '.' . $extension;

		for ($n = 2; $n < 1000; $n++) {
			$candidate = $stem . ' (' . $n . ')' . $suffix;
			if (!$folder->nodeExists($candidate)) {
				return $candidate;
			}
		}

		return $stem . ' (' . substr(bin2hex(random_bytes(4)), 0, 8) . ')' . $suffix;
	}
}
