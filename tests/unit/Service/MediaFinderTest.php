<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Tests\unit\Service;

use OCA\PhotoSweep\Service\MediaFinder;
use OCP\Files\File;
use OCP\Files\Folder;
use PHPUnit\Framework\TestCase;
use Psr\Log\LoggerInterface;

/**
 * Runs against the public-API fallback, because the core search classes the paged
 * path builds are not in the stubs. Both paths share the cursor rule this checks.
 */
class MediaFinderTest extends TestCase {
	use FileMocks;

	public function testPagesByModificationTimeThenFileId(): void {
		$scope = $this->scope([
			$this->file(5, '/c.jpg', mtime: 300),
			$this->file(9, '/a.jpg', mtime: 100),
			$this->file(2, '/b.jpg', mtime: 200),
			$this->file(3, '/d.jpg', mtime: 200),
		]);
		$finder = new MediaFinder($this->createMock(LoggerInterface::class));

		self::assertSame([9, 2], $this->ids($finder->findBatch($scope, 0, 0, 2)));
		self::assertSame([3, 5], $this->ids($finder->findBatch($scope, 200, 2, 2)));
		self::assertSame([], $this->ids($finder->findBatch($scope, 300, 5, 2)));
	}

	public function testDeletingFilesBehindTheCursorSkipsNothing(): void {
		// The flaw of an offset: delete two files the scan has passed and the next
		// page starts two files late. A cursor does not care what happened behind it.
		$files = [];
		for ($id = 1; $id <= 6; $id++) {
			$files[] = $this->file($id, '/' . $id . '.jpg', mtime: 1000 + $id);
		}
		$finder = new MediaFinder($this->createMock(LoggerInterface::class));

		$first = $finder->findBatch($this->scope($files), 0, 0, 3);
		self::assertSame([1, 2, 3], $this->ids($first));

		unset($files[0], $files[1]);
		$next = $finder->findBatch($this->scope(array_values($files)), 1003, 3, 3);

		self::assertSame([4, 5, 6], $this->ids($next));
	}

	public function testManyFilesWithTheSameTimeAreAllReached(): void {
		// A bulk copy stamps everything with one mtime; only the file id tells them
		// apart, and the cursor has to walk through all of them.
		$files = [];
		for ($id = 1; $id <= 5; $id++) {
			$files[] = $this->file($id, '/' . $id . '.jpg', mtime: 500);
		}
		$finder = new MediaFinder($this->createMock(LoggerInterface::class));
		$scope = $this->scope($files);

		self::assertSame([1, 2], $this->ids($finder->findBatch($scope, 0, 0, 2)));
		self::assertSame([3, 4], $this->ids($finder->findBatch($scope, 500, 2, 2)));
		self::assertSame([5], $this->ids($finder->findBatch($scope, 500, 4, 2)));
	}

	/**
	 * @param File[] $files
	 */
	private function scope(array $files): Folder {
		$scope = $this->createMock(Folder::class);
		$scope->method('searchByMime')->willReturnCallback(
			static fn (string $prefix): array => $prefix === 'image' ? $files : [],
		);
		return $scope;
	}

	/**
	 * @param File[] $files
	 * @return int[]
	 */
	private function ids(array $files): array {
		return array_map(static fn (File $f): int => $f->getId(), $files);
	}
}
