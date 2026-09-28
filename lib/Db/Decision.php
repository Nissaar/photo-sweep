<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Db;

use OCP\AppFramework\Db\Entity;

/**
 * A verdict you gave one file.
 *
 * `applied` is the important column: until it is true nothing on disk has been
 * touched, and the row can be withdrawn freely. Once it is true the row stops being
 * a plan and becomes history — the record an undo is built from.
 *
 * @method string getUserId()
 * @method void setUserId(string $userId)
 * @method int getFileId()
 * @method void setFileId(int $fileId)
 * @method string getVerdict()
 * @method void setVerdict(string $verdict)
 * @method int getDecidedAt()
 * @method void setDecidedAt(int $decidedAt)
 * @method bool getApplied()
 * @method void setApplied(bool $applied)
 * @method int|null getAppliedAt()
 * @method void setAppliedAt(?int $appliedAt)
 * @method string|null getAppliedMode()
 * @method void setAppliedMode(?string $appliedMode)
 * @method string|null getAppliedFolder()
 * @method void setAppliedFolder(?string $appliedFolder)
 * @method int getTakenAt()
 * @method void setTakenAt(int $takenAt)
 * @method string getYearMonth()
 * @method void setYearMonth(string $yearMonth)
 * @method string getName()
 * @method void setName(string $name)
 * @method int getSize()
 * @method void setSize(int $size)
 * @method string|null getOriginPath()
 * @method void setOriginPath(?string $originPath)
 * @method bool getIsVideo()
 * @method void setIsVideo(bool $isVideo)
 */
class Decision extends Entity implements \JsonSerializable {
	/*
	 * Every property below is deliberately left uninitialised rather than given a
	 * sensible-looking default.
	 *
	 * `Entity::setter()` returns early when the value being set is identical to the
	 * property's current value, and an early return means the field is never marked
	 * dirty — so `QBMapper::insert()` leaves that column out of the INSERT entirely.
	 * With `protected $verdict = 'keep'`, recording a *keep* wrote a row with a null
	 * verdict and the database rejected it, while a delete inserted cleanly. Starting
	 * from null means no real value can ever collide with the default.
	 */
	protected $userId = null;
	protected $fileId = null;
	protected $verdict = null;
	protected $decidedAt = null;
	protected $applied = null;
	protected $appliedAt = null;
	protected $appliedMode = null;
	protected $appliedFolder = null;
	protected $takenAt = null;
	protected $yearMonth = null;
	protected $name = null;
	protected $size = null;
	protected $originPath = null;
	protected $isVideo = null;

	public function __construct() {
		$this->addType('fileId', 'integer');
		$this->addType('decidedAt', 'integer');
		$this->addType('applied', 'boolean');
		$this->addType('appliedAt', 'integer');
		$this->addType('takenAt', 'integer');
		$this->addType('size', 'integer');
		$this->addType('isVideo', 'boolean');
	}

	public function jsonSerialize(): array {
		return [
			'fileId' => (int)$this->getFileId(),
			'verdict' => (string)$this->getVerdict(),
			'decidedAt' => (int)$this->getDecidedAt(),
			'applied' => (bool)$this->getApplied(),
			'appliedAt' => $this->getAppliedAt(),
			'appliedMode' => $this->getAppliedMode(),
			'takenAt' => (int)$this->getTakenAt(),
			'yearMonth' => (string)$this->getYearMonth(),
			'name' => (string)$this->getName(),
			'size' => (int)$this->getSize(),
			'originPath' => $this->getOriginPath(),
			'isVideo' => (bool)$this->getIsVideo(),
		];
	}
}
