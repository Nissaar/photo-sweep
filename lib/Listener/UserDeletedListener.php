<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Listener;

use OCA\PhotoSweep\Db\DecisionMapper;
use OCA\PhotoSweep\Db\MediaMapper;
use OCA\PhotoSweep\Db\ScanMapper;
use OCP\EventDispatcher\Event;
use OCP\EventDispatcher\IEventListener;
use OCP\User\Events\UserDeletedEvent;
use Psr\Log\LoggerInterface;

/**
 * Forgets everything about an account when it is deleted.
 *
 * The index holds file names and paths, and the decisions say which photos someone
 * wanted gone; none of that should outlive the account. Leaving the scan row behind
 * is worse than untidy, too: the background job would keep trying to index a user
 * who no longer exists, and log an error about it every run, for good.
 *
 * @template-implements IEventListener<UserDeletedEvent>
 */
class UserDeletedListener implements IEventListener {

	public function __construct(
		private MediaMapper $mediaMapper,
		private DecisionMapper $decisionMapper,
		private ScanMapper $scanMapper,
		private LoggerInterface $logger,
	) {
	}

	public function handle(Event $event): void {
		if (!$event instanceof UserDeletedEvent) {
			return;
		}

		$userId = $event->getUser()->getUID();
		try {
			// Scan row first: once it is gone the background job stops picking this
			// user up, even if one of the larger deletes below fails.
			$this->scanMapper->removeForUser($userId);
			$this->decisionMapper->removeAllForUser($userId);
			$this->mediaMapper->removeAllForUser($userId);
		} catch (\Throwable $e) {
			// Deleting the account matters more than tidying up after it, so this
			// must not throw; but it is data left behind, so it is logged loudly.
			$this->logger->error('Could not remove Photo Sweep data for a deleted user', [
				'exception' => $e,
				'userId' => $userId,
				'app' => 'photosweep',
			]);
		}
	}
}
