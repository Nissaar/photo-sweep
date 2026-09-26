<!--
  - SPDX-FileCopyrightText: 2026 Nissaar
  - SPDX-License-Identifier: AGPL-3.0-or-later
  -->

<template>
	<div class="pc-index">
		<div v-if="scanning" class="pc-index__line">
			<NcLoadingIcon :size="16" />
			<span>{{ n('photosweep', 'Indexing — %n photo so far', 'Indexing — %n photos so far', scan.found) }}</span>
		</div>
		<div v-else class="pc-index__line">
			<span>{{ indexedLabel }}</span>
		</div>

		<!-- The server's own error text can be an exception message with paths in
		     it. It is in the server log; this line only says that something broke. -->
		<p v-if="scan.error && !scanning" class="pc-index__error">
			{{ t('photosweep', 'The last scan stopped because of an error. Try again, or ask your administrator to check the server log.') }}
		</p>

		<div class="pc-index__actions">
			<NcButton variant="tertiary" :disabled="scanning" @click="$emit('scan')">
				{{ t('photosweep', 'Check for new photos') }}
			</NcButton>
			<NcButton variant="tertiary" :disabled="scanning" @click="$emit('rebuild')">
				{{ t('photosweep', 'Rebuild index') }}
			</NcButton>
		</div>
	</div>
</template>

<script>
import { translatePlural as n, translate as t } from '@nextcloud/l10n'
import NcButton from '@nextcloud/vue/components/NcButton'
import NcLoadingIcon from '@nextcloud/vue/components/NcLoadingIcon'

export default {
	name: 'IndexStatus',

	components: { NcButton, NcLoadingIcon },

	props: {
		scan: {
			type: Object,
			required: true,
		},

		summary: {
			type: Object,
			required: true,
		},

		/**
		 * Whether a scan is under way, as the app knows it.
		 *
		 * Not `scan.running`: the server sets and clears that inside one request,
		 * so every answer the browser sees says false and the spinner never showed.
		 */
		scanning: {
			type: Boolean,
			default: false,
		},
	},

	emits: ['scan', 'rebuild'],

	computed: {
		indexedLabel() {
			if (!this.summary.indexed) {
				return t('photosweep', 'Nothing indexed yet')
			}
			return n('photosweep', '%n photo indexed', '%n photos indexed', this.summary.indexed)
		},
	},

	methods: { t, n },
}
</script>

<style scoped>
.pc-index {
	padding: 8px 12px 12px;
	border-top: 1px solid var(--color-border);
	font-size: 0.9em;
	color: var(--color-text-maxcontrast);
}

.pc-index__line {
	display: flex;
	align-items: center;
	gap: 8px;
	min-height: 24px;
}

.pc-index__error {
	margin: 4px 0;
	color: var(--color-error);
}

.pc-index__actions {
	display: flex;
	flex-wrap: wrap;
	gap: 4px;
	margin-top: 4px;
}
</style>
