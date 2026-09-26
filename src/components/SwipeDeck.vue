<!--
  - SPDX-FileCopyrightText: 2026 Nissaar
  - SPDX-License-Identifier: AGPL-3.0-or-later
  -->

<template>
	<div class="pc-deck">
		<div class="pc-deck__bar">
			<NcButton variant="tertiary" @click="$emit('back')">
				<template #icon>
					<ArrowLeft :size="20" />
				</template>
				{{ t('photosweep', 'Months') }}
			</NcButton>

			<div class="pc-deck__title">
				<strong>{{ label(month) }}</strong>
				<span v-if="items.length" class="pc-deck__progress">
					{{ t('photosweep', '{done} of {total}', { done: Math.min(index + 1, items.length), total: items.length }) }}
				</span>
			</div>

			<NcButton
				variant="tertiary"
				:disabled="!history.length"
				@click="undo">
				<template #icon>
					<UndoVariant :size="20" />
				</template>
				{{ t('photosweep', 'Undo') }}
			</NcButton>
		</div>

		<NcProgressBar :value="progress" size="medium" :aria-label="t('photosweep', 'Progress through this month')" />

		<!-- Swiping is silent otherwise: the card changes, and a screen reader has no
		     way to know whether that was a keep, a delete or an undo. -->
		<p class="hidden-visually" aria-live="polite">
			{{ announcement }}
		</p>

		<div v-if="loading" class="pc-deck__centre">
			<NcLoadingIcon :size="44" />
		</div>

		<NcEmptyContent
			v-else-if="loadFailed"
			:name="t('photosweep', 'Could not open this month')"
			:description="t('photosweep', 'Your verdicts are safe. Check your connection and try again.')">
			<template #icon>
				<AlertCircle />
			</template>
			<template #action>
				<NcButton @click="load(showingJudged ? false : null)">
					{{ t('photosweep', 'Try again') }}
				</NcButton>
			</template>
		</NcEmptyContent>

		<NcEmptyContent
			v-else-if="!items.length"
			:name="showingJudged ? t('photosweep', 'No photos in this month') : t('photosweep', 'Nothing left in this month')"
			:description="showingJudged ? '' : t('photosweep', 'Every photo here already has a verdict.')">
			<template #icon>
				<CheckAll />
			</template>
			<template v-if="!showingJudged" #action>
				<div class="pc-deck__choices">
					<NcButton @click="reviewAgain">
						{{ t('photosweep', 'Review this month again') }}
					</NcButton>
					<NcButton variant="tertiary" @click="askReset">
						{{ t('photosweep', 'Clear this month’s verdicts…') }}
					</NcButton>
				</div>
			</template>
		</NcEmptyContent>

		<NcEmptyContent
			v-else-if="finished"
			:name="t('photosweep', 'Month finished')"
			:description="summaryText">
			<template #icon>
				<CheckAll />
			</template>
			<template #action>
				<div class="pc-deck__choices">
					<NcButton variant="primary" @click="$emit('back')">
						{{ t('photosweep', 'Back to months') }}
					</NcButton>
					<NcButton variant="tertiary" @click="askReset">
						{{ t('photosweep', 'Clear this month’s verdicts…') }}
					</NcButton>
				</div>
			</template>
		</NcEmptyContent>

		<div v-else class="pc-deck__stage">
			<!-- The next photo sits behind the current one so a decision reveals it
			     instantly rather than flashing an empty frame while it loads. It is
			     decoration until it moves forward, so it is hidden from assistive tech. -->
			<div v-if="next" class="pc-card pc-card--behind" aria-hidden="true">
				<img :src="preview(next.fileId, DECK_SIZE)" alt="" loading="eager">
			</div>

			<div
				class="pc-card"
				:class="{ 'pc-card--dragging': dragging }"
				:style="cardStyle"
				@pointerdown="onPointerDown"
				@pointermove="onPointerMove"
				@pointerup="onPointerUp"
				@pointercancel="onPointerCancel">
				<video
					v-if="current.isVideo && playing"
					class="pc-card__media"
					:src="video(current.path)"
					:poster="preview(current.fileId, DECK_SIZE)"
					controls
					autoplay
					playsinline />
				<img
					v-else
					class="pc-card__media"
					:src="preview(current.fileId, DECK_SIZE)"
					:alt="current.name"
					fetchpriority="high"
					decoding="async"
					draggable="false">

				<button v-if="current.isVideo && !playing" class="pc-card__play" @click="playing = true">
					<Play :size="48" />
					<span class="hidden-visually">{{ t('photosweep', 'Play video') }}</span>
				</button>

				<span v-if="verdictHint" class="pc-card__stamp" :class="`pc-card__stamp--${verdictHint}`">
					{{ verdictHint === 'delete' ? t('photosweep', 'Delete') : t('photosweep', 'Keep') }}
				</span>

				<div class="pc-card__meta">
					<span class="pc-card__name" :title="current.path">{{ current.name }}</span>
					<span class="pc-card__detail" :title="sourceHint">
						{{ date(current.takenAt, timeZone) }} · {{ size(current.size) }}
					</span>
				</div>
			</div>
		</div>

		<div v-if="!loading && items.length && !finished" class="pc-deck__actions">
			<NcButton
				class="pc-deck__delete"
				variant="error"
				wide
				@click="decide('delete')">
				<template #icon>
					<Delete :size="20" />
				</template>
				{{ t('photosweep', 'Delete') }}
			</NcButton>
			<NcButton variant="success" wide @click="decide('keep')">
				<template #icon>
					<Check :size="20" />
				</template>
				{{ t('photosweep', 'Keep') }}
			</NcButton>
		</div>

		<p v-if="!loading && items.length && !finished" class="pc-deck__hint">
			{{ shortcutsOff
				? t('photosweep', 'Drag the photo or use the buttons. Nothing is deleted until you confirm it on the Marked for deletion screen.')
				: t('photosweep', 'Drag the photo, use the buttons, or press the left and right arrow keys. Nothing is deleted until you confirm it on the Marked for deletion screen.') }}
		</p>

		<NcDialog
			v-if="resetCount !== null"
			:name="t('photosweep', 'Clear this month’s verdicts?')"
			:message="resetMessage"
			@closing="resetCount = null">
			<template #actions>
				<NcButton variant="tertiary" @click="resetCount = null">
					{{ t('photosweep', 'Cancel') }}
				</NcButton>
				<NcButton variant="error" @click="reset">
					{{ t('photosweep', 'Clear verdicts') }}
				</NcButton>
			</template>
		</NcDialog>
	</div>
