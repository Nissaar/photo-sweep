/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import { formatFileSize } from '@nextcloud/files'
import { getCanonicalLocale, translate as t } from '@nextcloud/l10n'

/**
 * "2024-07" as a month a person would read.
 *
 * @param {string} yearMonth a month key like "2024-07"
 * @return {string}
 */
export function monthLabel(yearMonth) {
	const [year, month] = String(yearMonth).split('-').map(Number)
	if (!year || !month) {
		return yearMonth
	}
	return new Date(Date.UTC(year, month - 1, 1)).toLocaleDateString(getCanonicalLocale(), {
		month: 'long',
		year: 'numeric',
		timeZone: 'UTC',
	})
}

/**
 * A day, in the timezone the server buckets months in.
 *
 * The browser's own zone is the wrong one here: a photo taken just before midnight
 * would sit in July on the grid and read as 1 August on the card.
 *
 * @param {number} seconds epoch seconds
 * @param {string|null} timeZone an IANA zone name, from the user's config
 * @return {string}
 */
export function dateLabel(seconds, timeZone = null) {
	if (!seconds) {
		return ''
	}
	const date = new Date(seconds * 1000)
	const format = {
		day: 'numeric',
		month: 'short',
		year: 'numeric',
	}
	try {
		return date.toLocaleDateString(getCanonicalLocale(), timeZone ? { ...format, timeZone } : format)
	} catch {
		// A zone name this browser does not know throws rather than falling back.
		return date.toLocaleDateString(getCanonicalLocale(), format)
	}
}

/**
 * @param {number} bytes a size in bytes
 * @return {string}
 */
export function sizeLabel(bytes) {
	if (!bytes) {
		return ''
	}
	return formatFileSize(bytes)
}

/**
 * Explains where a photo's date came from, for the cases where it looks wrong.
 *
 * @param {string} source one of the DateSource values
 * @return {string}
 */
export function dateSourceLabel(source) {
	switch (source) {
		case 'exif':
			return t('photosweep', 'Date taken, from the photo itself')
		case 'filename':
			return t('photosweep', 'Date read from the file name')
		case 'upload':
			return t('photosweep', 'Date this file reached the server')
		default:
			return t('photosweep', 'Date the file was last changed — no capture date was available')
	}
}
