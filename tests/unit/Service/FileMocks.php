<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Tests\unit\Service;

use OCP\Files\File;
use OCP\Files\Folder;
use OCP\Files\Storage\IStorage;
use OCP\IL10N;
use OCP\IUser;
use PHPUnit\Framework\MockObject\MockObject;

/**
 * Builders for the Files API objects the services are handed.
 *
 * The details that matter to the app are few — who owns a file, whether its storage
 * is a home storage, where it sits — so each builder takes exactly those and fills
 * in nothing else.
 */
trait FileMocks {

	/**
	 * @param bool $home whether the file is on a home storage; a share or a group
	 *                   folder is not
	 */
	private function file(
		int $id,
		string $path,
		string $owner = 'alice',
		bool $home = true,
		int $mtime = 1_700_000_000,
		string $mimetype = 'image/jpeg',
	): File&MockObject {
		$user = $this->createMock(IUser::class);
		$user->method('getUID')->willReturn($owner);

		$storage = $this->createMock(IStorage::class);
		$storage->method('instanceOfStorage')->willReturn($home);

		$file = $this->createMock(File::class);
		$file->method('getId')->willReturn($id);
		$file->method('getPath')->willReturn('/' . $owner . '/files' . $path);
		$file->method('getName')->willReturn(basename($path));
		$file->method('getOwner')->willReturn($user);
		$file->method('getStorage')->willReturn($storage);
		$file->method('getMTime')->willReturn($mtime);
		$file->method('getMimeType')->willReturn($mimetype);
		return $file;
	}

	/**
	 * A user folder that finds [$files] by id and answers relative paths the way the
	 * real one does.
	 *
	 * @param array<int, File> $files by file id
	 */
	private function userFolder(array $files, string $userId = 'alice'): Folder&MockObject {
		$folder = $this->createMock(Folder::class);
		$folder->method('getPath')->willReturn('/' . $userId . '/files');
		$folder->method('getFirstNodeById')->willReturnCallback(
			static fn (int $id): ?File => $files[$id] ?? null,
		);
		$folder->method('getRelativePath')->willReturnCallback(static function (string $path) use ($userId): ?string {
			$root = '/' . $userId . '/files';
			if ($path === $root) {
				return '/';
			}
			return str_starts_with($path, $root . '/') ? substr($path, strlen($root)) : null;
		});
		return $folder;
	}

	private function l10n(): IL10N&MockObject {
		$l10n = $this->createMock(IL10N::class);
		$l10n->method('t')->willReturnCallback(
			static fn (string $text, array $parameters = []): string => vsprintf($text, $parameters),
		);
		return $l10n;
	}
}
