<?php

declare(strict_types=1);

/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

namespace OCA\PhotoSweep\Http;

use OCP\AppFramework\Http;
use OCP\AppFramework\Http\Response;

/**
 * An OCS failure that carries both a message for people and a code for programs.
 *
 * The public API has two ways to fail from an OCS controller, and each loses one of
 * the two. An OCSException sets `meta.message` but always sends empty data; a
 * DataResponse with an error status sends data but always leaves `meta.message`
 * empty. A client that has to tell "the trash is off" from "another run is going"
 * needs a stable code, and one that just shows the message needs the text, so this
 * renders the same envelope core does with both filled in.
 *
 * @template-extends Response<Http::STATUS_*, array<string, mixed>>
 */
class OcsFailureResponse extends Response {

	/**
	 * @param Http::STATUS_* $status
	 * @param string $format "json" or "xml", as the request asked
	 * @param array<string, scalar> $data
	 */
	public function __construct(
		int $status,
		private string $message,
		private array $data,
		private string $format = 'json',
	) {
		parent::__construct($status);
		$this->addHeader(
			'Content-Type',
			$this->format === 'json' ? 'application/json; charset=utf-8' : 'application/xml; charset=utf-8',
		);
	}

	public function render(): string {
		$meta = [
			'status' => 'failure',
			'statuscode' => $this->getStatus(),
			'message' => $this->message,
		];

		if ($this->format === 'json') {
			return (string)json_encode(['ocs' => ['meta' => $meta, 'data' => $this->data]], JSON_HEX_TAG);
		}

		$writer = new \XMLWriter();
		$writer->openMemory();
		$writer->setIndent(true);
		$writer->startDocument();
		$writer->startElement('ocs');
		foreach (['meta' => $meta, 'data' => $this->data] as $section => $values) {
			$writer->startElement($section);
			foreach ($values as $key => $value) {
				$writer->writeElement($key, is_bool($value) ? ($value ? '1' : '0') : (string)$value);
			}
			$writer->endElement();
		}
		$writer->endElement();
		$writer->endDocument();
		return $writer->outputMemory(true);
	}
}
