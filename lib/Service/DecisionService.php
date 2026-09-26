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
use OCP\AppFramework\Db\DoesNotExistException;
use OCP\Files\Folder;
use OCP\Files\IRootFolder;
use Psr\Log\LoggerInterface;

/**
 * Records what you decided about each photo, and nothing more.
 *
 * Writing a verdict never touches the file. That separation is the whole safety model:
 * you can go through a thousand photos, change your mind about any of them, and walk
 * away without having changed anything at all.
 */
class DecisionService {

	/**
	 * Most pending deletes the review list returns. Far past what anyone reviews in one
	 * sitting, and applying acts only on the ids the client shows, so a longer queue
	 * is simply worked through in more than one go.
	 */
	public const PENDING_LIMIT = 5000;

	public function __construct(
		private DecisionMapper $decisionMapper,
		private MediaMapper $mediaMapper,
		private IRootFolder $rootFolder,
		private IndexService $indexService,
		private LoggerInterface $logger,
	) {
	}

	/**
	 * @throws DoesNotExistException if the file is not in the index
	 * @throws \InvalidArgumentException on an unknown verdict
	 */
	public function record(string $userId, int $fileId, string $verdict): Decision {
		if (!Verdict::isValid($verdict)) {
			throw new \InvalidArgumentException('Unknown verdict: ' . $verdict);
		}

		$media = $this->mediaMapper->findByFileId($userId, $fileId);

		$decision = new Decision();
		$decision->setUserId($userId);
		$decision->setFileId($fileId);
		$decision->setVerdict($verdict);
		$decision->setDecidedAt(time());
		$decision->setApplied(false);
		$decision->setTakenAt($media->getTakenAt());
		$decision->setYearMonth($media->getYearMonth());
		$decision->setName($media->getName());
		$decision->setSize($media->getSize());
		$decision->setIsVideo($media->getIsVideo());
		// Captured now, while the file is still where it belongs. After it moves there
		// is nothing left to read the original location from, and an undo needs it.
		$decision->setOriginPath($media->getPath());

		return $this->decisionMapper->upsert($decision);
	}

	/**
	 * Takes a verdict back, before it has been carried out.
	 *
	 * Refuses to touch an applied one: that row is history, and the way back from
	 * there is a restore, not an undo.
	 *
	 * @return bool whether there was anything to undo
	 */
	public function undo(string $userId, int $fileId): bool {
		try {
			$decision = $this->decisionMapper->findByFileId($userId, $fileId);
		} catch (DoesNotExistException $e) {
			return false;
		}

		if ($decision->getApplied()) {
			return false;
		}

		$this->decisionMapper->delete($decision);
		return true;
	}

	/**
	 * @param array<array{fileId: int, verdict: string}> $verdicts
	 * @return array{recorded: int, skipped: int[]}
	 */
	public function recordMany(string $userId, array $verdicts): array {
		$recorded = 0;
		$skipped = [];
		foreach ($verdicts as $entry) {
			$fileId = (int)($entry['fileId'] ?? 0);
			$verdict = (string)($entry['verdict'] ?? '');
			try {
				$this->record($userId, $fileId, $verdict);
				$recorded++;
			} catch (DoesNotExistException|\InvalidArgumentException $e) {
				// A file that left the library between the deck loading and the
				// phone coming back online is expected, not an error, and so is an
				// entry the client mangled. Anything else — the database failing —
				// is a real error and must not be reported back as a skipped photo,
				// or the phone would drop a verdict it should have retried.
				$skipped[] = $fileId;
			}
		}
		return ['recorded' => $recorded, 'skipped' => $skipped];
	}

	/**
	 * @return Decision[]
	 */
	public function pending(string $userId): array {
		// Deliberately not reconciled against the filesystem. A pending row is a
		// verdict the user gave and has not confirmed, and an external storage that
		// is briefly unreachable looks exactly like a file that has been removed —
		// so checking here would sometimes throw away real work to tidy a list.
		// Applying already copes with a file that has since vanished.
		return $this->decisionMapper->findPending($userId, Verdict::DELETE, self::PENDING_LIMIT);
	}

