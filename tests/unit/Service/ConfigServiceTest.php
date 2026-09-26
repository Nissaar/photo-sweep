<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Tests\unit\Service;

use OCA\PhotoSweep\AppInfo\Application;
use OCA\PhotoSweep\Service\CleanupMode;
use OCA\PhotoSweep\Service\ConfigService;
use OCP\Files\Folder;
use OCP\Files\IRootFolder;
use OCP\Files\Node;
use OCP\Files\NotFoundException;
use OCP\IConfig;
use PHPUnit\Framework\MockObject\MockObject;
use PHPUnit\Framework\TestCase;

class ConfigServiceTest extends TestCase {
	use FileMocks;

	private IConfig&MockObject $config;
	private IRootFolder&MockObject $rootFolder;
	/** @var array<string, string> what the user has saved, by key */
	private array $stored = [];
	/** @var array<string, Node> nodes that exist, by path relative to the user's files */
	private array $nodes = [];
	private ConfigService $service;

	protected function setUp(): void {
		parent::setUp();
		$this->config = $this->createMock(IConfig::class);
		$this->config->method('getUserValue')->willReturnCallback(
			fn (string $userId, string $app, string $key, mixed $default = ''): mixed => $this->stored[$key] ?? $default,
		);
		$this->config->method('getSystemValue')->willReturnArgument(1);

		$this->rootFolder = $this->createMock(IRootFolder::class);
		$userFolder = $this->userFolder([]);
		$userFolder->method('get')->willReturnCallback(function (string $path): Node {
			if (isset($this->nodes[$path])) {
				return $this->nodes[$path];
			}
			throw new NotFoundException($path);
		});
		// The root of your files is your own.
		$userFolder->method('getOwner')->willReturn($this->file(0, '/x')->getOwner());
		$userFolder->method('getStorage')->willReturn($this->file(0, '/x')->getStorage());
		$this->rootFolder->method('getUserFolder')->willReturn($userFolder);

		$this->service = new ConfigService($this->config, $this->rootFolder, $this->l10n());
	}

	public function testSavesEveryFieldGiven(): void {
		$saved = [];
		$this->config->method('setUserValue')->willReturnCallback(
			static function (string $userId, string $app, string $key, string $value) use (&$saved): void {
				self::assertSame(Application::APP_ID, $app);
				$saved[$key] = $value;
			},
		);

		$this->service->update('alice', CleanupMode::FOLDER, 'Later/', '/Photos', false);

		self::assertSame([
			ConfigService::KEY_MODE => CleanupMode::FOLDER,
			ConfigService::KEY_TARGET_FOLDER => '/Later',
			ConfigService::KEY_SOURCE_FOLDER => '/Photos',
			ConfigService::KEY_SKIP_DECIDED => '0',
		], $saved);
	}

	public function testTwoDotsInsideANameAreFine(): void {
		$this->config->expects(self::once())
			->method('setUserValue')
			->with('alice', Application::APP_ID, ConfigService::KEY_TARGET_FOLDER, '/My..Photos');

		$this->service->update('alice', null, 'My..Photos');
	}

	/**
	 * @dataProvider refused
	 */
	public function testRefusesAndSavesNothing(?string $target, ?string $source): void {
		// The mode comes along in the same request, and must not be saved on its own
		// while the folder is refused.
		$this->config->expects(self::never())->method('setUserValue');
		$this->expectException(\InvalidArgumentException::class);

		$this->service->update('alice', CleanupMode::FOLDER, $target, $source);
	}

	public function refused(): array {
		return [
			'a real .. segment' => ['/Photos/../Documents', null],
			'the root' => ['/', null],
			'the photo folder itself' => ['/Photos', '/Photos'],
			'a parent of the photo folder' => ['/Photos', '/Photos/2019'],
			'the photo folder moved inside it' => [null, '/To Be Deleted/Old'],
			'a source with ..' => [null, '/../elsewhere'],
		];
	}

	public function testRefusesAFolderSharedWithTheUser(): void {
		// The folder does not exist yet, but the share it would be created in does,
		// and it is on Bob's storage.
		$this->nodes['/Shared'] = $this->file(9, '/Shared', 'bob');
		$this->config->expects(self::never())->method('setUserValue');

		$this->expectException(\InvalidArgumentException::class);
		$this->service->update('alice', null, '/Shared/To Be Deleted');
	}

	public function testRefusesAGroupFolder(): void {
		$this->nodes['/Team'] = $this->file(9, '/Team', 'alice', false);

		$this->expectException(\InvalidArgumentException::class);
		$this->service->update('alice', null, '/Team/Old photos');
	}

	public function testAcceptsANewFolderInTheUsersOwnFiles(): void {
		$this->nodes['/Archive'] = $this->createConfiguredMock(Folder::class, [
			'getOwner' => $this->file(0, '/x')->getOwner(),
			'getStorage' => $this->file(0, '/x')->getStorage(),
		]);
		$this->config->expects(self::once())->method('setUserValue');

		$this->service->update('alice', null, '/Archive/To Be Deleted');
	}

	public function testUnknownModeIsRefused(): void {
		$this->config->expects(self::never())->method('setUserValue');
		$this->expectException(\InvalidArgumentException::class);
		$this->service->update('alice', 'shred');
	}

	public function testAStoredValueThatNoLongerPassesFallsBack(): void {
		// Written by an older version, or by hand. A scan must not fail over it.
		$this->stored[ConfigService::KEY_TARGET_FOLDER] = '/a/../b';
		self::assertSame(ConfigService::DEFAULT_TARGET_FOLDER, $this->service->getTargetFolder('alice'));
	}

	public function testAnUnknownTimezoneFallsBackToUtc(): void {
		$this->stored['timezone'] = 'Mars/Olympus_Mons';
		self::assertSame('UTC', $this->service->getTimeZone('alice')->getName());
	}
}
