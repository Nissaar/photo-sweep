<!--
  - SPDX-FileCopyrightText: 2026 Nissaar
  - SPDX-License-Identifier: AGPL-3.0-or-later
  -->

<template>
	<div class="pc-months">
		<div class="pc-months__head">
			<h2>{{ t('photosweep', 'Pick a month') }}</h2>
			<p class="pc-months__sub">
				{{ subtitle }}
			</p>

			<div class="pc-months__filters">
				<NcCheckboxRadioSwitch
					v-for="option in filters"
					:key="option.id"
					:modelValue="filter"
					:value="option.id"
					:buttonVariant="true"
					name="pc-month-filter"
					type="radio"
					buttonVariantGrouped="horizontal"
					@update:modelValue="filter = $event">
					{{ option.label }}
				</NcCheckboxRadioSwitch>
			</div>
		</div>

		<NcEmptyContent
			v-if="!visible.length"
			:name="emptyTitle"
			:description="emptyDescription">
			<template #icon>
				<CalendarMonth />
			</template>
		</NcEmptyContent>

		<ul v-else class="pc-months__grid">
			<li v-for="month in visible" :key="month.month" class="pc-months__cell">
				<button class="pc-month" :class="{ 'pc-month--done': month.done }" @click="$emit('open', month.month)">
					<span class="pc-month__name">{{ label(month.month) }}</span>
					<span class="pc-month__count">
						{{ countLabel(month) }}
					</span>
					<span class="pc-month__bar">
						<span class="pc-month__fill" :style="{ width: progress(month) }" />
					</span>
				</button>
				<!-- A sibling of the tile, not inside it: a button within a button is
				     invalid, and screen readers announce the pair as one control. -->
				<NcButton
					v-if="month.reviewed > 0"
					class="pc-month__again"
					variant="tertiary"
					:title="t('photosweep', 'Review this month again')"
					:aria-label="t('photosweep', 'Review {month} again', { month: label(month.month) })"
					@click="$emit('reopen', month.month)">
					<template #icon>
						<Restore :size="18" />
					</template>
				</NcButton>
			</li>
		</ul>
	</div>
</template>

<script>
import { translatePlural as n, translate as t } from '@nextcloud/l10n'
import NcButton from '@nextcloud/vue/components/NcButton'
import NcCheckboxRadioSwitch from '@nextcloud/vue/components/NcCheckboxRadioSwitch'
import NcEmptyContent from '@nextcloud/vue/components/NcEmptyContent'
import CalendarMonth from 'vue-material-design-icons/CalendarMonth.vue'
import Restore from 'vue-material-design-icons/Restore.vue'
import { monthLabel } from '../format.js'

export default {
	name: 'MonthGrid',

	components: { CalendarMonth, NcButton, NcCheckboxRadioSwitch, NcEmptyContent, Restore },

	props: {
		months: {
			type: Array,
			required: true,
		},

		summary: {
			type: Object,
			required: true,
		},

		scanning: {
			type: Boolean,
			default: false,
		},
	},

	// `reopen` shows the month with the photos already judged, so going over it
	// again never costs a verdict. Clearing them is a separate, confirmed step
	// inside the month itself.
	emits: ['open', 'reopen'],

	data() {
		return {
			// Defaults to what is left to do. With a decade of months on screen, a
			// badge on the finished ones still leaves you hunting for the unfinished.
			filter: 'todo',
		}
	},

	computed: {
		filters() {
			return [
				{ id: 'todo', label: t('photosweep', 'To review') },
				{ id: 'done', label: t('photosweep', 'Done') },
				{ id: 'all', label: t('photosweep', 'All') },
			]
		},

		visible() {
			if (this.filter === 'done') {
				return this.months.filter((m) => m.done)
			}
			if (this.filter === 'all') {
				return this.months
			}
			return this.months.filter((m) => !m.done)
		},

		subtitle() {
			if (!this.summary.indexed) {
				return this.scanning
					? t('photosweep', 'Reading your library…')
					: t('photosweep', 'No photos indexed yet')
			}
			return t('photosweep', '{photos} still to go through, across {months}', {
				photos: n('photosweep', '%n photo', '%n photos', this.summary.photosLeft),
				months: n('photosweep', '%n month', '%n months', this.summary.monthsToReview),
			})
		},

		emptyTitle() {
			if (this.scanning) {
				return t('photosweep', 'Still reading your library')
			}
			if (this.filter === 'todo' && this.months.length) {
				return t('photosweep', 'Every month is done')
			}
			return t('photosweep', 'Nothing here yet')
		},

		emptyDescription() {
			if (this.scanning) {
				return t('photosweep', 'Months appear as they are found.')
			}
			if (this.filter === 'todo' && this.months.length) {
				return t('photosweep', 'Switch to All to go back over one.')
			}
			return t('photosweep', 'Once your photos have been indexed, the months they were taken in show up here.')
		},
	},

	methods: {
		t,
		label: monthLabel,

		countLabel(month) {
			if (month.done) {
				return n('photosweep', 'All %n reviewed', 'All %n reviewed', month.total)
			}
			return n('photosweep', '{remaining} of %n left', '{remaining} of %n left', month.total, {
				remaining: month.remaining,
			})
		},

		progress(month) {
			if (!month.total) {
				return '0%'
			}
			return `${Math.round((month.reviewed / month.total) * 100)}%`
		},
	},
}
</script>

<style scoped>
.pc-months {
	padding: 24px max(16px, 4%);
	max-width: 1100px;
	margin: 0 auto;
}

.pc-months__head h2 {
	margin: 0;
}

.pc-months__sub {
	color: var(--color-text-maxcontrast);
	margin: 4px 0 16px;
}

.pc-months__filters {
	display: flex;
	margin-bottom: 24px;
}

.pc-months__grid {
	display: grid;
	grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
	gap: 12px;
	list-style: none;
	padding: 0;
	margin: 0;
}

.pc-months__cell {
	position: relative;
}

.pc-month {
	display: flex;
	flex-direction: column;
	gap: 6px;
	width: 100%;
	padding: 16px;
	text-align: start;
	background-color: var(--color-main-background);
	border: 2px solid var(--color-border);
	border-radius: var(--border-radius-large);
	cursor: pointer;
}

.pc-month:hover,
.pc-month:focus-visible {
	border-color: var(--color-primary-element);
	background-color: var(--color-background-hover);
}

.pc-month--done {
	opacity: 0.65;
}

.pc-month__name {
	font-weight: 600;
}

.pc-month__count {
	color: var(--color-text-maxcontrast);
	font-size: 0.9em;
}

.pc-month__bar {
	display: block;
	height: 4px;
	border-radius: 2px;
	background-color: var(--color-background-darker);
	overflow: hidden;
}

.pc-month__fill {
	display: block;
	height: 100%;
	background-color: var(--color-primary-element);
}

.pc-month__again {
	position: absolute;
	top: 6px;
	inset-inline-end: 6px;
}
</style>
