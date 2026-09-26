<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Service;

use OC\Files\Search\SearchBinaryOperator;
use OC\Files\Search\SearchComparison;
use OC\Files\Search\SearchOrder;
use OC\Files\Search\SearchQuery;
use OCP\Files\File;
use OCP\Files\Folder;
use OCP\Files\Node;
use OCP\Files\Search\ISearchBinaryOperator;
use OCP\Files\Search\ISearchComparison;
use OCP\Files\Search\ISearchOrder;
use Psr\Log\LoggerInterface;

/**
 * Finds photos and videos in a folder, a page at a time.
 *
 * Every dependency on Nextcloud's file search lives in this one class, on purpose.
 * Constructing an `ISearchQuery` needs the `OC\Files\Search` implementations — there
 * is no public factory for them — so the coupling is real and is better concentrated
 * in one small file than sprinkled through the indexer.
 *
 * If those classes ever move, {@see findBatch} degrades to the public
 * `Folder::searchByMime()` instead of breaking. That path loads the whole result set
 * before slicing it, so it is slower on a large library, but it keeps working and
 * returns the same pages in the same order.
 */
class MediaFinder {

	/**
	 * Mime prefixes that count as library media.
	 *
	 * Broader than the Photos app's own list, which excludes GIF and BMP as "too
	 * rarely used for photos". Here the point is to clean up everything, and a folder
	 * of old GIFs is exactly the kind of thing worth going through.
	 */
	private const MIME_PREFIXES = ['image', 'video'];

	public function __construct(
		private LoggerInterface $logger,
	) {
	}

	/**
	 * The next page of media after a cursor, ordered by modification time and then
	 * file id.
	 *
	 * Paged by a keyset rather than an offset. An offset counts rows, so every file
	 * deleted mid-scan — this app deleting three hundred, say — shifts the window and
	 * lets as many unrelated files slip past the pass, which then purges them as stale.
	 * A cursor names the last file seen, and deletions behind it change nothing.
	 *
	 * The key is (mtime, file id) because Nextcloud's file search accepts range
	 * comparisons on mtime but only `eq` and `in` on fileid. The query asks for
	 * everything from the cursor's mtime onwards, and the files at exactly that mtime
	 * which the cursor has already passed are dropped here, by file id.
	 *
	 * A file modified mid-scan moves ahead of the cursor and is simply met again
	 * later in the same pass, which is harmless.
	 *
	 * @param int $afterFileId 0 to start from the beginning
	 * @return File[] empty only when the pass has reached the end
	 */
	public function findBatch(Folder $scope, int $afterMtime, int $afterFileId, int $limit): array {
		if (class_exists(SearchQuery::class)) {
			try {
				return $this->searchPaged($scope, $afterMtime, $afterFileId, $limit);
			} catch (\Throwable $e) {
				$this->logger->warning('Paged media search failed, falling back to searchByMime', [
					'exception' => $e,
					'app' => 'photosweep',
				]);
			}
		}
		return $this->searchByMimeFallback($scope, $afterMtime, $afterFileId, $limit);
	}

	/**
	 * @return File[]
	 */
	private function searchPaged(Folder $scope, int $afterMtime, int $afterFileId, int $limit): array {
		$mimeComparisons = [];
		foreach (self::MIME_PREFIXES as $prefix) {
			$mimeComparisons[] = new SearchComparison(
				ISearchComparison::COMPARE_LIKE,
				'mimetype',
				$prefix . '/%',
			);
		}
		$operator = new SearchBinaryOperator(ISearchBinaryOperator::OPERATOR_OR, $mimeComparisons);
		if ($afterFileId > 0) {
			$operator = new SearchBinaryOperator(ISearchBinaryOperator::OPERATOR_AND, [
				$operator,
				new SearchComparison(ISearchComparison::COMPARE_GREATER_THAN_EQUAL, 'mtime', $afterMtime),
			]);
		}
		$order = [
			new SearchOrder(ISearchOrder::DIRECTION_ASCENDING, 'mtime'),
			new SearchOrder(ISearchOrder::DIRECTION_ASCENDING, 'fileid'),
		];

		// Files at the cursor's own mtime that were already seen come first in this
		// order. Usually they are a handful and the first page has plenty after them,
		// but a bulk copy can stamp thousands of files with one mtime, so keep paging
		// until something new turns up or the results run out. The offset here only
		// ever counts files at that one mtime, never the library behind the cursor.
		for ($offset = 0; ; $offset += $limit) {
			$query = new SearchQuery($operator, $limit, $offset, $order);
			// The parameter is typed against the public ISearchQuery, which this private
			// class implements — psalm cannot see that from the OCP stubs alone.
			/** @psalm-suppress InvalidArgument */
			$page = $scope->search($query);
			$fresh = self::after($this->onlyFiles($page), $afterMtime, $afterFileId);
			if ($fresh !== [] || count($page) < $limit) {
				return $fresh;
			}
		}
	}

	/**
	 * Public-API fallback: fetch everything, then page in memory.
	 *
	 * @return File[]
	 */
	private function searchByMimeFallback(Folder $scope, int $afterMtime, int $afterFileId, int $limit): array {
		$nodes = [];
		foreach (self::MIME_PREFIXES as $prefix) {
			foreach ($scope->searchByMime($prefix) as $node) {
				$nodes[] = $node;
			}
		}

		$files = $this->onlyFiles($nodes);
		// Same order as the paged path, so a fallback mid-scan resumes from the same
		// cursor without skipping anything.
		usort($files, static fn (File $a, File $b): int => [$a->getMTime(), $a->getId()] <=> [$b->getMTime(), $b->getId()]);

		return array_slice(self::after($files, $afterMtime, $afterFileId), 0, $limit);
	}

	/**
	 * The files strictly after the cursor, in the order given.
	 *
	 * @param File[] $files
	 * @return File[]
	 */
	private static function after(array $files, int $afterMtime, int $afterFileId): array {
		if ($afterFileId <= 0) {
			return $files;
		}
		return array_values(array_filter(
			$files,
			static fn (File $f): bool => $f->getMTime() > $afterMtime
				|| ($f->getMTime() === $afterMtime && $f->getId() > $afterFileId),
		));
	}

	/**
	 * @param Node[] $nodes
	 * @return File[]
	 */
	private function onlyFiles(array $nodes): array {
		$files = [];
		foreach ($nodes as $node) {
			if ($node instanceof File) {
				$files[] = $node;
			}
		}
		return $files;
	}

	public static function isMedia(string $mimetype): bool {
		foreach (self::MIME_PREFIXES as $prefix) {
			if (str_starts_with($mimetype, $prefix . '/')) {
				return true;
			}
		}
		return false;
	}
}
