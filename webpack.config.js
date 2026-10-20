/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import path from 'node:path'
import { fileURLToPath } from 'node:url'
import webpackConfig from '@nextcloud/webpack-vue-config'

const here = path.dirname(fileURLToPath(import.meta.url))

webpackConfig.entry = {
	main: path.join(here, 'src', 'main.js'),
}

// The changelog is read into the bundle as text, so the "What's new" dialog shows
// what the release page shows and the two cannot drift apart.
webpackConfig.module.rules.push({
	test: /CHANGELOG\.md$/,
	type: 'asset/source',
})

export default webpackConfig
