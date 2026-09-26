<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Service;

use OCA\PhotoSweep\Db\DecisionMapper;
use OCA\PhotoSweep\Db\Media;
use OCA\PhotoSweep\Db\MediaMapper;
use OCA\PhotoSweep\Db\Verdict;

/**
 * The month grid and the deck for one month.
 *
 * Everything here reads the index rather than the filesystem, which is what lets the
 * grid appear instantly no matter how large the library is.
 */
class TimelineService {

	private const MONTH_PATTERN = '/^\d{4}-(0[1-9]|1[0-2])$/';

	/**
	 * Most items one month's deck returns. A phone dump can put ten thousand photos
	 * in one month, and every one of them is a JSON object and, on the client, a
	 * card. Nobody judges that many in a sitting; with judged photos skipped, the
	 * next opening picks up where this one ran out.
	 */
	public const MONTH_LIMIT = 2000;

	public function __construct(
		private MediaMapper $mediaMapper,
		private DecisionMapper $decisionMapper,
		private ConfigService $configService,
	) {
	}

	public static function isValidMonth(string $yearMonth): bool {
		return preg_match(self::MONTH_PATTERN, $yearMonth) === 1;
	}

	/**
	 * Every month with photos in it, newest first, with review progress.
	 *
	 * @return list<array{month: string, total: int, reviewed: int, remaining: int, done: bool}>
	 */
	public function months(string $userId): array {
		$totals = $this->mediaMapper->monthCounts($userId);
		$reviewed = $this->decisionMapper->decidedCountsByMonth($userId);

		$months = [];
		foreach ($totals as $month => $total) {
			// Only verdicts on photos still in this month's index are counted, so this
			// cannot exceed the total. An applied delete has left the index and no
			// longer counts, which is what keeps a month with unjudged photos in it
			// from being reported as done.
			$done = $reviewed[$month] ?? 0;
			$months[] = [
				'month' => $month,
				'total' => $total,
				'reviewed' => $done,
				'remaining' => max(0, $total - $done),
				'done' => $total > 0 && $done >= $total,
			];
		}

		return $months;
	}

	/**
	 * One month's items, newest first.
	 *
	 * @return Media[]
	 */
	public function monthItems(string $userId, string $yearMonth, ?bool $skipDecided = null): array {
		if (!self::isValidMonth($yearMonth)) {
			throw new \InvalidArgumentException('Expected a month like 2024-07');
		}

		$skip = $skipDecided ?? $this->configService->getSkipDecided($userId);
		if (!$skip) {
			return $this->mediaMapper->findForMonth($userId, $yearMonth, self::MONTH_LIMIT);
		}

		// Filtered before the limit, not after: otherwise a month whose first few
		// thousand photos had all been judged would come back empty with photos left.
		$items = $this->mediaMapper->findForMonth($userId, $yearMonth);
		$decided = array_flip($this->decisionMapper->decidedFileIdsForMonth($userId, $yearMonth));
		return array_slice(array_values(array_filter(
			$items,
			static fn (Media $m): bool => !isset($decided[$m->getFileId()]),
		)), 0, self::MONTH_LIMIT);
	}

	/**
	 * Forgets a month's verdicts so it comes back up for review.
	 *
	 * Nothing on disk is touched, and files already dealt with stay dealt with.
	 */
	public function resetMonth(string $userId, string $yearMonth): int {
		if (!self::isValidMonth($yearMonth)) {
			throw new \InvalidArgumentException('Expected a month like 2024-07');
		}
		return $this->decisionMapper->clearUnappliedForMonth($userId, $yearMonth);
	}

	/**
	 * Headline numbers for the top of the app.
	 *
	 * Takes the month list when the caller already has it. Every swipe refreshes this,
	 * and recomputing the grouped-by-month aggregate a second time per request put
	 * that query in competition with the preview the user is waiting to see.
	 *
	 * @param list<array{month: string, total: int, reviewed: int, remaining: int, done: bool}>|null $months
	 * @return array{indexed: int, months: int, monthsToReview: int, photosLeft: int, pendingDeletes: int, kept: int}
	 */
	public function summary(string $userId, ?array $months = null): array {
		$months ??= $this->months($userId);

		return [
			'indexed' => $this->mediaMapper->countForUser($userId),
			'months' => count($months),
			'monthsToReview' => count(array_filter($months, static fn (array $m): bool => !$m['done'])),
			'photosLeft' => array_sum(array_column($months, 'remaining')),
			'pendingDeletes' => $this->decisionMapper->countPending($userId, Verdict::DELETE),
			'kept' => $this->decisionMapper->countByVerdict($userId, Verdict::KEEP),
		];
	}
}
