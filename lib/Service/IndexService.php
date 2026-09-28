<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Service;

use OCA\PhotoSweep\Db\DecisionMapper;
use OCA\PhotoSweep\Db\Media;
use OCA\PhotoSweep\Db\MediaMapper;
use OCA\PhotoSweep\Db\Scan;
use OCA\PhotoSweep\Db\ScanMapper;
use OCP\Files\File;
use OCP\Files\Folder;
use OCP\Files\IRootFolder;
use OCP\Files\NotFoundException;
use OCP\IL10N;
use Psr\Log\LoggerInterface;

/**
 * Builds and maintains the month index.
 *
 * The scan is resumable by design. A first pass over a large library is minutes of
 * work, and it runs in a background job that can be cut short at any point, so
 * progress is written after every batch and picked up from a cursor on the next run.
 * Losing the process costs one batch, never the whole scan.
 */
class IndexService {

	/** Files per database round trip. Large enough to amortise, small enough to resume. */
	private const BATCH_SIZE = 500;

	/**
	 * Batches a browser-triggered scan does before handing back.
	 *
	 * Much smaller than the cron budget on purpose: the request has to return inside
	 * the web server's timeout, and the UI would rather show real progress four
	 * thousand files at a time than sit on a spinner and then fail.
	 */
	public const WEB_BATCHES = 8;

	/**
	 * Batches per run, so one user's first scan cannot monopolise cron.
	 * At 500 a batch this indexes 20,000 files per run and resumes on the next.
	 */
	private const MAX_BATCHES_PER_RUN = 40;

	/**
	 * How long a `running` flag is believed before it is treated as a crashed run.
	 * Without this a process killed mid-scan would lock the user out of scanning for good.
	 */
	private const STALE_RUN_SECONDS = 1800;

	/** The scan's error column. */
	private const ERROR_BYTES = 255;

	public function __construct(
		private IRootFolder $rootFolder,
		private MediaMapper $mediaMapper,
		private ScanMapper $scanMapper,
		private DecisionMapper $decisionMapper,
		private MediaFinder $finder,
		private DateResolver $dateResolver,
		private ConfigService $configService,
		private IL10N $l10n,
		private LoggerInterface $logger,
	) {
	}

	public function getStatus(string $userId): Scan {
		return $this->scanMapper->findOrCreate($userId);
	}

	/**
	 * Brings the index up to date, resuming an interrupted pass or starting a new one.
	 *
	 * @param bool $full discard the index and re-read everything, which is how a user
	 *                   corrects drift after reorganising files outside the app
	 * @param null|callable(int, int): void $onProgress receives (indexed so far, batch size)
	 * @param int|null $maxBatches cap for this run; a web request needs a much tighter
	 *                             one than cron does, so the caller sets it
	 */
	public function scan(
		string $userId,
		bool $full = false,
		?callable $onProgress = null,
		?int $maxBatches = null,
	): Scan {
		$scan = $this->scanMapper->findOrCreate($userId);
		$now = time();

		if ($scan->getRunning() && ($now - $scan->getUpdatedAt()) < self::STALE_RUN_SECONDS) {
			return $scan;
		}

		if ($full) {
			$this->mediaMapper->removeAllForUser($userId);
			self::rewind($scan);
		}

		// A completed index is refreshed by walking it again from the start rather than
		// carrying on from where it stopped: resuming would only ever find new
		// uploads and would never notice a file that left.
		if ($scan->getComplete() && !$full) {
			self::rewind($scan);
		}

		$scan->setRunning(true);
		$scan->setError(null);
		if ((int)$scan->getCursorFileId() === 0) {
			$scan->setStartedAt($now);
		}
		$scan->setUpdatedAt($now);
		$this->scanMapper->save($scan);

		try {
			$this->runScan($userId, $scan, $onProgress, $maxBatches ?? self::MAX_BATCHES_PER_RUN);
		} catch (\Throwable $e) {
			$this->logger->error('Photo Sweep index scan failed', [
				'exception' => $e,
				'userId' => $userId,
				'app' => 'photosweep',
			]);
			// Shown to the user, so it says what happened rather than repeating a
			// storage or database message that can carry server paths or SQL. The
			// detail is in the log entry above. Cut by bytes, not characters: the
			// column is 255 bytes, and 250 characters of anything but ASCII is not.
			$scan->setError(mb_strcut(
				$this->l10n->t('Your library could not be read. The server log has the details.'),
				0,
				self::ERROR_BYTES,
			));
		} finally {
			$scan->setRunning(false);
			$scan->setUpdatedAt(time());
			$this->scanMapper->save($scan);
		}

		return $scan;
	}