</template>

<script>
import { showError, showInfo } from '@nextcloud/dialogs'
import { translatePlural as n, translate as t } from '@nextcloud/l10n'
import { useHotKey } from '@nextcloud/vue/composables/useHotKey'
import NcButton from '@nextcloud/vue/components/NcButton'
import NcDialog from '@nextcloud/vue/components/NcDialog'
import NcEmptyContent from '@nextcloud/vue/components/NcEmptyContent'
import NcLoadingIcon from '@nextcloud/vue/components/NcLoadingIcon'
import NcProgressBar from '@nextcloud/vue/components/NcProgressBar'
import AlertCircle from 'vue-material-design-icons/AlertCircle.vue'
import ArrowLeft from 'vue-material-design-icons/ArrowLeft.vue'
import Check from 'vue-material-design-icons/Check.vue'
import CheckAll from 'vue-material-design-icons/CheckAll.vue'
import Delete from 'vue-material-design-icons/Delete.vue'
import Play from 'vue-material-design-icons/Play.vue'
import UndoVariant from 'vue-material-design-icons/UndoVariant.vue'
import api, { fileUrl, previewUrl } from '../api.js'
import { dateLabel, dateSourceLabel, monthLabel, sizeLabel } from '../format.js'

/** How far the card must travel before the drag counts as a verdict. */
const COMMIT_DISTANCE = 120

