/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import { getCurrentUser } from '@nextcloud/auth'
import axios from '@nextcloud/axios'
import { generateOcsUrl, generateRemoteUrl, generateUrl } from '@nextcloud/router'

const base = generateOcsUrl('apps/photosweep/api/v1')

const options = {
	headers: { 'OCS-APIRequest': 'true' },
}

/**
 * Unwraps an OCS envelope.
 *
 * @param {object} response an axios response
 * @return {object} the payload the endpoint actually returned
 */
const data = (response) => response.data.ocs.data

/**
 * The stable code an OCS failure carries in its payload, such as `apply_running`.
 *
 * @param {Error} error what axios threw
 * @return {string|null} the code, or null for anything that is not one
 */
export function errorCode(error) {
	const code = error?.response?.data?.ocs?.data?.error
	return typeof code === 'string' ? code : null
}

export default {
	/** Index state, headline numbers and settings, in one call. */
	async status() {
		return data(await axios.get(`${base}/index`, options))
	},

	/**
	 * Advances the index by one chunk.
	 *
	 * @param {boolean} full discard what is indexed and read everything again
	 */
	async scan(full = false) {
		return data(await axios.post(`${base}/index`, { full }, options))
	},

	async months() {
		return data(await axios.get(`${base}/months`, options))
	},

	/**
	 * @param {string} month e.g. "2024-07"
	 * @param {boolean|null} skipDecided override the stored setting for this call
	 */
	async month(month, skipDecided = null) {
		const params = skipDecided === null ? {} : { skipDecided }
		return data(await axios.get(`${base}/months/${encodeURIComponent(month)}`, { ...options, params }))
	},

	async resetMonth(month) {
		return data(await axios.delete(`${base}/months/${encodeURIComponent(month)}`, options))
	},

	async record(fileId, verdict) {
		return data(await axios.post(`${base}/decisions`, { fileId, verdict }, options))
	},

	/**
	 * @param {Array<{fileId: number, verdict: string}>} verdicts the batch to record
	 */
	async recordMany(verdicts) {
		return data(await axios.post(`${base}/decisions`, { verdicts }, options))
	},

	async undo(fileId) {
		return data(await axios.delete(`${base}/decisions/${fileId}`, options))
	},

	async pending() {
		return data(await axios.get(`${base}/decisions/pending`, options))
	},

	async applied() {
		return data(await axios.get(`${base}/decisions/applied`, options))
	},

	/**
	 * The only call that changes files.
	 *
	 * The ids are the list the user was shown, so a verdict that arrived from another
	 * device after the screen loaded is not carried out along with it.
	 *
	 * @param {number[]} fileIds the pending deletes the user confirmed
	 * @param {boolean} permanent the user was told the trash is off and agreed anyway
	 */
	async apply(fileIds, permanent = false) {
		const body = permanent ? { fileIds, permanent: true } : { fileIds }
		return data(await axios.post(`${base}/apply`, body, options))
	},

	async restore(fileIds) {
		return data(await axios.post(`${base}/restore`, { fileIds }, options))
	},

	async saveConfig(patch) {
		return data(await axios.put(`${base}/config`, patch, options))
	},
}

/**
 * A preview of one file, at whatever size the layout needs.
 *
 * `a=1` keeps the aspect ratio: without it every portrait photo is cropped to a
 * square, which is exactly the kind of framing that makes a keep-or-delete judgement
 * harder than it needs to be.
 *
 * @param {number} fileId the file
 * @param {number} size longest edge, in pixels
 * @return {string} a URL on this origin
 */
export function previewUrl(fileId, size = 1024) {
	return generateUrl('/core/preview?fileId={fileId}&x={size}&y={size}&a=1', { fileId, size })
}

/**
 * The file itself, for playing a video in place.
 *
 * Served straight from the user's own WebDAV endpoint, so playback carries the same
 * session as the rest of the page and no separate token is involved.
 *
 * @param {string} path path relative to the user's files root
 * @return {string} a URL on this origin
 */
export function fileUrl(path) {
	const user = getCurrentUser()
	if (!user) {
		return ''
	}
	const clean = String(path).replace(/^\/+/, '').split('/').map(encodeURIComponent).join('/')
	return generateRemoteUrl(`dav/files/${encodeURIComponent(user.uid)}/${clean}`)
}
