<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Db;

use OCP\AppFramework\Db\DoesNotExistException;
use OCP\AppFramework\Db\QBMapper;
use OCP\DB\Exception;
use OCP\DB\QueryBuilder\IQueryBuilder;
use OCP\IDBConnection;

/**
 * @template-extends QBMapper<Decision>
 */
class DecisionMapper extends QBMapper {
	public const TABLE = 'photosweep_decisions';

	public function __construct(IDBConnection $db) {
		parent::__construct($db, self::TABLE, Decision::class);
	}

	/**
	 * Re-deciding a photo replaces the old verdict rather than adding a second one.
	 *
	 * Find-then-insert is not atomic: two requests for the same photo at once — a
	 * double tap, or the phone's outbox flushing while the web UI saves — can both
	 * find nothing and both insert. The unique index turns the second insert into a
	 * constraint violation, and that case is exactly an update that lost the race.
	 */
	public function upsert(Decision $decision): Decision {
		try {
			$existing = $this->findByFileId($decision->getUserId(), $decision->getFileId());
			$decision->setId($existing->getId());
			return $this->update($decision);
		} catch (DoesNotExistException $e) {
			// Insert below.
		}

		try {
			return $this->insert($decision);
		} catch (Exception $e) {
			if ($e->getReason() !== Exception::REASON_UNIQUE_CONSTRAINT_VIOLATION) {
				throw $e;
			}
		}

		$existing = $this->findByFileId($decision->getUserId(), $decision->getFileId());
		$decision->setId($existing->getId());
		return $this->update($decision);
	}

	/**
	 * @throws DoesNotExistException
	 */
	public function findByFileId(string $userId, int $fileId): Decision {
		$qb = $this->db->getQueryBuilder();
		$qb->select('*')
			->from(self::TABLE)
			->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
			->andWhere($qb->expr()->eq('file_id', $qb->createNamedParameter($fileId, IQueryBuilder::PARAM_INT)));
		return $this->findEntity($qb);
	}

	/**
	 * Verdicts that have not been carried out yet — the review screen's contents.
	 *
	 * @param int|null $limit null for all of them, which only applying on behalf of a
	 *                        client that does not say which photos it showed needs
	 * @return Decision[]
	 */
	public function findPending(string $userId, string $verdict = Verdict::DELETE, ?int $limit = null): array {
		$qb = $this->db->getQueryBuilder();
		$qb->select('*')
			->from(self::TABLE)
			->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
			->andWhere($qb->expr()->eq('verdict', $qb->createNamedParameter($verdict)))
			->andWhere($qb->expr()->eq('applied', $qb->createNamedParameter(false, IQueryBuilder::PARAM_BOOL)))
			->orderBy('decided_at', 'DESC');
		if ($limit !== null) {
			$qb->setMaxResults($limit);
		}
		return $this->findEntities($qb);
	}

	/**
	 * The pending deletes among [$fileIds], and only those.
	 *
	 * This is what applying acts on when the client says which photos it showed. An
	 * id that is not a pending delete — kept since, undone, already applied, or never
	 * this user's — is simply not returned, so nothing can be done to it.
	 *
	 * @param int[] $fileIds
	 * @return Decision[]
	 */
	public function findPendingByFileIds(string $userId, array $fileIds): array {
		if ($fileIds === []) {
			return [];
		}
		$found = [];
		foreach (array_chunk($fileIds, 900) as $chunk) {
			$qb = $this->db->getQueryBuilder();
			$qb->select('*')
				->from(self::TABLE)
				->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
				->andWhere($qb->expr()->eq('verdict', $qb->createNamedParameter(Verdict::DELETE)))
				->andWhere($qb->expr()->eq('applied', $qb->createNamedParameter(false, IQueryBuilder::PARAM_BOOL)))
				->andWhere($qb->expr()->in('file_id', $qb->createNamedParameter($chunk, IQueryBuilder::PARAM_INT_ARRAY)));
			foreach ($this->findEntities($qb) as $entity) {
				$found[] = $entity;
			}
		}
		return $found;
	}