/**
 * Every preview in the deck is requested at this one size, and that is the whole
 * point: the browser caches by URL, so asking for the next photo at one size and
 * then displaying it at another throws the prefetch away and pays a fresh round
 * trip on every swipe.
 *
 * 1024 is also deliberate rather than round. Nextcloud snaps a requested preview up
 * to the next power of four (64, 256, 1024, 4096) and the previewgenerator app
 * pre-renders that same ladder, so 1024 is a size servers tend to already have. Ask
 * for 1200 and you silently get the 4096 bucket — several megabytes, and a full
 * decode of the original if nothing warmed it.
 */
const DECK_SIZE = 1024

/** Photos to pull into the browser cache ahead of the one on screen. */
const PREFETCH_AHEAD = 4

/**
 * For translations with a file name in them. Vue escapes the result when it renders
 * it, so escaping here as well would show a name like "Tom & Jerry.jpg" as "&amp;".
 */
const PLAIN = { escape: false, sanitize: false }

/**
 * Whether a key press is one of the deck's shortcuts and nothing else wants it.
 *
 * useHotKey already skips text fields, modifier combinations, key repeat and open
 * dialogs. It does not know about the video: with its controls focused, the arrow
 * keys seek, and a seek must not also judge the photo.
 *
 * @param {KeyboardEvent} event the key press
 * @return {boolean}
 */
function isDeckKey(event) {
	if (event.defaultPrevented) {
		return false
	}
	if (event.target instanceof Element && event.target.closest('video')) {
		return false
	}
	// Some browsers fire keydown without a key, for autofill among others.
	const key = event.key ?? ''
	return key === 'ArrowLeft' || key === 'ArrowRight' || key.toLowerCase() === 'z'
}

