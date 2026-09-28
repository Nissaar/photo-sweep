<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\AppInfo;

use OCA\PhotoSweep\Listener\FileEventListener;
use OCA\PhotoSweep\Listener\UserDeletedListener;
use OCP\AppFramework\App;
use OCP\AppFramework\Bootstrap\IBootContext;
use OCP\AppFramework\Bootstrap\IBootstrap;
use OCP\AppFramework\Bootstrap\IRegistrationContext;
use OCP\Files\Events\Node\NodeDeletedEvent;
use OCP\User\Events\UserDeletedEvent;

class Application extends App implements IBootstrap {
	public const APP_ID = 'photosweep';

	public function __construct(array $params = []) {
		parent::__construct(self::APP_ID, $params);
	}

	public function register(IRegistrationContext $context): void {
		// A deleted photo must stop being offered for review straight away. New files
		// are picked up by the background job instead, once their EXIF has been read.
		$context->registerEventListener(NodeDeletedEvent::class, FileEventListener::class);

		// Pulling a photo back out of the trash in Files is the user undoing what this
		// app did, just somewhere else. Without this the photo is on disk again while
		// the app still lists it as deleted, and no scan ever corrects it.
		//
		// The class name is only a string here, so naming an app that may be disabled
		// costs nothing: the event is simply never dispatched.
		$context->registerEventListener(FileEventListener::NODE_RESTORED_EVENT, FileEventListener::class);

		// An account's index and verdicts go with it.
		$context->registerEventListener(UserDeletedEvent::class, UserDeletedListener::class);
	}

	public function boot(IBootContext $context): void {
	}
}
