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
				:aria-label="t('photosweep', 'Undo')"
				@click="undo">
				<template #icon>
					<UndoVariant :size="20" />
				</template>
				{{ t('photosweep', 'Undo') }}
			</NcButton>
		</div>

		<NcProgressBar :value="progress" size="medium" />

		<div v-if="loading" class="pc-deck__centre">
			<NcLoadingIcon :size="44" />
		</div>

		<NcEmptyContent
			v-else-if="!items.length"
			:name="t('photosweep', 'Nothing left in this month')"
			:description="t('photosweep', 'Every photo here already has a verdict.')">
			<template #icon>
				<CheckAll />
			</template>
			<template #action>
				<NcButton @click="reviewAgain">
					{{ t('photosweep', 'Review this month again') }}
				</NcButton>
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
				<NcButton variant="primary" @click="$emit('back')">
					{{ t('photosweep', 'Back to months') }}
				</NcButton>
			</template>
		</NcEmptyContent>

		<div v-else class="pc-deck__stage">
			<!-- The next photo sits behind the current one so a decision reveals it
			     instantly rather than flashing an empty frame while it loads. -->
			<div v-if="next" class="pc-card pc-card--behind">
				<img :src="preview(next.fileId, DECK_SIZE)" :alt="next.name" loading="eager">
			</div>

			<div
				class="pc-card"
				:class="{ 'pc-card--dragging': dragging }"
				:style="cardStyle"
				@pointerdown="onPointerDown"
				@pointermove="onPointerMove"
				@pointerup="onPointerUp"
				@pointercancel="onPointerUp">
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
						{{ date(current.takenAt) }} · {{ size(current.size) }}
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
			{{ t('photosweep', 'Drag the photo, use the buttons, or press the left and right arrow keys. Nothing is deleted until you confirm it on the Marked for deletion screen.') }}
		</p>
	</div>
</template>

<script>
import { showError } from '@nextcloud/dialogs'
import { translate as t } from '@nextcloud/l10n'
import NcButton from '@nextcloud/vue/components/NcButton'
import NcEmptyContent from '@nextcloud/vue/components/NcEmptyContent'
import NcLoadingIcon from '@nextcloud/vue/components/NcLoadingIcon'
import NcProgressBar from '@nextcloud/vue/components/NcProgressBar'
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

export default {
	name: 'SwipeDeck',

	components: {
		ArrowLeft,
		Check,
		CheckAll,
		Delete,
		NcButton,
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
	},

	emits: ['back', 'changed'],

	data() {
		return {
			items: [],
			index: 0,
			loading: true,
			playing: false,
			kept: 0,
			deleted: 0,
			/** Verdicts given this session, newest last — this is what undo walks back. */
			history: [],
			dragging: false,
			dragX: 0,
			pointerId: null,
			startX: 0,
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
			return t('photosweep', 'Kept {kept}, marked {deleted} for deletion.', {
				kept: this.kept,
				deleted: this.deleted,
			})
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
	},

	async mounted() {
		await this.load()
		window.addEventListener('keydown', this.onKey)
	},

	beforeUnmount() {
		window.removeEventListener('keydown', this.onKey)
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

		async load(skipDecided = null) {
			this.loading = true
			try {
				const result = await api.month(this.month, skipDecided)
				this.items = result.items
				this.index = 0
				this.history = []
				this.kept = 0
				this.deleted = 0
			} catch {
				showError(t('photosweep', 'Could not open that month'))
			} finally {
				this.loading = false
			}
		},

		async reviewAgain() {
			await api.resetMonth(this.month)
			await this.load(false)
			this.$emit('changed')
		},

		onKey(event) {
			if (this.loading || !this.current || event.metaKey || event.ctrlKey) {
				return
			}
			if (event.key === 'ArrowLeft') {
				event.preventDefault()
				this.decide('delete')
			} else if (event.key === 'ArrowRight') {
				event.preventDefault()
				this.decide('keep')
			} else if (event.key === 'z' && this.history.length) {
				event.preventDefault()
				this.undo()
			}
		},

		/**
		 * Records a verdict and moves on.
		 *
		 * The deck advances first and the request follows, because waiting on the
		 * network between every photo is what makes going through a thousand of them
		 * unbearable. A failure is surfaced and the item is put back.
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
			this.history.push({ item, verdict })
			if (verdict === 'keep') {
				this.kept += 1
			} else {
				this.deleted += 1
			}

			try {
				await api.record(item.fileId, verdict)
				this.$emit('changed')
			} catch {
				showError(t('photosweep', 'Could not save that decision'))
				this.stepBack()
			}
		},

		async undo() {
			const last = this.history[this.history.length - 1]
			if (!last) {
				return
			}
			this.stepBack()
			try {
				await api.undo(last.item.fileId)
				this.$emit('changed')
			} catch {
				showError(t('photosweep', 'Could not undo that'))
			}
		},

		/** Reverses the local bookkeeping of one verdict, without calling the server. */
		stepBack() {
			const last = this.history.pop()
			if (!last) {
				return
			}
			this.index = Math.max(0, this.index - 1)
			this.playing = false
			if (last.verdict === 'keep') {
				this.kept = Math.max(0, this.kept - 1)
			} else {
				this.deleted = Math.max(0, this.deleted - 1)
			}
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

.pc-deck__hint {
	margin: 0;
	text-align: center;
	font-size: 0.85em;
	color: var(--color-text-maxcontrast);
}
</style>
