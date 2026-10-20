/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import changelog from '../CHANGELOG.md'

/**
 * The newest entry of the changelog, as a version and a list of notes.
 *
 * @param {string} text the contents of CHANGELOG.md
 * @return {{version: string, notes: string[]}|null} null if there is no entry
 */
export function latestEntry(text) {
	const match = /^## \[?([^\]\s]+)\]?.*$/m.exec(text)
	if (!match) {
		return null
	}

	const rest = text.slice(match.index + match[0].length)
	const next = rest.search(/^## /m)
	const body = next === -1 ? rest : rest.slice(0, next)

	const notes = []
	for (const line of body.split('\n')) {
		if (/^\s*-\s+/.test(line)) {
			notes.push(line.replace(/^\s*-\s+/, '').trim())
		} else if (notes.length > 0 && line.trim() !== '') {
			notes[notes.length - 1] += ' ' + line.trim()
		}
	}

	return notes.length > 0 ? { version: match[1], notes } : null
}

/** What changed in the version this bundle belongs to. */
export default latestEntry(changelog)

const KEY = 'photosweep.whatsNewSeen'

/**
 * The version whose notes were last shown in this browser.
 *
 * Storage can be blocked or throw in a private window; nothing here may break the
 * page, so a failure reads as "never shown".
 *
 * @return {string|null}
 */
export function lastSeen() {
	try {
		return window.localStorage.getItem(KEY)
	} catch {
		return null
	}
}

/**
 * @param {string} version the version whose notes have now been shown
 */
export function markSeen(version) {
	try {
		window.localStorage.setItem(KEY, version)
	} catch {
		// Shown again next time, which is harmless.
	}
}