	/**
	 * Verdicts already carried out — the source list for undo.
	 *
	 * @return Decision[]
	 */
	public function findApplied(string $userId, int $limit = 500): array {
		$qb = $this->db->getQueryBuilder();
		$qb->select('*')
			->from(self::TABLE)
			->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
			->andWhere($qb->expr()->eq('verdict', $qb->createNamedParameter(Verdict::DELETE)))
			->andWhere($qb->expr()->eq('applied', $qb->createNamedParameter(true, IQueryBuilder::PARAM_BOOL)))
			->orderBy('applied_at', 'DESC')
			->setMaxResults($limit);
		return $this->findEntities($qb);
	}

	/**
	 * @param int[] $fileIds
	 * @return Decision[]
	 */
	public function findByFileIds(string $userId, array $fileIds): array {
		if ($fileIds === []) {
			return [];
		}
		$found = [];
		foreach (array_chunk($fileIds, 900) as $chunk) {
			$qb = $this->db->getQueryBuilder();
			$qb->select('*')
				->from(self::TABLE)
				->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
				->andWhere($qb->expr()->in('file_id', $qb->createNamedParameter($chunk, IQueryBuilder::PARAM_INT_ARRAY)));
			foreach ($this->findEntities($qb) as $entity) {
				$found[] = $entity;
			}
		}
		return $found;
	}

	public function countPending(string $userId, string $verdict = Verdict::DELETE): int {
		$qb = $this->db->getQueryBuilder();
		$qb->select($qb->func()->count('*', 'total'))
			->from(self::TABLE)
			->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
			->andWhere($qb->expr()->eq('verdict', $qb->createNamedParameter($verdict)))
			->andWhere($qb->expr()->eq('applied', $qb->createNamedParameter(false, IQueryBuilder::PARAM_BOOL)));
		$result = $qb->executeQuery();
		$total = (int)$result->fetchOne();
		$result->closeCursor();
		return $total;
	}

	public function countByVerdict(string $userId, string $verdict): int {
		$qb = $this->db->getQueryBuilder();
		$qb->select($qb->func()->count('*', 'total'))
			->from(self::TABLE)
			->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
			->andWhere($qb->expr()->eq('verdict', $qb->createNamedParameter($verdict)));
		$result = $qb->executeQuery();
		$total = (int)$result->fetchOne();
		$result->closeCursor();
		return $total;
	}

	/**
	 * How many photos still in the index have been judged, per month, for the grid's
	 * progress bars.
	 *
	 * Joined to the index rather than read from the decision's own copy of the month.
	 * An applied delete has left the index, so it no longer counts towards a month
	 * whose remaining photos nobody has looked at. And a photo whose date changed
	 * after it was judged — its EXIF read later, a new timezone — counts in the month
	 * it is shown in now, not the one it was in when the verdict was given.
	 *
	 * @return array<string, int>
	 */
	public function decidedCountsByMonth(string $userId): array {
		$qb = $this->db->getQueryBuilder();
		$qb->select('m.year_month')
			->selectAlias($qb->func()->count('*'), 'total')
			->from(self::TABLE, 'd')
			->innerJoin('d', MediaMapper::TABLE, 'm', $qb->expr()->andX(
				$qb->expr()->eq('m.user_id', 'd.user_id'),
				$qb->expr()->eq('m.file_id', 'd.file_id'),
			))
			->where($qb->expr()->eq('d.user_id', $qb->createNamedParameter($userId)))
			->groupBy('m.year_month');

		$result = $qb->executeQuery();
		$counts = [];
		while ($row = $result->fetch()) {
			$counts[(string)$row['year_month']] = (int)$row['total'];
		}
		$result->closeCursor();
		return $counts;
	}

	/**
	 * File ids already judged in one month, so reopening it can skip them.
	 *
	 * The month is the index's, for the same reason as {@see decidedCountsByMonth}.
	 *
	 * @return int[]
	 */
	public function decidedFileIdsForMonth(string $userId, string $yearMonth): array {
		$qb = $this->db->getQueryBuilder();
		$qb->select('d.file_id')
			->from(self::TABLE, 'd')
			->innerJoin('d', MediaMapper::TABLE, 'm', $qb->expr()->andX(
				$qb->expr()->eq('m.user_id', 'd.user_id'),
				$qb->expr()->eq('m.file_id', 'd.file_id'),
			))
			->where($qb->expr()->eq('d.user_id', $qb->createNamedParameter($userId)))
			->andWhere($qb->expr()->eq('m.year_month', $qb->createNamedParameter($yearMonth)));

		$result = $qb->executeQuery();
		$ids = [];
		while ($row = $result->fetch()) {
			$ids[] = (int)$row['file_id'];
		}
		$result->closeCursor();
		return $ids;
	}