export default {
	name: 'SwipeDeck',

	components: {
		AlertCircle,
		ArrowLeft,
		Check,
		CheckAll,
		Delete,
		NcButton,
		NcDialog,
		NcEmptyContent,
		NcLoadingIcon,
		NcProgressBar,
		Play,
		UndoVariant,
	},

	props: {
		month: {
			type: String,
			required: true,
		},

		/** Open with the photos already judged in the deck too, to go over them again. */
		showJudged: {
			type: Boolean,
			default: false,
		},

		timeZone: {
			type: String,
			default: null,
		},
	},

	emits: ['back', 'changed'],

	data() {
		return {
			items: [],
			index: 0,
			loading: true,
			loadFailed: false,
			/** Whether the deck on screen includes photos that already had a verdict. */
			showingJudged: this.showJudged,
			playing: false,
			kept: 0,
			deleted: 0,
			/**
			 * Verdicts given this session, newest last — this is what undo walks back.
			 * Always the same photos, in the same order, as items[0..index).
			 */
			history: [],
			dragging: false,
			dragX: 0,
			pointerId: null,
			startX: 0,
			/** Verdicts a reset would clear, while its confirmation is open; else null. */
			resetCount: null,
			announcement: '',
			shortcutsOff: Boolean(window.OCP?.Accessibility?.disableKeyboardShortcuts?.()),
			DECK_SIZE,
		}
	},

	computed: {
		current() {
			return this.items[this.index] ?? null
		},

		next() {
			return this.items[this.index + 1] ?? null
		},

		finished() {
			return !this.loading && this.items.length > 0 && this.index >= this.items.length
		},

		progress() {
			if (!this.items.length) {
				return 0
			}
			return Math.round((this.index / this.items.length) * 100)
		},

		cardStyle() {
			if (!this.dragX) {
				return {}
			}
			return {
				transform: `translateX(${this.dragX}px) rotate(${this.dragX / 28}deg)`,
			}
		},

		verdictHint() {
			if (this.dragX <= -60) {
				return 'delete'
			}
			if (this.dragX >= 60) {
				return 'keep'
			}
			return null
		},

		sourceHint() {
			return this.current ? dateSourceLabel(this.current.dateSource) : ''
		},

		summaryText() {
			return t('photosweep', '{kept}, {deleted}.', {
				kept: n('photosweep', 'Kept %n', 'Kept %n', this.kept),
				deleted: n('photosweep', 'marked %n for deletion', 'marked %n for deletion', this.deleted),
			})
		},

		resetMessage() {
			return n(
				'photosweep',
				'This removes the verdict on %n photo in {month}, including a mark for deletion if it has one. It comes back up for review. Nothing on disk changes.',
				'This removes the verdicts on %n photos in {month}, including any marks for deletion. They come back up for review. Nothing on disk changes.',
				this.resetCount ?? 0,
				{ month: monthLabel(this.month) },
			)
		},
	},

	watch: {
		index: 'prefetchAhead',
		items: 'prefetchAhead',
	},

	created() {
		// Deliberately not in data(): these are kept only so the browser does not
		// garbage-collect an in-flight decode, and making them reactive would have
		// Vue walk an Image object on every swipe for nothing.
		this.prefetched = new Map()

		// The last request still out for each file, so a verdict, its undo and a
		// fresh verdict reach the server in the order they were given.
		this.inFlight = new Map()

		// Registered here, before the month has loaded, so leaving mid-load cannot
		// strand a listener on a deck that no longer exists. onKey ignores keys
		// until there is something to judge.
		this.stopHotKeys = useHotKey((event) => isDeckKey(event) && this.acceptsKey(event), this.onKey, { prevent: true })
	},

	async mounted() {
		await this.load(this.showJudged ? false : null)
	},

	beforeUnmount() {
		this.stopHotKeys()
	},

	methods: {
		t,
		label: monthLabel,
		date: dateLabel,
		size: sizeLabel,
		preview: previewUrl,
		video: fileUrl,

		/**
		 * Warms the browser cache for the photos just over the horizon.
		 *
		 * The card behind the current one covers exactly one photo ahead, which is
		 * enough for a considered swipe and not enough for a fast one. Decoding a few
		 * more in advance is what makes a run of quick verdicts stay instant.
		 */
		prefetchAhead() {
			for (let i = this.index + 1; i <= this.index + PREFETCH_AHEAD; i++) {
				const item = this.items[i]
				if (!item || this.prefetched.has(item.fileId)) {
					continue
				}
				const img = new Image()
				img.decoding = 'async'
				img.src = this.preview(item.fileId, DECK_SIZE)
				// decode() resolves once the pixels are ready, so the swap is a paint
				// rather than a decode. It rejects if the image is replaced first,
				// which is not worth reporting.
				img.decode?.().catch(() => {})
				this.prefetched.set(item.fileId, img)
			}

			// The deck only moves forwards, so anything already judged is dead weight.
			for (const fileId of this.prefetched.keys()) {
				const stillAhead = this.items
					.slice(this.index, this.index + PREFETCH_AHEAD + 1)
					.some((item) => item.fileId === fileId)
				if (!stillAhead) {
					this.prefetched.delete(fileId)
				}
			}
		},

		/**
		 * @param {boolean|null} skipDecided false to include photos already judged,
		 *                                   null for the user's setting
		 */
		async load(skipDecided = null) {
			this.loading = true
			try {
				const result = await api.month(this.month, skipDecided)
				this.items = result.items
				this.index = 0
				this.history = []
				this.kept = 0
				this.deleted = 0
				this.showingJudged = skipDecided === false
				this.loadFailed = false
			} catch {
				// Its own state with a retry, rather than a toast over an empty deck:
				// an empty deck says "nothing left in this month", which is not true.
				this.loadFailed = true
			} finally {
				this.loading = false
			}
		},

		/**
		 * Goes over the month again with every photo in it, verdicts and all.
		 *
		 * Nothing is cleared. Judging a photo again replaces its verdict, and leaving
		 * one alone keeps the verdict it had.
		 */
		async reviewAgain() {
			await this.load(false)
		},

		/** Counts what a reset would clear, and asks before clearing it. */
		async askReset() {
			let count
			try {
				// Every indexed photo in the month, less the ones without a verdict.
				const [all, open] = await Promise.all([
					api.month(this.month, false),
					api.month(this.month, true),
				])
				count = all.items.length - open.items.length
			} catch {
				showError(t('photosweep', 'Could not check this month'))
				return
			}
			if (count <= 0) {
				showInfo(t('photosweep', 'No photo in this month has a verdict to clear.'))
				return
			}
			this.resetCount = count
		},

		async reset() {
			this.resetCount = null
			try {
				await api.resetMonth(this.month)
			} catch {
				showError(t('photosweep', 'Could not clear this month’s verdicts'))
				return
			}
			this.$emit('changed')
			await this.load(this.showingJudged ? false : null)
		},

		/**
		 * Whether the deck has a use for this key right now. A key it would ignore
		 * keeps its default, so the arrows still scroll while the month loads.
		 *
		 * @param {KeyboardEvent} event one of the deck's keys
		 * @return {boolean}
		 */
		acceptsKey(event) {
			if (this.loading || this.resetCount !== null) {
				return false
			}
			if (event.key.toLowerCase() === 'z') {
				// Also on the finished screen, where there is no current photo but the
				// last verdict can still be taken back.
				return this.history.length > 0
			}
			return this.current !== null
		},

		onKey(event) {
			if (event.key.toLowerCase() === 'z') {
				this.undo()
			} else {
				this.decide(event.key === 'ArrowLeft' ? 'delete' : 'keep')
			}
		},

		/**
		 * Runs one server call for a file once whatever is still out for it is done.
		 *
		 * Without this an undo could overtake the verdict it takes back: the server
		 * would drop a verdict that did not exist yet and then store it, and the photo
		 * would stay marked while the deck showed it undone.
		 *
		 * @param {number} fileId the file
		 * @param {() => Promise<void>} task makes the request
		 * @return {Promise} the task's own outcome
		 */
		enqueue(fileId, task) {
			const previous = this.inFlight.get(fileId) ?? Promise.resolve()
			const run = previous.then(task)
			const settled = run.then(() => {}, () => {})
			this.inFlight.set(fileId, settled)
			settled.then(() => {
				if (this.inFlight.get(fileId) === settled) {
					this.inFlight.delete(fileId)
				}
			})
			return run
		},

		/**
		 * Records a verdict and moves on.
		 *
		 * The deck advances first and the request follows, because waiting on the
		 * network between every photo is what makes going through a thousand of them
		 * unbearable. A failure is surfaced and that photo is put back.
		 *
		 * @param {string} verdict "keep" or "delete"
		 */
		async decide(verdict) {
			const item = this.current
			if (!item) {
				return
			}

			this.index += 1
			this.playing = false
			this.dragX = 0
			const entry = { item, verdict, saved: false }
			this.history.push(entry)
			this.count(verdict, 1)
			this.announce(verdict === 'keep'
				? t('photosweep', 'Kept {name}', { name: item.name }, undefined, PLAIN)
				: t('photosweep', 'Marked {name} for deletion', { name: item.name }, undefined, PLAIN))

			try {
				await this.enqueue(item.fileId, async () => {
					await api.record(item.fileId, verdict)
					// Set inside the queued task, so an undo queued behind it is
					// guaranteed to see it.
					entry.saved = true
				})
				this.$emit('changed')
			} catch {
				this.dropFailed(entry)
			}
		},

		/**
		 * Takes back exactly the verdict whose save failed, wherever it is by now.
		 *
		 * Not simply the last one: by the time a request fails the deck has usually
		 * moved on, and rolling back the newest verdict would undo a photo that saved
		 * fine while leaving the failed one looking marked.
		 *
		 * @param {object} entry the history entry that could not be saved
		 */
		dropFailed(entry) {
			const at = this.history.indexOf(entry)
			if (at === -1) {
				// Already undone, or the deck was reloaded. Either way nothing on
				// screen claims this verdict any more, which is the truth.
				return
			}
			showError(t('photosweep', 'Could not save that decision'))
			this.history.splice(at, 1)
			this.count(entry.verdict, -1)

			// Bring the photo back as the current card. The judged part of the deck
			// stays exactly the photos in history, which is what undo relies on.
			const from = this.items.indexOf(entry.item)
			if (from !== -1 && from < this.index) {
				this.items.splice(from, 1)
				this.items.splice(this.index - 1, 0, entry.item)
				this.index -= 1
			}
			this.playing = false
		},

		async undo() {
			const entry = this.history[this.history.length - 1]
			if (!entry) {
				return
			}
			this.stepBack()
			this.announce(t('photosweep', 'Took back the verdict on {name}', { name: entry.item.name }, undefined, PLAIN))
			try {
				await this.enqueue(entry.item.fileId, async () => {
					// A save that failed left nothing on the server to take back.
					if (entry.saved) {
						await api.undo(entry.item.fileId)
					}
				})
				this.$emit('changed')
			} catch {
				showError(t('photosweep', 'Could not undo that'))
				this.restoreEntry(entry)
			}
		},

		/**
		 * Puts a verdict back after its undo failed, since the server still holds it.
		 *
		 * Only when the photo is still the one on screen and has not been judged again
		 * meanwhile; otherwise the newer verdict is the one that counts.
		 *
		 * @param {object} entry the history entry the undo removed
		 */
		restoreEntry(entry) {
			if (this.items[this.index] !== entry.item || this.history.some((e) => e.item === entry.item)) {
				return
			}
			this.history.push(entry)
			this.index += 1
			this.playing = false
			this.count(entry.verdict, 1)
		},

		/** Reverses the local bookkeeping of the newest verdict, without calling the server. */
		stepBack() {
			const last = this.history.pop()
			if (!last) {
				return
			}
			this.index = Math.max(0, this.index - 1)
			this.playing = false
			this.count(last.verdict, -1)
		},

		/**
		 * @param {string} verdict "keep" or "delete"
		 * @param {number} delta 1 or -1
		 */
		count(verdict, delta) {
			if (verdict === 'keep') {
				this.kept = Math.max(0, this.kept + delta)
			} else {
				this.deleted = Math.max(0, this.deleted + delta)
			}
		},

		/**
		 * Says what just happened, for screen readers.
		 *
		 * @param {string} text the announcement
		 */
		announce(text) {
			this.announcement = text
		},

		onPointerDown(event) {
			// A press that lands on the play button, or on the video's own controls,
			// is not the start of a swipe. Letting it through matters more than it
			// looks: the capture below redirects every later pointer event to the
			// card, and the button consequently never sees a click at all — which is
			// why the play button did nothing and videos could only be looked at.
			if (event.target instanceof Element && event.target.closest('button, video')) {
				return
			}

			if (this.playing || event.button !== 0) {
				return
			}
			this.pointerId = event.pointerId
			this.startX = event.clientX
			this.dragging = true
			event.currentTarget.setPointerCapture(event.pointerId)
		},

		onPointerMove(event) {
			if (!this.dragging || event.pointerId !== this.pointerId) {
				return
			}
			this.dragX = event.clientX - this.startX
		},

		onPointerUp(event) {
			if (!this.dragging) {
				return
			}
			if (event.pointerId === this.pointerId) {
				event.currentTarget.releasePointerCapture?.(event.pointerId)
			}
			this.dragging = false
			const travelled = this.dragX
			this.dragX = 0
			this.pointerId = null

			if (travelled <= -COMMIT_DISTANCE) {
				this.decide('delete')
			} else if (travelled >= COMMIT_DISTANCE) {
				this.decide('keep')
			}
		},

		/**
		 * The browser took the gesture away: palm rejection, a system swipe, the
		 * screen rotating. That is not the user letting go, so the card goes back
		 * and nothing is recorded, however far it had travelled.
		 *
		 * @param {PointerEvent} event the cancellation
		 */
		onPointerCancel(event) {
			if (!this.dragging) {
				return
			}
			if (event.pointerId === this.pointerId) {
				event.currentTarget.releasePointerCapture?.(event.pointerId)
			}
			this.dragging = false
			this.dragX = 0
			this.pointerId = null
		},
	},
}
</script>