	private static function rewind(Scan $scan): void {
		$scan->setCursorMtime(0);
		$scan->setCursorFileId(0);
		$scan->setFound(0);
		$scan->setComplete(false);
	}

	/**
	 * @param null|callable(int, int): void $onProgress
	 */
	private function runScan(string $userId, Scan $scan, ?callable $onProgress, int $maxBatches): void {
		$userFolder = $this->rootFolder->getUserFolder($userId);
		$scope = $this->resolveScope($userFolder, $this->configService->getSourceFolder($userId));
		$timezone = $this->configService->getTimeZone($userId);
		$excludedPrefixes = $this->collectionPrefixes($userId);

		$batches = 0;
		while ($batches++ < $maxBatches) {
			$files = $this->finder->findBatch(
				$scope,
				(int)$scan->getCursorMtime(),
				(int)$scan->getCursorFileId(),
				self::BATCH_SIZE,
			);
			// Only an empty page ends the pass. A short one does not: the finder drops
			// files the cursor has already passed, so a page can come back short with
			// more still to come, and ending early would purge the rest as stale.
			if ($files === []) {
				$this->finishPass($userId, $scan);
				break;
			}

			$indexed = $this->indexBatch($userId, $userFolder, $files, $timezone, $excludedPrefixes);

			$last = $files[count($files) - 1];
			$scan->setCursorMtime($last->getMTime());
			$scan->setCursorFileId($last->getId());
			$scan->setFound($scan->getFound() + $indexed);
			$scan->setUpdatedAt(time());
			// Written every batch: this is what makes the scan resumable, and what
			// makes months appear in the grid while it is still running.
			$this->scanMapper->save($scan);

			if ($onProgress !== null) {
				$onProgress($scan->getFound(), count($files));
			}
		}
	}

	/**
	 * Closes out a pass that reached the end of the library.
	 *
	 * Everything still present was re-stamped during this pass, so anything older than
	 * the pass is a file that has gone — deleted while the app was off, moved out of
	 * the indexed folder, or moved into the folder that mode excludes.
	 */
	private function finishPass(string $userId, Scan $scan): void {
		$removed = $this->mediaMapper->removeStale($userId, $scan->getStartedAt());
		if ($removed > 0) {
			$this->logger->info('Removed stale entries after a full index pass', [
				'removed' => $removed,
				'app' => 'photosweep',
			]);
		}
		$scan->setComplete(true);
	}

	/**
	 * The folders whose contents are not part of the library, each with a trailing
	 * slash so a prefix test cannot match "/To Be Deleted Later".
	 *
	 * Whatever folder mode moves files into is not part of the library any more.
	 * Indexing it would put condemned photos back in front of the user, month after
	 * month, which is the one outcome that makes the feature useless. That is the
	 * folder set now, and every folder photos were collected into before the setting
	 * was changed.
	 *
	 * @return string[]
	 */
	public function collectionPrefixes(string $userId): array {
		$folders = $this->decisionMapper->appliedFolders($userId);
		$folders[] = $this->configService->getTargetFolder($userId);

		$prefixes = [];
		foreach ($folders as $folder) {
			$prefix = rtrim($folder, '/') . '/';
			// The root would exclude everything. The setting cannot be the root, so
			// this is only ever a damaged row, and it is safer ignored.
			if ($prefix !== '/') {
				$prefixes[$prefix] = true;
			}
		}
		return array_keys($prefixes);
	}

