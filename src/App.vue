<!--
  - SPDX-FileCopyrightText: 2026 Nissaar
  - SPDX-License-Identifier: AGPL-3.0-or-later
  -->

<template>
	<NcContent appName="photosweep">
		<NcAppNavigation>
			<template #list>
				<NcAppNavigationItem
					:name="t('photosweep', 'Months')"
					:active="view === 'months'"
					@click="go('months')">
					<template #icon>
						<CalendarMonth :size="20" />
					</template>
					<template #counter>
						<!-- The number goes in `count`, not the slot: the component
						     renders Intl.NumberFormat().format(count) and ignores any
						     content, so slotting it showed a literal NaN. -->
						<NcCounterBubble v-if="summary.monthsToReview" :count="summary.monthsToReview" />
					</template>
				</NcAppNavigationItem>

				<NcAppNavigationItem
					:name="t('photosweep', 'Marked for deletion')"
					:active="view === 'review'"
					@click="go('review')">
					<template #icon>
						<DeleteClock :size="20" />
					</template>
					<template #counter>
						<NcCounterBubble
							v-if="summary.pendingDeletes"
							:count="summary.pendingDeletes"
							type="highlighted" />
					</template>
				</NcAppNavigationItem>

				<NcAppNavigationItem
					:name="t('photosweep', 'Settings')"
					:active="view === 'settings'"
					@click="go('settings')">
					<template #icon>
						<Cog :size="20" />
					</template>
				</NcAppNavigationItem>
			</template>

			<template #footer>
				<IndexStatus
					:scan="scan"
					:summary="summary"
					@scan="runScan"
					@rebuild="rebuild" />
			</template>
		</NcAppNavigation>

		<NcAppContent>
			<div v-if="loading" class="pc-centre">
				<NcLoadingIcon :size="44" />
			</div>

			<MonthGrid
				v-else-if="view === 'months'"
				:months="months"
				:summary="summary"
				:scanning="scan.running"
				@open="openMonth"
				@reset="resetMonth" />

			<SwipeDeck
				v-else-if="view === 'swipe'"
				:key="openedMonth"
				:month="openedMonth"
				@back="backToMonths"
				@changed="refreshSummary" />

			<ReviewList
				v-else-if="view === 'review'"
				:trashAvailable="trashAvailable"
				:mode="config.mode"
				@changed="refreshAll" />

			<SettingsPanel
				v-else-if="view === 'settings'"
				:config="config"
				:trashAvailable="trashAvailable"
				@save="saveConfig"
				@rebuild="rebuild" />
		</NcAppContent>
	</NcContent>
</template>

<script>
import { showError } from '@nextcloud/dialogs'
import { loadState } from '@nextcloud/initial-state'
import { translate as t } from '@nextcloud/l10n'
import NcAppContent from '@nextcloud/vue/components/NcAppContent'
import NcAppNavigation from '@nextcloud/vue/components/NcAppNavigation'
import NcAppNavigationItem from '@nextcloud/vue/components/NcAppNavigationItem'
import NcContent from '@nextcloud/vue/components/NcContent'
import NcCounterBubble from '@nextcloud/vue/components/NcCounterBubble'
import NcLoadingIcon from '@nextcloud/vue/components/NcLoadingIcon'
import CalendarMonth from 'vue-material-design-icons/CalendarMonth.vue'
import Cog from 'vue-material-design-icons/Cog.vue'
import DeleteClock from 'vue-material-design-icons/DeleteClock.vue'
import IndexStatus from './components/IndexStatus.vue'
import MonthGrid from './components/MonthGrid.vue'
import ReviewList from './components/ReviewList.vue'
import SettingsPanel from './components/SettingsPanel.vue'
import SwipeDeck from './components/SwipeDeck.vue'
import api from './api.js'

const EMPTY_SUMMARY = {
	indexed: 0,
	months: 0,
	monthsToReview: 0,
	photosLeft: 0,
	pendingDeletes: 0,
	kept: 0,
}

export default {
	name: 'App',

	components: {
		CalendarMonth,
		Cog,
		DeleteClock,
		IndexStatus,
		MonthGrid,
		NcAppContent,
		NcAppNavigation,
		NcAppNavigationItem,
		NcContent,
		NcCounterBubble,
		NcLoadingIcon,
		ReviewList,
		SettingsPanel,
		SwipeDeck,
	},

	data() {
		return {
			view: 'months',
			openedMonth: null,
			loading: true,
			months: [],
			summary: { ...EMPTY_SUMMARY },
			scan: { complete: false, running: false, found: 0, error: null },
			// Sent down with the page, so the first render already knows which delete
			// mode is set rather than flickering through a default.
			config: loadState('photosweep', 'config', {
				mode: 'trash',
				targetFolder: '/To Be Deleted',
				sourceFolder: '/',
				skipDecided: true,
			}),

			trashAvailable: loadState('photosweep', 'trashAvailable', true),
			autoScanning: false,
		}
	},

	async mounted() {
		await this.refreshAll()
		this.loading = false

		// Nothing indexed yet means a first run. Start the scan without being asked:
		// an empty month grid with a button on it is a worse first impression than
		// months appearing one batch at a time.
		if (!this.scan.complete && this.summary.indexed === 0) {
			this.runScan()
		}
	},

	methods: {
		t,

		go(view) {
			this.view = view
		},

		openMonth(month) {
			this.openedMonth = month
			this.view = 'swipe'
		},

		async backToMonths() {
			this.view = 'months'
			await this.refreshAll()
		},

		async refreshAll() {
			try {
				const [status, timeline] = await Promise.all([api.status(), api.months()])
				this.scan = status.scan
				this.config = status.config
				this.trashAvailable = status.trashAvailable
				this.summary = timeline.summary
				this.months = timeline.months
			} catch {
				showError(t('photosweep', 'Could not load your library'))
			}
		},

		async refreshSummary() {
			try {
				const timeline = await api.months()
				this.summary = timeline.summary
				this.months = timeline.months
			} catch {
				// The deck is still usable without refreshed totals, so this stays quiet.
			}
		},

		/**
		 * Runs the scan to completion, one bounded chunk at a time.
		 *
		 * Each call indexes a few thousand files and returns, so the grid fills in as
		 * it goes and no single request has to survive a whole library.
		 *
		 * @param {boolean} full discard the index first
		 */
		async runScan(full = false) {
			if (this.autoScanning) {
				return
			}
			this.autoScanning = true
			try {
				let guard = 0
				do {
					const result = await api.scan(full && guard === 0)
					this.scan = result.scan
					this.summary = result.summary
					this.months = (await api.months()).months

					if (this.scan.error) {
						showError(this.scan.error)
						break
					}
					guard++
				} while (!this.scan.complete && guard < 500)
			} catch {
				showError(t('photosweep', 'The scan could not finish'))
			} finally {
				this.autoScanning = false
			}
		},

		async rebuild() {
			await this.runScan(true)
		},

		async resetMonth(month) {
			try {
				await api.resetMonth(month)
				await this.refreshAll()
			} catch {
				showError(t('photosweep', 'Could not reopen that month'))
			}
		},

		async saveConfig(patch) {
			try {
				this.config = await api.saveConfig(patch)
			} catch (error) {
				// The server rejects a few settings by hand — an empty target folder,
				// for one — and its reason is more use than a generic failure.
				showError(error?.response?.data?.ocs?.meta?.message
					?? t('photosweep', 'Could not save that setting'))
			}
		},
	},
}
</script>

<style scoped>
.pc-centre {
	display: flex;
	align-items: center;
	justify-content: center;
	height: 100%;
	min-height: 320px;
}
</style>
