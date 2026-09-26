<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * The few private core types that public interfaces extend.
 *
 * `nextcloud/ocp` ships the public API only, but `IRootFolder` extends
 * `OC\Hooks\Emitter`, so PHPUnit cannot even build a mock of it without this. The
 * signatures match core's, so a mock behaves as it would against a real server.
 */

namespace OC\Hooks;

if (!interface_exists(Emitter::class)) {
	interface Emitter {
		/**
		 * @param string $scope
		 * @param string $method
		 */
		public function listen($scope, $method, callable $callback);

		/**
		 * @param string $scope optional
		 * @param string $method optional
		 */
		public function removeListener($scope = null, $method = null, ?callable $callback = null);
	}
}