	/**
	 * @param string[] $prefixes
	 */
	public static function isUnderAny(string $relativePath, array $prefixes): bool {
		foreach ($prefixes as $prefix) {
			if (str_starts_with($relativePath, $prefix)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * @param File[] $files
	 * @param string[] $excludedPrefixes
	 * @return int how many were actually indexed
	 */
	private function indexBatch(
		string $userId,
		Folder $userFolder,
		array $files,
		\DateTimeZone $timezone,
		array $excludedPrefixes,
	): int {
		$fileIds = array_map(static fn (File $f): int => $f->getId(), $files);
		$metadata = $this->dateResolver->preloadMetadata($fileIds);
		$existing = $this->mediaMapper->existingRowIds($userId, $fileIds);

		$indexed = 0;
		foreach ($files as $file) {
			try {
				$relativePath = $userFolder->getRelativePath($file->getPath());
				if ($relativePath === null) {
					continue;
				}
				if (self::isUnderAny($relativePath, $excludedPrefixes)) {
					continue;
				}

				// Only the user's own files, on their own storage: never a share, a
				// group folder or an external mount. See OwnFiles for why.
				if (!OwnFiles::isOwn($file, $userId)) {
					continue;
				}

				$resolved = $this->dateResolver->resolve($file, $timezone, $metadata);

				$media = new Media();
				$media->setUserId($userId);
				$media->setFileId($file->getId());
				$media->setTakenAt($resolved->timestamp);
				$media->setYearMonth(self::yearMonth($resolved->timestamp, $timezone));
				$media->setDateSource($resolved->source);
				$media->setMimetype($file->getMimeType());
				$media->setIsVideo(str_starts_with($file->getMimeType(), 'video/'));
				$media->setName($file->getName());
				$media->setPath($relativePath);
				$media->setSize(max(0, (int)$file->getSize()));
				$media->setIndexedAt(time());

				$rowId = $existing[$file->getId()] ?? null;
				if ($rowId !== null) {
					$media->setId($rowId);
					$this->mediaMapper->update($media);
				} else {
					$this->mediaMapper->insert($media);
				}
				$indexed++;
			} catch (\Throwable $e) {
				// One unreadable file must not abort the scan; the rest of the library
				// is still worth indexing.
				$this->logger->debug('Skipped a file while indexing', [
					'exception' => $e,
					'app' => 'photosweep',
				]);
			}
		}

		return $indexed;
	}

	/**
	 * The folder to index, falling back to everything if the configured one is gone.
	 */
	private function resolveScope(Folder $userFolder, string $sourcePath): Folder {
		if ($sourcePath === '' || $sourcePath === '/') {
			return $userFolder;
		}
		try {
			$node = $userFolder->get($sourcePath);
			if ($node instanceof Folder) {
				return $node;
			}
		} catch (NotFoundException $e) {
			// Configured folder has been renamed or removed.
		}
		$this->logger->warning('Configured source folder is missing, indexing everything instead', [
			'path' => $sourcePath,
			'app' => 'photosweep',
		]);
		return $userFolder;
	}

	/**
	 * Indexes a specific set of files, adding them back to the library.
	 *
	 * Applying a verdict takes a file out of the index because it has left the
	 * library; restoring one has to put it back, or the photo would sit on disk
	 * invisible to the month grid until somebody happened to run a full rebuild.
	 *
	 * @param int[] $fileIds
	 * @return int how many were indexed
	 */
	public function indexFiles(string $userId, array $fileIds): int {
		if ($fileIds === []) {
			return 0;
		}

		$userFolder = $this->rootFolder->getUserFolder($userId);
		$timezone = $this->configService->getTimeZone($userId);

		$files = [];
		foreach ($fileIds as $fileId) {
			$node = $userFolder->getFirstNodeById($fileId);
			if ($node instanceof File) {
				$files[] = $node;
			}
		}

		return $this->indexBatch($userId, $userFolder, $files, $timezone, $this->collectionPrefixes($userId));
	}

	/** "2024-07", in the user's own timezone. */
	public static function yearMonth(int $timestamp, \DateTimeZone $timezone): string {
		return (new \DateTimeImmutable('@' . $timestamp))
			->setTimezone($timezone)
			->format('Y-m');
	}
}
