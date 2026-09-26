<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Listener;

use OCA\PhotoSweep\Db\DecisionMapper;
use OCA\PhotoSweep\Db\MediaMapper;
use OCA\PhotoSweep\Db\ScanMapper;
use OCA\PhotoSweep\Service\IndexService;
use OCA\PhotoSweep\Service\MediaFinder;
use OCP\EventDispatcher\Event;
use OCP\EventDispatcher\IEventListener;
use OCP\Files\Events\Node\AbstractNodesEvent;
use OCP\Files\Events\Node\NodeDeletedEvent;
use OCP\Files\File;
use OCP\Files\Folder;
use OCP\Files\IRootFolder;
use OCP\Files\Node;
use Psr\Log\LoggerInterface;

/**
 * Keeps the index in step with the filesystem between scans.
 *
 * Only deletions are handled eagerly, and only for users who have a scan row — that
 * is, people who actually use the app. A photo that has been deleted but is still
 * offered up for review is the single most confusing thing this app could do, so it
 * is worth reacting to immediately rather than waiting for the next pass.
 *
 * Additions are deliberately left to the background job: a file that has just landed
 * usually has no extracted EXIF yet, so indexing it this instant would date it from
 * its filename or mtime and quite possibly file it under the wrong month.
 *
 * A restore from the trash is the exception, because there the file is not new: it
 * was in the index minutes ago and the app is the reason it left.
 *
 * @template-implements IEventListener<NodeDeletedEvent|AbstractNodesEvent>
 */
class FileEventListener implements IEventListener {

	/**
	 * Named as a string because it belongs to files_trashbin, which an admin can
	 * disable. Referring to the class directly would make this app's static analysis
	 * depend on another app being installed, the same reason TrashService names
	 * ITrashManager this way.
	 */
	public const NODE_RESTORED_EVENT = 'OCA\\Files_Trashbin\\Events\\NodeRestoredEvent';

	public function __construct(
		private MediaMapper $mediaMapper,
		private DecisionMapper $decisionMapper,
		private ScanMapper $scanMapper,
		private IndexService $indexService,
		private IRootFolder $rootFolder,
		private LoggerInterface $logger,
	) {
	}

	public function handle(Event $event): void {
		if ($event instanceof NodeDeletedEvent) {
			$this->onDeleted($event->getNode());
			return;
		}

		// Typed against the public parent so getTarget() stays checkable, pinned to
		// the trash event by name so no other AbstractNodesEvent slips through.
		//
		// Psalm cannot see that the trash event extends AbstractNodesEvent, because
		// files_trashbin is not part of this app's analysis, so it reads the
		// comparison as impossible. On a real server it is exactly what arrives.
		/** @psalm-suppress TypeDoesNotContainType */
		if ($event instanceof AbstractNodesEvent && $event::class === self::NODE_RESTORED_EVENT) {
			$this->onRestored($event->getTarget());
		}
	}

	private function onDeleted(Node $node): void {
		try {
			if ($node instanceof Folder) {
				$this->onFolderDeleted($node);
				return;
			}
			$userId = $this->userForMedia($node);
			if ($userId === null) {
				return;
			}
			$this->mediaMapper->removeByFileId($userId, $node->getId());
		} catch (\Throwable $e) {
			// The index going briefly out of date is survivable; blocking a file
			// deletion because of it is not.
			$this->logger->debug('Could not update the index for a deleted file', [
				'exception' => $e,
				'app' => 'photosweep',
			]);
		}
	}

	/**
	 * A deleted folder raises one event, for itself; nothing is heard about what was
	 * inside. Without this every photo in it would stay in the grid, offered up for
	 * review, until the next complete pass noticed.
	 */
	private function onFolderDeleted(Folder $folder): void {
		$userId = $this->ownerOf($folder);
		if ($userId === null || $this->scanMapper->find($userId) === null) {
			return;
		}
		$relativePath = $this->rootFolder->getUserFolder($userId)->getRelativePath($folder->getPath());
		if ($relativePath === null) {
			return;
		}
		$this->mediaMapper->removeUnderPath($userId, $relativePath);
	}

	/**
	 * The photo is back, so the verdict that sent it away is no longer true.
	 *
	 * Dropping the decision row is what puts it back in its month: the deck hides
	 * every file that has one, applied or not, so leaving the row would keep the
	 * photo invisible even after the next scan re-indexed it.
	 *
	 * A trash restore keeps the file id, which is what lets the row be found at all.
	 */
	private function onRestored(Node $node): void {
		try {
			$userId = $this->userForMedia($node);
			if ($userId === null) {
				return;
			}
			$fileId = $node->getId();
			$this->decisionMapper->removeByFileId($userId, $fileId);
			$this->indexService->indexFiles($userId, [$fileId]);
		} catch (\Throwable $e) {
			// Same bargain as a delete: the restore itself must not fail because the
			// app could not keep up. A stale row is corrected when the list is read.
			$this->logger->debug('Could not update the index for a restored file', [
				'exception' => $e,
				'app' => 'photosweep',
			]);
		}
	}

	/**
	 * The owner, but only when this is a media file belonging to someone who uses
	 * the app. Everything else is none of our business.
	 */
	private function userForMedia(Node $node): ?string {
		if (!$node instanceof File || !MediaFinder::isMedia($node->getMimeType())) {
			return null;
		}
		$userId = $this->ownerOf($node);
		if ($userId === null || $this->scanMapper->find($userId) === null) {
			return null;
		}
		return $userId;
	}

	private function ownerOf(Node $node): ?string {
		$owner = $node->getOwner();
		return $owner === null ? null : $owner->getUID();
	}
}
