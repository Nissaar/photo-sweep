<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Stands in for files_trashbin's restore event, which is not part of the public
 * stubs. The listener recognises the event by this exact class name, so the stub has
 * to carry it.
 */

namespace OCA\Files_Trashbin\Events;

use OCP\Files\Events\Node\AbstractNodesEvent;

if (!class_exists(NodeRestoredEvent::class)) {
	class NodeRestoredEvent extends AbstractNodesEvent {
	}
}
