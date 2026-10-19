/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

/**
 * Photos sent to the server per request.
 *
 * Deleting is slow work, around a second per photo on a busy server. One request
 * for two thousand photos outlives every proxy's timeout, and the browser gives up
 * while the server carries on, so the next tap finds a run still in progress. Small
 * requests each finish well inside any timeout, however many photos there are.
 */
export const APPLY_BATCH_SIZE = 25

/** Tries per batch before giving up on it. */
const ATTEMPTS = 5

const RETRY_DELAY_MS = 3000

/**
 * Whether a failed batch is worth sending again.
 *
 * A batch that timed out on the client is usually still running on the server, so
 * its retry is told `apply_running` until it finishes. Sending it again is safe
 * either way: the server only acts on photos that are still pending.
 *
 * @param {Error} error what the request threw
 * @param {string|null} code the stable code the server answered with, if any
 * @return {boolean}
 */
function transient(error, code) {
	if (code === 'apply_running') {
		return true
	}
	if (code !== null) {
		return false
	}
	const status = error?.response?.status
	return status === undefined || status >= 500
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * Carries out the deletes a slice at a time and adds the answers up.
 *
 * Throws if the very first batch fails, so the caller can explain a refusal with
 * nothing done. After that, a failure ends the run quietly with `interrupted` set:
 * the photos already dealt with stay dealt with, and the rest are still pending for
 * another go.
 *
 * @param {object} options
 * @param {number[]} options.fileIds everything the user confirmed
 * @param {(ids: number[]) => Promise<object>} options.send carries out one batch
 * @param {(error: Error) => string|null} options.errorCode the error's stable code
 * @param {(done: number, total: number) => void} [options.onProgress] told after each batch
 * @param {number} [options.size] photos per batch
 * @param {number} [options.delay] wait between retries, in milliseconds
 * @return {Promise<{mode: string|null, succeeded: number, failed: number, failures: object[], targetFolder: string|null, error: string|null, interrupted: boolean}>}
 */
export async function applyInBatches({
	fileIds,
	send,
	errorCode,
	onProgress = () => {},
	size = APPLY_BATCH_SIZE,
	delay = RETRY_DELAY_MS,
}) {
	const total = {
		mode: null,
		succeeded: 0,
		failed: 0,
		failures: [],
		targetFolder: null,
		error: null,
		interrupted: false,
	}

	for (let start = 0; start < fileIds.length; start += size) {
		const ids = fileIds.slice(start, start + size)

		let result = null
		for (let attempt = 1; result === null; attempt++) {
			try {
				result = await send(ids)
			} catch (error) {
				if (attempt < ATTEMPTS && transient(error, errorCode(error))) {
					await sleep(delay)
					continue
				}
				if (start === 0) {
					throw error
				}
				total.interrupted = true
				return total
			}
		}

		total.mode = result.mode ?? total.mode
		total.targetFolder = result.targetFolder ?? total.targetFolder
		total.succeeded += result.succeeded ?? 0
		total.failed += result.failed ?? 0
		total.failures.push(...(result.failures ?? []))

		if (result.error) {
			// The server could not even start, and it would say the same to the next
			// batch. Stop with what it said.
			total.error = result.error
			return total
		}
		onProgress(Math.min(start + size, fileIds.length), fileIds.length)
	}

	return total
}
