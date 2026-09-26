/**
 * SPDX-FileCopyrightText: 2026 Nissaar
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

import { createApp } from 'vue'
import App from './App.vue'

// No global t/n: every component imports the ones it uses, so there is a single
// place to look for where a template's translation function comes from.
createApp(App).mount('#photosweep')
