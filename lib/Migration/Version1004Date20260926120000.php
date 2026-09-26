<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Migration;

use Closure;
use OCP\DB\ISchemaWrapper;
use OCP\DB\Types;
use OCP\Migration\IOutput;
use OCP\Migration\SimpleMigrationStep;

/**
 * Two things the app used to work out from the current settings, and got wrong when
 * those settings changed: where a collected photo was put, and where the scan is.
 */
class Version1004Date20260926120000 extends SimpleMigrationStep {

	/**
	 * @param Closure(): ISchemaWrapper $schemaClosure
	 */
	public function changeSchema(IOutput $output, Closure $schemaClosure, array $options): ?ISchemaWrapper {
		/** @var ISchemaWrapper $schema */
		$schema = $schemaClosure();
		$changed = false;

		if ($schema->hasTable('photosweep_decisions')) {
			$table = $schema->getTable('photosweep_decisions');
			// The folder a photo was collected into, recorded when it was moved. Telling
			// a collected photo from a restored one by comparing against the folder
			// setting breaks the moment that setting changes: every photo already in
			// the old folder would look restored and lose its undo. Null for trash
			// mode, and for rows applied before this column existed.
			if (!$table->hasColumn('applied_folder')) {
				$table->addColumn('applied_folder', Types::STRING, ['notnull' => false, 'length' => 4000]);
				$changed = true;
			}
		}

		if ($schema->hasTable('photosweep_scans')) {
			$table = $schema->getTable('photosweep_scans');
			// A keyset cursor: the modification time and file id of the last file the
			// scan reached. An offset shifts when files are deleted mid-scan, so files
			// were skipped and then purged as stale. Nextcloud's file search accepts
			// range comparisons on mtime, which is what makes this expressible.
			if (!$table->hasColumn('cursor_mtime')) {
				$table->addColumn('cursor_mtime', Types::BIGINT, ['notnull' => true, 'length' => 20, 'default' => 0]);
				$changed = true;
			}
			if (!$table->hasColumn('cursor_file_id')) {
				$table->addColumn('cursor_file_id', Types::BIGINT, ['notnull' => true, 'length' => 20, 'default' => 0]);
				$changed = true;
			}
		}

		return $changed ? $schema : null;
	}
}
