<!--
  - SPDX-FileCopyrightText: 2026 Nissaar
  - SPDX-License-Identifier: AGPL-3.0-or-later
  -->

<template>
	<div class="pc-settings">
		<h2>{{ t('photosweep', 'Settings') }}</h2>

		<section class="pc-settings__section">
			<h3>{{ t('photosweep', 'What happens when you confirm a deletion') }}</h3>

			<NcCheckboxRadioSwitch
				:modelValue="local.mode"
				value="trash"
				name="pc-mode"
				type="radio"
				@update:modelValue="set('mode', $event)">
				{{ t('photosweep', 'Move to the Nextcloud trash') }}
			</NcCheckboxRadioSwitch>
			<p class="pc-settings__hint">
				{{ trashAvailable
					? t('photosweep', 'A real deletion. The files leave your library and your server’s retention policy decides how long they stay recoverable.')
					: t('photosweep', 'The trash app is disabled on this server, so this option deletes permanently and cannot be undone.') }}
			</p>

			<NcCheckboxRadioSwitch
				:modelValue="local.mode"
				value="folder"
				name="pc-mode"
				type="radio"
				@update:modelValue="set('mode', $event)">
				{{ t('photosweep', 'Collect them in a folder') }}
			</NcCheckboxRadioSwitch>
			<p class="pc-settings__hint">
				{{ t('photosweep', 'Deletes nothing. The files are moved together so you can look through them in Files and delete them yourself.') }}
			</p>

			<NcTextField
				v-if="local.mode === 'folder'"
				:modelValue="local.targetFolder"
				:label="t('photosweep', 'Collection folder')"
				:helperText="t('photosweep', 'Created if it does not exist. It is left out of the index, so collected photos will not come back around for review.')"
				@update:modelValue="set('targetFolder', $event)"
				@blur="flush('targetFolder')"
				@keydown.enter="flush('targetFolder')" />
		</section>

		<section class="pc-settings__section">
			<h3>{{ t('photosweep', 'What gets indexed') }}</h3>

			<NcTextField
				:modelValue="local.sourceFolder"
				:label="t('photosweep', 'Folder to go through')"
				:helperText="t('photosweep', 'Use / for everything, or narrow it to something like /Photos. Changing this needs a rebuild to take effect.')"
				@update:modelValue="set('sourceFolder', $event)"
				@blur="flush('sourceFolder')"
				@keydown.enter="flush('sourceFolder')" />

			<NcCheckboxRadioSwitch
				:modelValue="local.skipDecided"
				type="switch"
				@update:modelValue="set('skipDecided', $event)">
				{{ t('photosweep', 'Hide photos you have already judged') }}
			</NcCheckboxRadioSwitch>
			<p class="pc-settings__hint">
				{{ t('photosweep', 'On by default, so reopening a month picks up where you left off instead of starting over.') }}
			</p>
		</section>

		<section class="pc-settings__section">
			<h3>{{ t('photosweep', 'Index') }}</h3>
			<p class="pc-settings__hint">
				{{ t('photosweep', 'Rebuilding reads every photo again and works out its date from scratch. Your verdicts are kept.') }}
			</p>
			<NcButton @click="$emit('rebuild')">
				{{ t('photosweep', 'Rebuild index') }}
			</NcButton>
		</section>
	</div>
</template>

<script>
import { translate as t } from '@nextcloud/l10n'
import NcButton from '@nextcloud/vue/components/NcButton'
import NcCheckboxRadioSwitch from '@nextcloud/vue/components/NcCheckboxRadioSwitch'
import NcTextField from '@nextcloud/vue/components/NcTextField'

export default {
	name: 'SettingsPanel',

	components: { NcButton, NcCheckboxRadioSwitch, NcTextField },

	props: {
		config: {
			type: Object,
			required: true,
		},

		trashAvailable: {
			// Defaults to false so an unknown trash state warns rather than reassures.
			type: Boolean,
			default: false,
		},
	},

	emits: ['save', 'rebuild'],

	data() {
		return {
			local: { ...this.config },
		}
	},

	watch: {
		config: {
			handler(value) {
				// A save answers with every setting, including one still being typed
				// into. What is typed wins until it has been saved in its turn.
				this.local = { ...value, ...this.unsaved }
			},

			deep: true,
		},
	},

	created() {
		// Per setting, so saving one never drops another's unsaved edit. Not
		// reactive: nothing on screen depends on it.
		this.unsaved = {}
	},

	beforeUnmount() {
		// Leaving the screen is not a reason to drop what was typed.
		this.flush()
	},

	methods: {
		t,

		/**
		 * Applies a change locally at once. Switches save straight away; the folder
		 * fields save when they lose focus or on Enter.
		 *
		 * Saving while typing would write half-typed folder paths, one of which would
		 * be the one that gets created, and the server refuses in-between values such
		 * as "/" for the collection folder, so each pause would flash an error.
		 *
		 * @param {string} key the setting
		 * @param {string|boolean} value its new value
		 */
		set(key, value) {
			this.local = { ...this.local, [key]: value }
			this.unsaved[key] = value
			if (typeof value !== 'string') {
				this.flush(key)
			}
		},

		/**
		 * Saves what is waiting, for one setting or all of them.
		 *
		 * @param {string|null} only the setting to save, or null for every one
		 */
		flush(only = null) {
			const keys = only === null ? Object.keys(this.unsaved) : [only]
			const patch = {}
			for (const key of keys) {
				if (key in this.unsaved) {
					patch[key] = this.unsaved[key]
					delete this.unsaved[key]
				}
			}
			if (Object.keys(patch).length) {
				this.$emit('save', patch)
			}
		},
	},
}
</script>

<style scoped>
.pc-settings {
	padding: 24px max(16px, 4%);
	max-width: 680px;
	margin: 0 auto;
}

.pc-settings h2 {
	margin: 0 0 8px;
}

.pc-settings__section {
	margin-top: 32px;
}

.pc-settings__section h3 {
	margin: 0 0 12px;
}

.pc-settings__hint {
	margin: 4px 0 16px 28px;
	color: var(--color-text-maxcontrast);
	font-size: 0.9em;
}
</style>
