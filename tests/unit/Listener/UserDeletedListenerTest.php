<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Tests\unit\Listener;

use OCA\PhotoSweep\Db\DecisionMapper;
use OCA\PhotoSweep\Db\MediaMapper;
use OCA\PhotoSweep\Db\ScanMapper;
use OCA\PhotoSweep\Listener\UserDeletedListener;
use OCP\IUser;
use OCP\User\Events\UserDeletedEvent;
use PHPUnit\Framework\TestCase;
use Psr\Log\LoggerInterface;

class UserDeletedListenerTest extends TestCase {

	public function testForgetsEverythingAboutTheAccount(): void {
		$media = $this->createMock(MediaMapper::class);
		$decisions = $this->createMock(DecisionMapper::class);
		$scans = $this->createMock(ScanMapper::class);
		$media->expects(self::once())->method('removeAllForUser')->with('alice');
		$decisions->expects(self::once())->method('removeAllForUser')->with('alice');
		$scans->expects(self::once())->method('removeForUser')->with('alice');

		$listener = new UserDeletedListener($media, $decisions, $scans, $this->createMock(LoggerInterface::class));
		$listener->handle(new UserDeletedEvent($this->user('alice')));
	}

	public function testNeverStopsTheAccountBeingDeleted(): void {
		$scans = $this->createMock(ScanMapper::class);
		$scans->method('removeForUser')->willThrowException(new \RuntimeException('database gone'));
		$logger = $this->createMock(LoggerInterface::class);
		$logger->expects(self::once())->method('error');

		$listener = new UserDeletedListener(
			$this->createMock(MediaMapper::class),
			$this->createMock(DecisionMapper::class),
			$scans,
			$logger,
		);
		$listener->handle(new UserDeletedEvent($this->user('alice')));
	}

	private function user(string $uid): IUser {
		$user = $this->createMock(IUser::class);
		$user->method('getUID')->willReturn($uid);
		return $user;
	}
}