	/**
	 * Forgets one month's verdicts so it can be reviewed again.
	 *
	 * The month is the index's: resetting July clears the verdicts on the photos July
	 * shows now, including one judged back when its date put it in June.
	 *
	 * Spares applied rows on purpose: those files have already been moved or trashed,
	 * they are no longer in the index to review, and their rows are the undo history.
	 *
	 * @return int how many verdicts were forgotten
	 */
	public function clearUnappliedForMonth(string $userId, string $yearMonth): int {
		// Two steps rather than a DELETE with a subquery: MySQL refuses a subquery
		// that reads the table being deleted from, and the join is what picks the rows.
		$cleared = 0;
		foreach (array_chunk($this->decidedFileIdsForMonth($userId, $yearMonth), 900) as $chunk) {
			$qb = $this->db->getQueryBuilder();
			$qb->delete(self::TABLE)
				->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
				->andWhere($qb->expr()->eq('applied', $qb->createNamedParameter(false, IQueryBuilder::PARAM_BOOL)))
				->andWhere($qb->expr()->in('file_id', $qb->createNamedParameter($chunk, IQueryBuilder::PARAM_INT_ARRAY)));
			$cleared += $qb->executeStatement();
		}
		return $cleared;
	}

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
	 * Marks verdicts as carried out. Called only after the files have actually moved,
	 * so an interrupted run leaves rows pending — safe to retry — rather than claiming
	 * work it did not do.
	 *
	 * @param int[] $fileIds
	 * @param string|null $folder where folder mode put them, relative to the user's
	 *                            files; null in trash mode
	 */
	public function markApplied(string $userId, array $fileIds, int $at, string $mode, ?string $folder = null): void {
		if ($fileIds === []) {
			return;
		}
		foreach (array_chunk($fileIds, 900) as $chunk) {
			$qb = $this->db->getQueryBuilder();
			$qb->update(self::TABLE)
				->set('applied', $qb->createNamedParameter(true, IQueryBuilder::PARAM_BOOL))
				->set('applied_at', $qb->createNamedParameter($at, IQueryBuilder::PARAM_INT))
				->set('applied_mode', $qb->createNamedParameter($mode))
				->set('applied_folder', $qb->createNamedParameter($folder))
				->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
				->andWhere($qb->expr()->in('file_id', $qb->createNamedParameter($chunk, IQueryBuilder::PARAM_INT_ARRAY)));
			$qb->executeStatement();
		}
	}

	/**
	 * Every folder that still holds photos this app collected, as recorded when they
	 * were moved.
	 *
	 * More than one once the folder setting has been changed: the photos collected
	 * under the old setting are still there, still condemned, and must stay out of the
	 * index just as much as the new folder's.
	 *
	 * @return string[] paths relative to the user's files
	 */
	public function appliedFolders(string $userId): array {
		$qb = $this->db->getQueryBuilder();
		$qb->selectDistinct('applied_folder')
			->from(self::TABLE)
			->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)))
			->andWhere($qb->expr()->eq('applied', $qb->createNamedParameter(true, IQueryBuilder::PARAM_BOOL)))
			->andWhere($qb->expr()->isNotNull('applied_folder'));

		$result = $qb->executeQuery();
		$folders = [];
		while ($row = $result->fetch()) {
			$folder = (string)$row['applied_folder'];
			if ($folder !== '') {
				$folders[] = $folder;
			}
		}
		$result->closeCursor();
		return $folders;
	}

	public function removeAllForUser(string $userId): void {
		$qb = $this->db->getQueryBuilder();
		$qb->delete(self::TABLE)
			->where($qb->expr()->eq('user_id', $qb->createNamedParameter($userId)));
		$qb->executeStatement();
	}
}
