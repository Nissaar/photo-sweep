<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Db;

use OCP\AppFramework\Db\Entity;

/**
 * How far one user's index scan has got.
 *
 * @method string getUserId()
 * @method void setUserId(string $userId)
 * @method int getCursorOffset()
 * @method void setCursorOffset(int $cursorOffset)
 * @method int getCursorMtime()
 * @method void setCursorMtime(int $cursorMtime)
 * @method int getCursorFileId()
 * @method void setCursorFileId(int $cursorFileId)
 * @method bool getComplete()
 * @method void setComplete(bool $complete)
 * @method bool getRunning()
 * @method void setRunning(bool $running)
 * @method int getFound()
 * @method void setFound(int $found)
 * @method int getStartedAt()
 * @method void setStartedAt(int $startedAt)
 * @method int getUpdatedAt()
 * @method void setUpdatedAt(int $updatedAt)
 * @method string|null getError()
 * @method void setError(?string $error)
 */
class Scan extends Entity implements \JsonSerializable {
	/*
	 * Every property below is deliberately left uninitialised rather than given a
	 * sensible-looking default.
	 *
	 * `Entity::setter()` returns early when the value being set is identical to the
	 * property's current value, and an early return means the field is never marked
	 * dirty — so `QBMapper::insert()` leaves that column out of the INSERT entirely.
	 * Any property whose default matched a real value would silently write a null.
	 * Starting from null means no real value can ever collide with the default.
	 */
	protected $userId = null;
	/**
	 * No longer read. The scan pages by {@see $cursorMtime} and {@see $cursorFileId}
	 * now; the column stays because dropping it buys nothing and every row still has
	 * it, and Entity refuses to hydrate a column it has no property for.
	 */
	protected $cursorOffset = null;
	protected $cursorMtime = null;
	protected $cursorFileId = null;
	protected $complete = null;
	protected $running = null;
	protected $found = null;
	protected $startedAt = null;
	protected $updatedAt = null;
	protected $error = null;

	public function __construct() {
		$this->addType('cursorOffset', 'integer');
		$this->addType('cursorMtime', 'integer');
		$this->addType('cursorFileId', 'integer');
		$this->addType('complete', 'boolean');
		$this->addType('running', 'boolean');
		$this->addType('found', 'integer');
		$this->addType('startedAt', 'integer');
		$this->addType('updatedAt', 'integer');
	}

	public function jsonSerialize(): array {
		return [
			'complete' => (bool)$this->getComplete(),
			'running' => (bool)$this->getRunning(),
			'found' => (int)$this->getFound(),
			'startedAt' => (int)$this->getStartedAt(),
			'updatedAt' => (int)$this->getUpdatedAt(),
			'error' => $this->getError(),
		];
	}
}