	/**
	 * @return Decision[]
	 */
	public function applied(string $userId): array {
		// A file that is back in the library has been restored from somewhere other
		// than this app — the Files trash, an admin, a backup. The verdict no longer
		// describes reality, so it goes, and the photo rejoins its month.
		return $this->reconcile($userId, $this->decisionMapper->findApplied($userId));
	}

	/**
	 * Drops rows the filesystem has already disagreed with, and returns the rest.
	 *
	 * The event listener handles the common cases the instant they happen. This is
	 * the backstop for everything it cannot see — a restore performed while the app
	 * was disabled, an `occ` command, a third-party trash backend — and, unlike the
	 * listener, it also cleans up rows that went stale before the listener existed.
	 *
	 * Deliberately confined to the two review screens, which are opened rarely and
	 * bounded in size. The deck must never pay for this.
	 *
	 * @param Decision[] $decisions verdicts already carried out
	 * @return Decision[]
	 */
	private function reconcile(string $userId, array $decisions): array {
		if ($decisions === []) {
			return [];
		}

		try {
			$userFolder = $this->rootFolder->getUserFolder($userId);
		} catch (\Throwable $e) {
			// No view of the files means nothing to check against; show what we have.
			return $decisions;
		}

		// Folder mode does not delete: it moves the file into a folder. Such a file is
		// still present on purpose, and mistaking that for a restore would empty the
		// whole list for everyone who uses that mode. Every folder that still holds
		// collected photos counts, not only the one set now, for rows applied before
		// the folder each photo went to was recorded.
		$collectionPrefixes = $this->indexService->collectionPrefixes($userId);

		$kept = [];
		$staleIds = [];
		foreach ($decisions as $decision) {
			if ($this->isRestored($userFolder, $decision, $collectionPrefixes)) {
				$staleIds[] = $decision->getFileId();
			} else {
				$kept[] = $decision;
			}
		}

		if ($staleIds === []) {
			return $kept;
		}

		try {
			$this->decisionMapper->removeByFileIds($userId, $staleIds);
			// Back in the library and no longer spoken for, so it belongs in a month
			// again — otherwise it is on disk but in no month at all.
			$this->indexService->indexFiles($userId, $staleIds);
		} catch (\Throwable $e) {
			$this->logger->warning('Could not reconcile decisions with the filesystem', [
				'exception' => $e,
				'app' => 'photosweep',
			]);
		}

		return $kept;
	}

	/**
	 * Whether this file has come back, making the recorded verdict untrue.
	 *
	 * A trashed file is not in the user's folder, so for trash mode any sighting
	 * means someone put it back. Folder mode is the awkward one: the file is meant
	 * to still exist, just somewhere else, so only a file that has left the folder it
	 * was collected into counts as restored.
	 *
	 * That folder is the one recorded when the file was moved. Comparing against the
	 * folder setting instead would, the moment the setting changed, call every photo
	 * already collected "restored" and throw away its undo.
	 *
	 * @param string[] $collectionPrefixes for rows that predate the recorded folder
	 */
	private function isRestored(Folder $userFolder, Decision $decision, array $collectionPrefixes): bool {
		$node = $userFolder->getFirstNodeById($decision->getFileId());
		if ($node === null) {
			return false;
		}

		if ($decision->getAppliedMode() !== CleanupMode::FOLDER) {
			return true;
		}

		// Everything below errs towards keeping the row. A stale entry is a cosmetic
		// annoyance; dropping a real one throws away the only record of what this app
		// did to a file, and with it the undo.
		$folder = $decision->getAppliedFolder();
		$prefixes = $folder !== null && $folder !== '' && $folder !== '/'
			? [rtrim($folder, '/') . '/']
			: $collectionPrefixes;
		if ($prefixes === []) {
			return false;
		}

		$relativePath = $userFolder->getRelativePath($node->getPath());
		return $relativePath !== null && !IndexService::isUnderAny($relativePath, $prefixes);
	}
}
