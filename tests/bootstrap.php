<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * These are plain unit tests: they run against the OCP stubs from `nextcloud/ocp`
 * rather than a live server, with the Files API mocked, so they cover the logic that
 * is worth covering — date resolution, month arithmetic, and every decision about
 * which files may be touched — without needing a Nextcloud instance to exist.
 */
require_once __DIR__ . '/../vendor/autoload.php';
require_once __DIR__ . '/unit/stubs/oc.php';
