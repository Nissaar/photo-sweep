<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Service;

use OCA\PhotoSweep\AppInfo\Application;
use OCP\Files\Folder;
use OCP\Files\IRootFolder;
use OCP\Files\Node;
use OCP\Files\NotFoundException;
use OCP\IConfig;
use OCP\IL10N;

/**
 * How a DELETE verdict is carried out.
 */
final class CleanupMode {
	/**
	 * Move the file to the Nextcloud trash. A real deletion: it leaves the library and
	 * the storage comes back when the server's retention policy expires it, and it is
	 * recoverable until then.
	 */
	public const TRASH = 'trash';

	/**
	 * Non-destructive: move the file into a folder instead, so it can be checked in
	 * Files and deleted by hand. Nothing is ever lost, but nothing is freed either.
	 */
	public const FOLDER = 'folder';

	public static function isValid(string $mode): bool {
		return $mode === self::TRASH || $mode === self::FOLDER;
	}
}

/**
 * Per-user settings, read straight from Nextcloud's own user config.
 */
class ConfigService {

	public const KEY_MODE = 'cleanup_mode';
	public const KEY_TARGET_FOLDER = 'target_folder';
	public const KEY_SOURCE_FOLDER = 'source_folder';
	public const KEY_SKIP_DECIDED = 'skip_decided';

	public const DEFAULT_TARGET_FOLDER = '/To Be Deleted';
	public const DEFAULT_SOURCE_FOLDER = '/';

	public function __construct(
		private IConfig $config,
		private IRootFolder $rootFolder,
		private IL10N $l10n,
	) {
	}

	public function getMode(string $userId): string {
		$mode = $this->config->getUserValue($userId, Application::APP_ID, self::KEY_MODE, CleanupMode::TRASH);
		return CleanupMode::isValid($mode) ? $mode : CleanupMode::TRASH;
	}

	/** Where folder-mode puts condemned files. */
	public function getTargetFolder(string $userId): string {
		$path = $this->config->getUserValue(
			$userId,
			Application::APP_ID,
			self::KEY_TARGET_FOLDER,
			self::DEFAULT_TARGET_FOLDER,
		);
		return $this->normaliseStored($path, self::DEFAULT_TARGET_FOLDER);
	}

	/** Which part of the user's files gets indexed. */
	public function getSourceFolder(string $userId): string {
		$path = $this->config->getUserValue(
			$userId,
			Application::APP_ID,
			self::KEY_SOURCE_FOLDER,
			self::DEFAULT_SOURCE_FOLDER,
		);
		return $this->normaliseStored($path, self::DEFAULT_SOURCE_FOLDER);
	}

	/** Hide items you have already judged when a month is reopened. */
	public function getSkipDecided(string $userId): bool {
		return $this->config->getUserValue($userId, Application::APP_ID, self::KEY_SKIP_DECIDED, '1') === '1';
	}

	/**
	 * Changes whichever settings are given, all of them or none.
	 *
	 * Everything is checked before anything is written. Saving the mode and then
	 * refusing the folder would leave the user in folder mode with a folder they were
	 * just told was not accepted — and the client, seeing an error, would believe
	 * nothing had changed.
	 *
	 * @throws \InvalidArgumentException with a message fit to show the user
	 */
	public function update(
		string $userId,
		?string $mode = null,
		?string $targetFolder = null,
		?string $sourceFolder = null,
		?bool $skipDecided = null,
	): void {
		if ($mode !== null && !CleanupMode::isValid($mode)) {
			throw new \InvalidArgumentException($this->l10n->t('Unknown cleanup mode'));
		}

		$target = $targetFolder === null
			? $this->getTargetFolder($userId)
			: $this->normaliseInput($targetFolder, self::DEFAULT_TARGET_FOLDER);
		$source = $sourceFolder === null
			? $this->getSourceFolder($userId)
			: $this->normaliseInput($sourceFolder, self::DEFAULT_SOURCE_FOLDER);

		if ($targetFolder !== null) {
			if ($target === '/') {
				throw new \InvalidArgumentException($this->l10n->t('The collection folder cannot be the root of your files'));
			}
			if (!$this->isOwnLocation($userId, $target)) {
				throw new \InvalidArgumentException($this->l10n->t('The collection folder has to be in your own files, not in a folder shared with you, a group folder or external storage'));
			}
		}

		// The collection folder is left out of the index. If it were the photo folder,
		// or held it, the whole library would disappear from the grid.
		if (($targetFolder !== null || $sourceFolder !== null) && self::contains($target, $source)) {
			throw new \InvalidArgumentException($this->l10n->t('The collection folder cannot be your photo folder or a folder that contains it'));
		}

		if ($mode !== null) {
			$this->config->setUserValue($userId, Application::APP_ID, self::KEY_MODE, $mode);
		}
		if ($targetFolder !== null) {
			$this->config->setUserValue($userId, Application::APP_ID, self::KEY_TARGET_FOLDER, $target);
		}
		if ($sourceFolder !== null) {
			$this->config->setUserValue($userId, Application::APP_ID, self::KEY_SOURCE_FOLDER, $source);
		}
		if ($skipDecided !== null) {
			$this->config->setUserValue($userId, Application::APP_ID, self::KEY_SKIP_DECIDED, $skipDecided ? '1' : '0');
		}
	}