<style scoped>
.pc-deck {
	display: flex;
	flex-direction: column;
	height: 100%;
	padding: 12px max(12px, 3%) 20px;
	gap: 8px;
}

.pc-deck__bar {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 8px;
}

.pc-deck__title {
	display: flex;
	flex-direction: column;
	align-items: center;
	line-height: 1.25;
}

.pc-deck__progress {
	color: var(--color-text-maxcontrast);
	font-size: 0.85em;
}

.pc-deck__centre {
	display: flex;
	align-items: center;
	justify-content: center;
	flex: 1;
}

.pc-deck__stage {
	position: relative;
	flex: 1;
	min-height: 0;
	display: flex;
	align-items: center;
	justify-content: center;
}

.pc-card {
	position: absolute;
	inset: 0;
	display: flex;
	align-items: center;
	justify-content: center;
	border-radius: var(--border-radius-large);
	overflow: hidden;
	background-color: var(--color-background-dark);
	touch-action: pan-y;
	user-select: none;
	cursor: grab;
}

.pc-card--dragging {
	cursor: grabbing;
	transition: none;
}

.pc-card:not(.pc-card--dragging) {
	transition: transform 0.18s ease-out;
}

.pc-card--behind {
	transform: scale(0.96);
	filter: brightness(0.6);
	pointer-events: none;
}

