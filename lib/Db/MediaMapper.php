<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Db;

use OCP\AppFramework\Db\DoesNotExistException;
use OCP\AppFramework\Db\QBMapper;
use OCP\DB\QueryBuilder\IQueryBuilder;
use OCP\IDBConnection;

/**
 * @template-extends QBMapper<Media>
 */
class MediaMapper extends QBMapper {
	public const TABLE = 'photosweep_media';

	public function __construct(IDBConnection $db) {
		parent::__construct($db, self::TABLE, Media::class);
	}

	/**
	 * @throws DoesNotExistException
	 */
	public function findByFileId(string $userId, int $fileId): Media {
		$qb = $this->db->getQueryBuilder();
		$qb->select('*')
			->from(self::TABLE)
			->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
			->andWhere($qb->expr()->eq('file_id', $qb->createNamedParameter($fileId, IQueryBuilder::PARAM_INT)));
		return $this->findEntity($qb);
	}

	/**
	 * How many indexed items each month holds.
	 *
	 * @return array<string, int> keyed by "2024-07", newest month first
	 */
	public function monthCounts(string $userId): array {
		$qb = $this->db->getQueryBuilder();
		$qb->select('year_month')
			->selectAlias($qb->func()->count('*'), 'total')
			->from(self::TABLE)
			->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
			->groupBy('year_month')
			->orderBy('year_month', 'DESC');

		$result = $qb->executeQuery();
		$counts = [];
		while ($row = $result->fetch()) {
			$counts[(string)$row['year_month']] = (int)$row['total'];
		}
		$result->closeCursor();
		return $counts;
	}

	/**
	 * One month's items, newest first — the order the review deck consumes them in.
	 *
	 * @param int $limit 0 for no limit
	 * @return Media[]
	 */
	public function findForMonth(string $userId, string $yearMonth, int $limit = 0, int $offset = 0): array {
		$qb = $this->db->getQueryBuilder();
		$qb->select('*')
			->from(self::TABLE)
			->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
			->andWhere($qb->expr()->eq('year_month', $qb->createNamedParameter($yearMonth)))
			->orderBy('taken_at', 'DESC')
			->addOrderBy('file_id', 'DESC');
		if ($limit > 0) {
			$qb->setMaxResults($limit);
			$qb->setFirstResult($offset);
		}
		return $this->findEntities($qb);
	}

	/**
	 * Which of these file ids are already indexed, and under which row id.
	 *
	 * Lets the indexer decide insert-or-update for a whole batch with one query
	 * instead of a lookup per file, which is the difference between a scan that
	 * finishes and one that times out on a large library.
	 *
	 * @param int[] $fileIds
	 * @return array<int, int> file id => row id
	 */
	public function existingRowIds(string $userId, array $fileIds): array {
		if ($fileIds === []) {
			return [];
		}
		$map = [];
		foreach (array_chunk($fileIds, 900) as $chunk) {
			$qb = $this->db->getQueryBuilder();
			$qb->select('id', 'file_id')
				->from(self::TABLE)
				->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
				->andWhere($qb->expr()->in('file_id', $qb->createNamedParameter($chunk, IQueryBuilder::PARAM_INT_ARRAY)));
			$result = $qb->executeQuery();
			while ($row = $result->fetch()) {
				$map[(int)$row['file_id']] = (int)$row['id'];
			}
			$result->closeCursor();
		}
		return $map;
	}

	public function countForUser(string $userId): int {
		$qb = $this->db->getQueryBuilder();
		$qb->select($qb->func()->count('*', 'total'))
			->from(self::TABLE)
			->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)));
		$result = $qb->executeQuery();
		$total = (int)$result->fetchOne();
		$result->closeCursor();
		return $total;
	}

	/**
	 * Drops an item from the index — used when the file leaves, whether we moved it
	 * or the user did.
	 */
	public function removeByFileId(string $userId, int $fileId): void {
		$qb = $this->db->getQueryBuilder();
		$qb->delete(self::TABLE)
			->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
			->andWhere($qb->expr()->eq('file_id', $qb->createNamedParameter($fileId, IQueryBuilder::PARAM_INT)));
		$qb->executeStatement();
	}

	/**
	 * @param int[] $fileIds
	 */
	public function removeByFileIds(string $userId, array $fileIds): void {
		if ($fileIds === []) {
			return;
		}
		foreach (array_chunk($fileIds, 900) as $chunk) {
			$qb = $this->db->getQueryBuilder();
			$qb->delete(self::TABLE)
				->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
				->andWhere($qb->expr()->in('file_id', $qb->createNamedParameter($chunk, IQueryBuilder::PARAM_INT_ARRAY)));
			$qb->executeStatement();
		}
	}

	/**
	 * Drops every item inside a folder, for when the folder itself is deleted.
	 *
	 * Deleting a folder raises one event for the folder and none for what was in it,
	 * so without this its photos would stay in the grid until the next full pass.
	 *
	 * @param string $folderPath relative to the user's files, e.g. "/Photos/2019"
	 * @return int rows removed
	 */
	public function removeUnderPath(string $userId, string $folderPath): int {
		$prefix = rtrim($folderPath, '/') . '/';
		if ($prefix === '/') {
			// The whole library. Not something a folder delete can mean.
			return 0;
		}
		$qb = $this->db->getQueryBuilder();
		$qb->delete(self::TABLE)
			->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
			->andWhere($qb->expr()->like('path', $qb->createNamedParameter($this->db->escapeLikeParameter($prefix) . '%')));
		return $qb->executeStatement();
	}

	/**
	 * Drops rows a completed pass did not touch.
	 *
	 * Files that disappear while the app is watching are removed by the event
	 * listener, but anything deleted while the app was disabled — or moved outside the
	 * indexed folder — leaves a row behind that the listener never saw. Sweeping by
	 * the pass's own start time removes exactly those, without needing to diff the
	 * whole library in memory.
	 *
	 * @return int rows removed
	 */
	public function removeStale(string $userId, int $indexedBefore): int {
		$qb = $this->db->getQueryBuilder();
		$qb->delete(self::TABLE)
			->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
			->andWhere($qb->expr()->lt('indexed_at', $qb->createNamedParameter($indexedBefore, IQueryBuilder::PARAM_INT)));
		return $qb->executeStatement();
	}

	/** Forgets the whole index for one user, ahead of a full rebuild. */
	public function removeAllForUser(string $userId): void {
		$qb = $this->db->getQueryBuilder();
		$qb->delete(self::TABLE)
			->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)));
		$qb->executeStatement();
	}
}
