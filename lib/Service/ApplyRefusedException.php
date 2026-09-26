<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Service;

/**
 * Applying was refused before a single file was touched.
 *
 * Carries a stable code for clients to branch on, and a translated message for them
 * to show. The codes are part of the API and must not change.
 */
class ApplyRefusedException extends \RuntimeException {

	/**
	 * Trash mode, but the trash app is off, so every delete would be permanent. The
	 * client has to say it knows that by sending `permanent: true`.
	 */
	public const TRASH_UNAVAILABLE = 'trash_unavailable';

	/** Another apply for this user has not finished yet. */
	public const APPLY_RUNNING = 'apply_running';

	public function __construct(
		private string $errorCode,
		string $message,
		?\Throwable $previous = null,
	) {
		parent::__construct($message, 0, $previous);
	}

	public function getErrorCode(): string {
		return $this->errorCode;
	}
}
