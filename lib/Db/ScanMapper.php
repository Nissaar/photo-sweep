<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Db;

use OCP\AppFramework\Db\DoesNotExistException;
use OCP\AppFramework\Db\QBMapper;
use OCP\IDBConnection;

/**
 * @template-extends QBMapper<Scan>
 */
class ScanMapper extends QBMapper {
	public const TABLE = 'photosweep_scans';

	public function __construct(IDBConnection $db) {
		parent::__construct($db, self::TABLE, Scan::class);
	}

	public function find(string $userId): ?Scan {
		$qb = $this->db->getQueryBuilder();
		$qb->select('*')
			->from(self::TABLE)
			->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)));
		try {
			return $this->findEntity($qb);
		} catch (DoesNotExistException $e) {
			return null;
		}
	}

	/** The scan row for a user, created on first use. */
	public function findOrCreate(string $userId): Scan {
		$scan = $this->find($userId);
		if ($scan !== null) {
			return $scan;
		}
		$scan = new Scan();
		$scan->setUserId($userId);
		// Set explicitly rather than relying on the entity's defaults: the properties
		// start as null so that no real value can collide with a default and be
		// silently dropped from the INSERT, which means the starting values have to
		// be written here.
		$scan->setCursorOffset(0);
		$scan->setCursorMtime(0);
		$scan->setCursorFileId(0);
		$scan->setComplete(false);
		$scan->setRunning(false);
		$scan->setFound(0);
		$scan->setStartedAt(0);
		$scan->setUpdatedAt(0);
		return $this->insert($scan);
	}

	/**
	 * Users whose index needs attention, most urgent first.
	 *
	 * Only users who have actually opened the app have a row here, which is the point:
	 * a server with a thousand accounts should not be walking a thousand photo
	 * libraries because two people use this.
	 *
	 * @return Scan[]
	 */
	public function findNeedingWork(int $refreshOlderThan, int $limit = 5): array {
		$qb = $this->db->getQueryBuilder();
		$qb->select('*')
			->from(self::TABLE)
			->where($qb->expr()->orX(
				// An unfinished pass, which is a resume and should go first.
				$qb->expr()->eq('complete', $qb->createNamedParameter(false, \OCP\DB\QueryBuilder\IQueryBuilder::PARAM_BOOL)),
				// A finished one that has gone stale.
				$qb->expr()->lt('updated_at', $qb->createNamedParameter($refreshOlderThan, \OCP\DB\QueryBuilder\IQueryBuilder::PARAM_INT)),
			))
			->orderBy('updated_at', 'ASC')
			->setMaxResults($limit);
		return $this->findEntities($qb);
	}

	public function save(Scan $scan): Scan {
		return $scan->getId() === null ? $this->insert($scan) : $this->update($scan);
	}

	public function removeForUser(string $userId): void {
		$qb = $this->db->getQueryBuilder();
		$qb->delete(self::TABLE)
			->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)));
		$qb->executeStatement();
	}
}
