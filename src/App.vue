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
					:scanning="scanning"
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
				:scanning="scanning"
				@open="openMonth"
				@reopen="reopenMonth" />

			<SwipeDeck
				v-else-if="view === 'swipe'"
				:key="`${openedMonth}:${showJudged}`"
				:month="openedMonth"
				:showJudged="showJudged"
				:timeZone="config.timezone"
				@back="backToMonths"
				@changed="scheduleSummaryRefresh" />

			<ReviewList
				v-else-if="view === 'review'"
				:trashAvailable="trashAvailable"
				:mode="config.mode"
				:timeZone="config.timezone"
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

/** How long the counters wait for a run of swipes to settle before refreshing. */
const SUMMARY_DEBOUNCE = 500

/** Backoff while another scan (usually cron) holds the index: first and longest wait. */
const BUSY_WAIT_FIRST = 2000
const BUSY_WAIT_MAX = 30000

/**
 * @param {number} ms how long
 * @return {Promise<void>}
 */
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

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
			/** Whether the opened month includes the photos already judged. */
			showJudged: false,
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

	computed: {
		/**
		 * Whether a scan is under way.
		 *
		 * Mostly the app's own loop: the server sets and clears `scan.running` inside
		 * a single request, so the answers to this page's own scans always say false.
		 * The flag is only ever seen true while some other process — cron, occ,
		 * another tab — is scanning.
		 */
		scanning() {
			return this.autoScanning || Boolean(this.scan.running)
		},
	},

	created() {
		// Not reactive: bookkeeping for requests, nothing on screen reads them.
		this.summaryTimer = null
		this.summaryRequest = 0
		this.saveRequest = 0
	},

	beforeUnmount() {
		clearTimeout(this.summaryTimer)
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
			this.showJudged = false
			this.view = 'swipe'
		},

		/**
		 * Opens a month with every photo in it, including the ones already judged.
		 *
		 * This used to clear the month's verdicts first, pending deletes and all, on a
		 * single click. Going over a month again should never cost anything; clearing
		 * is still there inside the month, behind a confirmation.
		 *
		 * @param {string} month e.g. "2024-07"
		 */
		reopenMonth(month) {
			this.openedMonth = month
			this.showJudged = true
			this.view = 'swipe'
		},

		async backToMonths() {
			this.view = 'months'
			await this.refreshAll()
		},

		async refreshAll() {
			// Anything a debounced refresh would bring is about to arrive anyway, and
			// its older answer must not land on top of this one.
			clearTimeout(this.summaryTimer)
			this.summaryRequest++
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

		/**
		 * Refreshes the counters once a run of swipes has paused.
		 *
		 * Every verdict reports a change, and refreshing on each one sent a request
		 * per photo whose answers could arrive out of order and make the counters
		 * run backwards.
		 */
		scheduleSummaryRefresh() {
			clearTimeout(this.summaryTimer)
			this.summaryTimer = setTimeout(() => this.refreshSummary(), SUMMARY_DEBOUNCE)
		},

		async refreshSummary() {
			const request = ++this.summaryRequest
			try {
				const timeline = await api.months()
				// A newer refresh has been sent since; its answer is the one to keep.
				if (request !== this.summaryRequest) {
					return
				}
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
			// Kept until a request has actually run. While another scan holds the
			// index the server answers without doing anything, and a rebuild asked
			// for then must not quietly turn into an ordinary refresh.
			let fullPending = full
			let wait = 0
			try {
				let guard = 0
				do {
					const result = await api.scan(fullPending)
					this.scan = result.scan
					this.summary = result.summary

					if (this.scan.error) {
						// The server's text is an exception message and belongs in its
						// log, which is where it already is.
						showError(t('photosweep', 'The scan stopped because of an error'))
						break
					}

					if (this.scan.running) {
						// Someone else is scanning — cron, most likely. Asking again at
						// once only runs into the rate limit, so wait, a little longer
						// each time, and pick up when it is done.
						wait = Math.min(wait ? wait * 2 : BUSY_WAIT_FIRST, BUSY_WAIT_MAX)
						await sleep(wait)
					} else {
						fullPending = false
						wait = 0
						// Only when new months have turned up. The grid gets its full
						// numbers once the scan is over, and until then a months request
						// per chunk would double the load of a scan for little to see.
						if (result.summary.months !== this.months.length) {
							this.months = (await api.months()).months
						}
					}
					guard++
				} while (!this.scan.complete && guard < 500)

				const timeline = await api.months()
				this.summary = timeline.summary
				this.months = timeline.months
			} catch {
				showError(t('photosweep', 'The scan could not finish'))
			} finally {
				this.autoScanning = false
			}
		},

		async rebuild() {
			await this.runScan(true)
		},

		async saveConfig(patch) {
			// Saves can overlap, and each answer is the whole config as it stood at the
			// time. Only the newest request's answer is current.
			const request = ++this.saveRequest
			try {
				const config = await api.saveConfig(patch)
				if (request === this.saveRequest) {
					this.config = config
				}
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