	/**
	 * The user's own timezone, which is what months are bucketed in.
	 *
	 * Mirrors how core resolves it, so a photo lands in the same month here as it does
	 * everywhere else in Nextcloud. Falls back to the server default, then UTC, because
	 * an unset or nonsense timezone must not take the indexer down mid-scan.
	 */
	public function getTimeZone(string $userId): \DateTimeZone {
		$name = $this->config->getUserValue($userId, 'core', 'timezone', '');
		if ($name !== '') {
			$zone = $this->parseZone($name);
			if ($zone !== null) {
				return $zone;
			}
		}

		$serverDefault = (string)$this->config->getSystemValue('default_timezone', 'UTC');
		return $this->parseZone($serverDefault) ?? new \DateTimeZone('UTC');
	}

	private function parseZone(string $name): ?\DateTimeZone {
		try {
			return new \DateTimeZone($name);
		} catch (\Throwable $e) {
			return null;
		}
	}

	/**
	 * @return array<string, mixed>
	 */
	public function asArray(string $userId): array {
		return [
			'mode' => $this->getMode($userId),
			'targetFolder' => $this->getTargetFolder($userId),
			'sourceFolder' => $this->getSourceFolder($userId),
			'skipDecided' => $this->getSkipDecided($userId),
			'timezone' => $this->getTimeZone($userId)->getName(),
		];
	}

	/**
	 * Whether [$outer] is [$inner] or one of its parents.
	 */
	private static function contains(string $outer, string $inner): bool {
		if ($outer === '/') {
			return true;
		}
		return $inner === $outer || str_starts_with($inner . '/', $outer . '/');
	}

	/**
	 * Whether a folder at [$path] would be on the user's own storage.
	 *
	 * The folder may not exist yet — applying creates it — so the question goes to
	 * the nearest part of the path that does. Anything below a share or a group folder
	 * is on that storage too, and moving photos there hands them to someone else.
	 */
	private function isOwnLocation(string $userId, string $path): bool {
		$userFolder = $this->rootFolder->getUserFolder($userId);
		$node = $this->nearestExisting($userFolder, $path);
		return OwnFiles::isOwn($node, $userId);
	}

	private function nearestExisting(Folder $userFolder, string $path): Node {
		while ($path !== '/' && $path !== '') {
			try {
				return $userFolder->get($path);
			} catch (NotFoundException $e) {
				$path = dirname($path);
			}
		}
		return $userFolder;
	}

	/**
	 * A path as typed by the user, in the shape the Files API expects: leading slash,
	 * no trailing slash, no empty or "." segments.
	 *
	 * A ".." segment is refused rather than resolved or silently replaced by the
	 * default: the user asked for something specific, and quietly saving something
	 * else while reporting success is how photos end up where nobody expects them.
	 * Only a whole segment counts — "My..Photos" is a perfectly good folder name.
	 *
	 * @throws \InvalidArgumentException
	 */
	private function normaliseInput(string $path, string $fallback): string {
		$path = trim($path);
		if ($path === '') {
			return $fallback;
		}
		$segments = [];
		foreach (explode('/', str_replace('\\', '/', $path)) as $segment) {
			if ($segment === '' || $segment === '.') {
				continue;
			}
			if ($segment === '..') {
				throw new \InvalidArgumentException($this->l10n->t('A folder path cannot contain ".."'));
			}
			$segments[] = $segment;
		}
		return '/' . implode('/', $segments);
	}

	/**
	 * The same shape for a value read back from storage, which should already be
	 * clean. Anything that is not — written by an older version, or by hand with
	 * `occ` — falls back to the default rather than failing a scan.
	 */
	private function normaliseStored(string $path, string $fallback): string {
		try {
			return $this->normaliseInput($path, $fallback);
		} catch (\InvalidArgumentException $e) {
			return $fallback;
		}
	}
}
