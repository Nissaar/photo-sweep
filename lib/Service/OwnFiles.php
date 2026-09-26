<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Service;

use OCP\Files\IHomeStorage;
use OCP\Files\Node;

/**
 * Decides whether a file is the user's own, in the sense this app needs.
 *
 * Files shared with you, group folders and external storage all sit inside your
 * folder, and Nextcloud lets you delete the ones you can write to. But that delete
 * removes a colleague's only copy, or empties a folder for a whole group, and moving
 * the file out of a group folder takes it away from everyone else. Someone sweeping
 * "2019" is tidying their own library, not agreeing to any of that.
 *
 * The owner alone does not settle it: a group folder reports whoever is looking at
 * it as the owner. The storage does — only a file on the user's home storage is
 * theirs and nobody else's. Shares say they are not a home storage even though the
 * owner's home is underneath, which is exactly the answer wanted here.
 *
 * External storage is left out too. It is the user's, but it comes and goes, and a
 * library whose contents depend on whether a mount answered is not one to delete from.
 */
final class OwnFiles {

	public static function isOwn(Node $node, string $userId): bool {
		try {
			$owner = $node->getOwner();
			if ($owner === null || $owner->getUID() !== $userId) {
				return false;
			}
			return $node->getStorage()->instanceOfStorage(IHomeStorage::class);
		} catch (\Throwable $e) {
			// A node whose storage cannot be reached is not one to touch.
			return false;
		}
	}
}
