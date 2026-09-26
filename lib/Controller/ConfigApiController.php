<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Controller;

use OCA\PhotoSweep\Service\ConfigService;
use OCP\AppFramework\Http\Attribute\NoAdminRequired;
use OCP\AppFramework\Http\Attribute\NoCSRFRequired;
use OCP\AppFramework\Http\DataResponse;
use OCP\AppFramework\OCS\OCSBadRequestException;
use OCP\IRequest;
use OCP\IUserSession;

class ConfigApiController extends AuthenticatedOcsController {

	public function __construct(
		string $appName,
		IRequest $request,
		IUserSession $userSession,
		private ConfigService $configService,
	) {
		parent::__construct($appName, $request, $userSession);
	}

	#[NoAdminRequired]
	#[NoCSRFRequired]
	public function show(): DataResponse {
		return new DataResponse($this->configService->asArray($this->userId()));
	}

	/**
	 * Every field is optional; only what is sent is changed. If any of it is refused,
	 * none of it is saved.
	 */
	#[NoAdminRequired]
	public function update(
		?string $mode = null,
		?string $targetFolder = null,
		?string $sourceFolder = null,
		?bool $skipDecided = null,
	): DataResponse {
		$userId = $this->userId();

		try {
			$this->configService->update($userId, $mode, $targetFolder, $sourceFolder, $skipDecided);
		} catch (\InvalidArgumentException $e) {
			throw new OCSBadRequestException($e->getMessage());
		}

		return new DataResponse($this->configService->asArray($userId));
	}
}
