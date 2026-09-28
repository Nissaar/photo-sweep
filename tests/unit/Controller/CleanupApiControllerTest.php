<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Tests\unit\Controller;

use OCA\PhotoSweep\Controller\CleanupApiController;
use OCA\PhotoSweep\Http\OcsFailureResponse;
use OCA\PhotoSweep\Service\ApplyRefusedException;
use OCA\PhotoSweep\Service\ApplyResult;
use OCA\PhotoSweep\Service\CleanupService;
use OCP\AppFramework\Http;
use OCP\AppFramework\Http\DataResponse;
use OCP\AppFramework\OCS\OCSBadRequestException;
use OCP\IL10N;
use OCP\IRequest;
use OCP\IUser;
use OCP\IUserSession;
use PHPUnit\Framework\MockObject\MockObject;
use PHPUnit\Framework\TestCase;

class CleanupApiControllerTest extends TestCase {

	private CleanupService&MockObject $cleanupService;
	private IRequest&MockObject $request;
	private CleanupApiController $controller;

	protected function setUp(): void {
		parent::setUp();
		$this->cleanupService = $this->createMock(CleanupService::class);
		$this->request = $this->createMock(IRequest::class);
		$this->request->method('getParam')->willReturn(null);
		$this->request->method('getHeader')->willReturn('application/json, text/plain, */*');

		$user = $this->createMock(IUser::class);
		$user->method('getUID')->willReturn('alice');
		$session = $this->createMock(IUserSession::class);
		$session->method('getUser')->willReturn($user);

		$l10n = $this->createMock(IL10N::class);
		$l10n->method('t')->willReturnCallback(
			static fn (string $text, array $parameters = []): string => vsprintf($text, $parameters),
		);

		$this->controller = new CleanupApiController('photosweep', $this->request, $session, $this->cleanupService, $l10n);
	}

	public function testPassesOnTheIdsAndThePermission(): void {
		$this->cleanupService->expects(self::once())
			->method('apply')
			->with('alice', [3, 5], true)
			->willReturn(new ApplyResult('trash', 2));

		// Duplicates, junk and non-positive ids are dropped rather than trusted.
		$response = $this->controller->apply([3, '5', 3, -1, 0, 'x', null], true);

		self::assertInstanceOf(DataResponse::class, $response);
	}

	public function testNoListMeansEveryPendingDelete(): void {
		$this->cleanupService->expects(self::once())
			->method('apply')
			->with('alice', null, false)
			->willReturn(new ApplyResult('trash', 0));

		$this->controller->apply();
	}

	/**
	 * @dataProvider refusals
	 */
	public function testARefusalIsAConflictWithAStableCode(string $code): void {
		$this->cleanupService->method('apply')->willThrowException(new ApplyRefusedException($code, 'Translated text'));

		$response = $this->controller->apply([1]);

		self::assertInstanceOf(OcsFailureResponse::class, $response);
		self::assertSame(Http::STATUS_CONFLICT, $response->getStatus());
		self::assertSame([
			'ocs' => [
				'meta' => ['status' => 'failure', 'statuscode' => 409, 'message' => 'Translated text'],
				'data' => ['error' => $code],
			],
		], json_decode($response->render(), true));
	}

	public function refusals(): array {
		return [
			[ApplyRefusedException::TRASH_UNAVAILABLE],
			[ApplyRefusedException::APPLY_RUNNING],
		];
	}

	public function testRefusesAnUnboundedRestore(): void {
		$this->cleanupService->expects(self::never())->method('restore');
		$this->expectException(OCSBadRequestException::class);
		$this->controller->restore(range(1, 1001));
	}
}
