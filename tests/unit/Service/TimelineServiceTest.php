<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Tests\unit\Service;

use OCA\PhotoSweep\Db\DecisionMapper;
use OCA\PhotoSweep\Db\Media;
use OCA\PhotoSweep\Db\MediaMapper;
use OCA\PhotoSweep\Service\ConfigService;
use OCA\PhotoSweep\Service\TimelineService;
use PHPUnit\Framework\MockObject\MockObject;
use PHPUnit\Framework\TestCase;

class TimelineServiceTest extends TestCase {

	private MediaMapper&MockObject $mediaMapper;
	private DecisionMapper&MockObject $decisionMapper;
	private ConfigService&MockObject $configService;
	private TimelineService $service;

	protected function setUp(): void {
		parent::setUp();
		$this->mediaMapper = $this->createMock(MediaMapper::class);
		$this->decisionMapper = $this->createMock(DecisionMapper::class);
		$this->configService = $this->createMock(ConfigService::class);
		$this->service = new TimelineService(
			$this->mediaMapper,
			$this->decisionMapper,
			$this->configService,
		);
	}

	/**
	 * @dataProvider months
	 */
	public function testRecognisesValidMonths(string $value, bool $valid): void {
		self::assertSame($valid, TimelineService::isValidMonth($value));
	}

	public function months(): array {
		return [
			['2024-07', true],
			['2024-01', true],
			['2024-12', true],
			['2024-00', false],
			['2024-13', false],
			['2024-7', false],
			['24-07', false],
			['not-a-month', false],
			// The month reaches the database through a query parameter, so anything
			// that is not exactly this shape is refused before it gets that far.
			["2024-07' OR '1'='1", false],
		];
	}

	public function testReportsProgressPerMonth(): void {
		$this->mediaMapper->method('monthCounts')->willReturn([
			'2024-07' => 40,
			'2024-06' => 12,
			'2024-05' => 0,
		]);
		$this->decisionMapper->method('decidedCountsByMonth')->willReturn([
			'2024-07' => 10,
			'2024-06' => 12,
		]);

		$months = $this->service->months('alice');

		self::assertSame(
			[
				['month' => '2024-07', 'total' => 40, 'reviewed' => 10, 'remaining' => 30, 'done' => false],
				['month' => '2024-06', 'total' => 12, 'reviewed' => 12, 'remaining' => 0, 'done' => true],
				['month' => '2024-05', 'total' => 0, 'reviewed' => 0, 'remaining' => 0, 'done' => false],
			],
			$months,
		);
	}

	public function testAMonthWithUnjudgedPhotosIsNotDoneAfterApplying(): void {
		// July had 52 photos; 40 were marked, the delete was applied, and the 12 that
		// are left were never looked at. The applied rows are still in the decisions
		// table as undo history, but their files have left the index, and the count
		// is joined to the index — so it reports none of the 12 as reviewed. Counting
		// the applied rows, and clamping 40 to 12, called the month finished and hid
		// it from the grid.
		$this->mediaMapper->method('monthCounts')->willReturn(['2024-07' => 12]);
		$this->decisionMapper->method('decidedCountsByMonth')->willReturn([]);

		$months = $this->service->months('alice');

		self::assertSame(0, $months[0]['reviewed']);
		self::assertSame(12, $months[0]['remaining']);
		self::assertFalse($months[0]['done']);
	}

	public function testDecidedItemsAreLookedUpByTheMonthTheyAreIndexedIn(): void {
		// The mapper joins to the index, so a photo judged while filed under June and
		// moved to July since (its EXIF read later) is skipped when July is opened.
		$this->mediaMapper->method('findForMonth')->willReturn([$this->media(1), $this->media(2)]);
		$this->decisionMapper->expects(self::once())
			->method('decidedFileIdsForMonth')
			->with('alice', '2024-07')
			->willReturn([1]);

		$items = $this->service->monthItems('alice', '2024-07', true);

		self::assertSame([2], array_map(static fn (Media $m): int => $m->getFileId(), $items));
	}

	public function testResettingAMonthClearsByTheIndexedMonth(): void {
		$this->decisionMapper->expects(self::once())
			->method('clearUnappliedForMonth')
			->with('alice', '2024-07')
			->willReturn(3);

		self::assertSame(3, $this->service->resetMonth('alice', '2024-07'));
	}

	public function testAMonthDeckIsBounded(): void {
		$this->mediaMapper->expects(self::once())
			->method('findForMonth')
			->with('alice', '2024-07', TimelineService::MONTH_LIMIT)
			->willReturn([]);

		$this->service->monthItems('alice', '2024-07', false);
	}

	public function testTheLimitAppliesAfterJudgedItemsAreSkipped(): void {
		// A month whose first MONTH_LIMIT photos had all been judged must still offer
		// the ones after them, not come back empty.
		$items = [];
		for ($i = 1; $i <= TimelineService::MONTH_LIMIT + 3; $i++) {
			$items[] = $this->media($i);
		}
		$this->mediaMapper->method('findForMonth')->willReturn($items);
		$this->decisionMapper->method('decidedFileIdsForMonth')->willReturn(range(1, TimelineService::MONTH_LIMIT));

		$left = $this->service->monthItems('alice', '2024-07', true);

		self::assertSame(
			[TimelineService::MONTH_LIMIT + 1, TimelineService::MONTH_LIMIT + 2, TimelineService::MONTH_LIMIT + 3],
			array_map(static fn (Media $m): int => $m->getFileId(), $left),
		);
	}

	public function testHidesItemsThatAlreadyHaveAVerdict(): void {
		$this->mediaMapper->method('findForMonth')->willReturn([
			$this->media(1),
			$this->media(2),
			$this->media(3),
		]);
		$this->decisionMapper->method('decidedFileIdsForMonth')->willReturn([2]);

		$items = $this->service->monthItems('alice', '2024-07', true);

		self::assertSame([1, 3], array_map(static fn (Media $m): int => $m->getFileId(), $items));
	}

	public function testCanBeAskedForEverythingIncludingDecidedItems(): void {
		$this->mediaMapper->method('findForMonth')->willReturn([
			$this->media(1),
			$this->media(2),
		]);
		$this->decisionMapper->expects(self::never())->method('decidedFileIdsForMonth');

		$items = $this->service->monthItems('alice', '2024-07', false);

		self::assertCount(2, $items);
	}

	public function testRefusesAMalformedMonth(): void {
		$this->expectException(\InvalidArgumentException::class);
		$this->service->monthItems('alice', '2024-13');
	}

	public function testRefusesToResetAMalformedMonth(): void {
		$this->decisionMapper->expects(self::never())->method('clearUnappliedForMonth');
		$this->expectException(\InvalidArgumentException::class);
		$this->service->resetMonth('alice', 'whenever');
	}

	private function media(int $fileId): Media {
		$media = new Media();
		$media->setFileId($fileId);
		$media->setYearMonth('2024-07');
		return $media;
	}
}
