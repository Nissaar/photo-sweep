<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Controller;

use OCA\PhotoSweep\Http\OcsFailureResponse;
use OCA\PhotoSweep\Service\ApplyRefusedException;
use OCA\PhotoSweep\Service\CleanupService;
use OCP\AppFramework\Http;
use OCP\AppFramework\Http\Attribute\NoAdminRequired;
use OCP\AppFramework\Http\DataResponse;
use OCP\AppFramework\Http\Response;
use OCP\AppFramework\OCS\OCSBadRequestException;
use OCP\IL10N;
use OCP\IRequest;
use OCP\IUserSession;

/**
 * The two endpoints that change files.
 *
 * Both are POST and neither is exempt from CSRF checks, which is deliberate: these
 * are the only calls in the app that cannot be undone by pressing something else.
 */
class CleanupApiController extends AuthenticatedOcsController {

	/**
	 * Most file ids one restore call accepts. The list of applied items it is chosen
	 * from holds 500, so this is room to spare, not a limit anyone meets by using the
	 * app — only by sending something unbounded at it.
	 */
	private const MAX_RESTORE_IDS = 1000;

	public function __construct(
		string $appName,
		IRequest $request,
		IUserSession $userSession,
		private CleanupService $cleanupService,
		private IL10N $l10n,
	) {
		parent::__construct($appName, $request, $userSession);
	}

	/**
	 * Carries out pending delete verdicts, in whichever mode is configured.
	 *
	 * The client is expected to have shown the user the list first, and to send the
	 * ids it showed: only those are acted on, so a verdict given elsewhere since — on
	 * the other device, from a queue that flushed late — is never carried out on the
	 * strength of a confirmation that did not include it. Without the list every
	 * pending delete is carried out, as older clients expect.
	 *
	 * Refused with 409 and a code in `data.error` when nothing may be done:
	 * `trash_unavailable` if the trash is off and `permanent` was not set, or
	 * `apply_running` if another run for this user has not finished.
	 *
	 * @param int[]|null $fileIds the photos the user confirmed
	 * @param bool $permanent the user knows the trash is off and accepts that
	 */
	#[NoAdminRequired]
	public function apply(?array $fileIds = null, bool $permanent = false): Response {
		$ids = $fileIds === null ? null : self::validIds($fileIds);
		try {
			return new DataResponse($this->cleanupService->apply($this->userId(), $ids, $permanent));
		} catch (ApplyRefusedException $e) {
			return new OcsFailureResponse(
				Http::STATUS_CONFLICT,
				$e->getMessage(),
				['error' => $e->getErrorCode()],
				$this->format(),
			);
		}
	}

	/**
	 * Puts already-applied items back: out of the trash, or back out of the folder.
	 *
	 * @param int[] $fileIds
	 */
	#[NoAdminRequired]
	public function restore(array $fileIds): DataResponse {
		if ($fileIds === []) {
			throw new OCSBadRequestException($this->l10n->t('No files given'));
		}
		if (count($fileIds) > self::MAX_RESTORE_IDS) {
			throw new OCSBadRequestException($this->l10n->t('Too many files at once. Restore at most %d at a time.', [self::MAX_RESTORE_IDS]));
		}

		$ids = self::validIds($fileIds);
		if ($ids === []) {
			throw new OCSBadRequestException($this->l10n->t('No valid file ids given'));
		}

		return new DataResponse($this->cleanupService->restore($this->userId(), $ids));
	}

	/**
	 * @param array<array-key, mixed> $fileIds
	 * @return int[]
	 */
	private static function validIds(array $fileIds): array {
		$ids = [];
		foreach ($fileIds as $id) {
			if (is_int($id) || (is_string($id) && ctype_digit($id))) {
				$id = (int)$id;
				if ($id > 0) {
					$ids[$id] = $id;
				}
			}
		}
		return array_values($ids);
	}

	/**
	 * The format core would have answered in, worked out the same way it does.
	 */
	private function format(): string {
		$format = $this->request->getParam('format');
		if (is_string($format) && $format !== '') {
			return $format === 'json' ? 'json' : 'xml';
		}
		return $this->getResponderByHTTPHeader($this->request->getHeader('Accept'), 'xml') === 'json' ? 'json' : 'xml';
	}
}