.pc-card__media {
	max-width: 100%;
	max-height: 100%;
	object-fit: contain;
}

.pc-card__play {
	position: absolute;
	inset: 0;
	margin: auto;
	width: 88px;
	height: 88px;
	display: flex;
	align-items: center;
	justify-content: center;
	border: none;
	border-radius: 50%;
	color: var(--color-primary-element-text);
	background-color: rgba(0, 0, 0, 0.55);
	cursor: pointer;
}

.pc-card__stamp {
	position: absolute;
	top: 24px;
	padding: 6px 16px;
	border: 3px solid;
	border-radius: var(--border-radius);
	font-size: 1.4em;
	font-weight: 700;
	text-transform: uppercase;
	letter-spacing: 0.08em;
	pointer-events: none;
}

.pc-card__stamp--delete {
	inset-inline-start: 24px;
	color: var(--color-error);
	border-color: var(--color-error);
	transform: rotate(-12deg);
}

.pc-card__stamp--keep {
	inset-inline-end: 24px;
	color: var(--color-success);
	border-color: var(--color-success);
	transform: rotate(12deg);
}

.pc-card__meta {
	position: absolute;
	inset-inline: 0;
	bottom: 0;
	display: flex;
	flex-direction: column;
	padding: 24px 16px 12px;
	color: #fff;
	background: linear-gradient(transparent, rgba(0, 0, 0, 0.72));
	pointer-events: none;
}

.pc-card__name {
	font-weight: 600;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}

.pc-card__detail {
	font-size: 0.85em;
	opacity: 0.85;
}

.pc-deck__actions {
	display: flex;
	gap: 12px;
	max-width: 560px;
	width: 100%;
	margin: 0 auto;
}

.pc-deck__choices {
	display: flex;
	flex-wrap: wrap;
	justify-content: center;
	gap: 8px;
}

.pc-deck__hint {
	margin: 0;
	text-align: center;
	font-size: 0.85em;
	color: var(--color-text-maxcontrast);
}
</style>
