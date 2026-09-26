<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Service;

use OCP\App\IAppManager;
use OCP\IUserManager;
use OCP\Server;
use Psr\Log\LoggerInterface;

/**
 * Restores files from the Nextcloud trash.
 *
 * Every reference to `files_trashbin` is confined here because that app is optional:
 * an admin can disable it, and on such a server a deletion is permanent and there is
 * nothing to restore from. Rather than depend on it, the app asks this class whether
 * restoring is possible at all and tells the user plainly when it is not — which it
 * must do *before* anyone trashes a thousand photos expecting to get them back.
 */
class TrashService {

	private const TRASH_MANAGER = 'OCA\\Files_Trashbin\\Trash\\ITrashManager';

	public function __construct(
		private IAppManager $appManager,
		private IUserManager $userManager,
		private LoggerInterface $logger,
	) {
	}

	/**
	 * Whether deletions on this server land somewhere recoverable.
	 *
	 * Surfaced in the UI next to the trash option, so the choice is made knowing
	 * whether it is reversible.
	 */
	public function isAvailable(): bool {
		return $this->appManager->isEnabledForUser('files_trashbin')
			&& interface_exists(self::TRASH_MANAGER);
	}

	/**
	 * Puts trashed files back where they came from.
	 *
	 * @param int[] $fileIds
	 * @return int[] the file ids that were actually restored
	 */
	public function restore(string $userId, array $fileIds): array {
		if ($fileIds === [] || !$this->isAvailable()) {
			return [];
		}

		$user = $this->userManager->get($userId);
		if ($user === null) {
			return [];
		}

		$manager = $this->trashManager();
		if ($manager === null) {
			return [];
		}

		$wanted = array_flip($fileIds);
		$restored = [];

		try {
			/** @var iterable<object> $items */
			$items = $manager->listTrashRoot($user);
		} catch (\Throwable $e) {
			$this->logger->warning('Could not list the trash', [
				'exception' => $e,
				'app' => 'photosweep',
			]);
			return [];
		}

		foreach ($items as $item) {
			$id = $this->itemId($item);
			if ($id === null || !isset($wanted[$id])) {
				continue;
			}
			try {
				$item->getTrashBackend()->restoreItem($item);
				$restored[] = $id;
			} catch (\Throwable $e) {
				$this->logger->warning('Could not restore a file from the trash', [
					'exception' => $e,
					'fileId' => $id,
					'app' => 'photosweep',
				]);
			}
			// The trash manager has no lookup by id, so the root listing is the only
			// way in. Stopping once everything asked for has been seen at least keeps
			// the walk short on a trash with years of other deletions in it.
			unset($wanted[$id]);
			if ($wanted === []) {
				break;
			}
		}

		return $restored;
	}

	private function trashManager(): ?object {
		try {
			/** @psalm-suppress MixedReturnStatement */
			return Server::get(self::TRASH_MANAGER);
		} catch (\Throwable $e) {
			$this->logger->debug('Trash manager unavailable', [
				'exception' => $e,
				'app' => 'photosweep',
			]);
			return null;
		}
	}

	private function itemId(object $item): ?int {
		if (!method_exists($item, 'getId')) {
			return null;
		}
		$id = $item->getId();
		return is_int($id) ? $id : null;
	}
}
