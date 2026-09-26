<!--
  - SPDX-FileCopyrightText: 2026 Nissaar
  - SPDX-License-Identifier: AGPL-3.0-or-later
  -->

<template>
	<div class="pc-review">
		<div class="pc-review__head">
			<h2>{{ t('photosweep', 'Marked for deletion') }}</h2>
			<p class="pc-review__sub">
				{{ t('photosweep', 'This is the only screen that changes your files. Take anything out that you want to keep, then confirm.') }}
			</p>
		</div>

		<div v-if="loading" class="pc-review__centre">
			<NcLoadingIcon :size="44" />
		</div>

		<NcEmptyContent
			v-else-if="loadFailed"
			:name="t('photosweep', 'Could not load your list')"
			:description="t('photosweep', 'Nothing has been changed. Check your connection and try again.')">
			<template #icon>
				<AlertCircle />
			</template>
			<template #action>
				<NcButton @click="load">
					{{ t('photosweep', 'Try again') }}
				</NcButton>
			</template>
		</NcEmptyContent>

		<template v-else>
			<NcNoteCard v-if="mode === 'trash' && !trashAvailable" type="warning">
				{{ t('photosweep', 'The trash app is disabled on this server, so deleting is permanent and cannot be undone. Switch to collecting files in a folder in Settings if you would rather check them first.') }}
			</NcNoteCard>
			<NcNoteCard v-else-if="mode === 'trash'" type="info">
				{{ t('photosweep', 'These files move to your Nextcloud trash, where they stay until your server’s retention policy clears them.') }}
			</NcNoteCard>
			<NcNoteCard v-else type="info">
				{{ t('photosweep', 'These files are moved into your collection folder. Nothing is deleted — you delete them yourself in Files.') }}
			</NcNoteCard>

			<NcEmptyContent
				v-if="!pending.length"
				:name="t('photosweep', 'Nothing marked for deletion')"
				:description="t('photosweep', 'Go through a month and anything you swipe away will be listed here first.')">
				<template #icon>
					<DeleteClock />
				</template>
			</NcEmptyContent>

			<template v-else>
				<div class="pc-review__actions">
					<NcButton variant="primary" :disabled="applying || busy.size > 0" @click="confirmApply">
						<template #icon>
							<NcLoadingIcon v-if="applying" :size="20" />
							<Delete v-else :size="20" />
						</template>
						{{ applying
							? t('photosweep', 'Working…')
							: n('photosweep', 'Delete %n photo', 'Delete %n photos', pending.length) }}
					</NcButton>
					<span class="pc-review__total">{{ totalSize }}</span>
				</div>

				<ul class="pc-review__grid">
					<li v-for="item in pending" :key="item.fileId" class="pc-tile">
						<img :src="preview(item.fileId, 300)" :alt="item.name" loading="lazy">
						<span class="pc-tile__name" :title="item.originPath">{{ item.name }}</span>
						<NcButton
							class="pc-tile__pull"
							variant="tertiary"
							:title="t('photosweep', 'Keep this one after all')"
							:aria-label="keepLabel(item)"
							:disabled="applying || busy.has(item.fileId)"
							@click="pullOut(item)">
							<template #icon>
								<Close :size="18" />
							</template>
						</NcButton>
					</li>
				</ul>
			</template>

			<section v-if="applied.length" class="pc-review__history">
				<h3>{{ t('photosweep', 'Already dealt with') }}</h3>
				<p class="pc-review__sub">
					{{ t('photosweep', 'Bring any of these back if you change your mind.') }}
				</p>

				<ul class="pc-review__grid">
					<li v-for="item in applied" :key="item.fileId" class="pc-tile pc-tile--done">
						<img :src="preview(item.fileId, 300)" :alt="item.name" loading="lazy">
						<span class="pc-tile__name" :title="item.originPath">{{ item.name }}</span>
						<span class="pc-tile__when">{{ date(item.appliedAt, timeZone) }}</span>
						<NcButton
							class="pc-tile__pull"
							variant="tertiary"
							:title="t('photosweep', 'Restore')"
							:aria-label="restoreLabel(item)"
							:disabled="applying || busy.has(item.fileId)"
							@click="restore(item)">
							<template #icon>
								<Restore :size="18" />
							</template>
						</NcButton>
					</li>
				</ul>
			</section>
		</template>

		<NcDialog
			v-if="confirming"
			:name="n('photosweep', 'Delete %n photo?', 'Delete %n photos?', pending.length)"
			:message="confirmMessage"
			@closing="confirming = false">
			<template #actions>
				<NcButton variant="tertiary" @click="confirming = false">
					{{ t('photosweep', 'Cancel') }}
				</NcButton>
				<NcButton variant="error" @click="apply">
					{{ confirmLabel }}
				</NcButton>
			</template>
		</NcDialog>
	</div>
</template>

<script>
import { showError, showSuccess } from '@nextcloud/dialogs'
import { translatePlural as n, translate as t } from '@nextcloud/l10n'
import NcButton from '@nextcloud/vue/components/NcButton'
import NcDialog from '@nextcloud/vue/components/NcDialog'
import NcEmptyContent from '@nextcloud/vue/components/NcEmptyContent'
import NcLoadingIcon from '@nextcloud/vue/components/NcLoadingIcon'
import NcNoteCard from '@nextcloud/vue/components/NcNoteCard'
import AlertCircle from 'vue-material-design-icons/AlertCircle.vue'
import Close from 'vue-material-design-icons/Close.vue'
import Delete from 'vue-material-design-icons/Delete.vue'
import DeleteClock from 'vue-material-design-icons/DeleteClock.vue'
import Restore from 'vue-material-design-icons/Restore.vue'
import api, { errorCode, previewUrl } from '../api.js'
import { dateLabel, sizeLabel } from '../format.js'

/**
 * For translations with a file name in them. Vue escapes the result when it renders
 * it, so escaping here as well would show a name like "Tom & Jerry.jpg" as "&amp;".
 */
const PLAIN = { escape: false, sanitize: false }

export default {
	name: 'ReviewList',

	components: {
		AlertCircle,
		Close,
		Delete,
		DeleteClock,
		NcButton,
		NcDialog,
		NcEmptyContent,
		NcLoadingIcon,
		NcNoteCard,
		Restore,
	},

	props: {
		mode: {
			type: String,
			default: 'trash',
		},

		trashAvailable: {
			// Defaults to false so an unknown trash state warns rather than reassures.
			type: Boolean,
			default: false,
		},

		timeZone: {
			type: String,
			default: null,
		},
	},

	emits: ['changed'],

	data() {
		return {
			pending: [],
			applied: [],
			loading: true,
			loadFailed: false,
			applying: false,
			confirming: false,
			/** File ids with a take-out or restore request still out. */
			busy: new Set(),
		}
	},

	computed: {
		totalSize() {
			const bytes = this.pending.reduce((sum, item) => sum + (item.size ?? 0), 0)
			return bytes ? sizeLabel(bytes) : ''
		},

		/** Trash mode with the trash app off: the one case where nothing can be undone. */
		permanent() {
			return this.mode === 'trash' && this.trashAvailable === false
		},

		confirmMessage() {
			if (this.mode === 'trash') {
				return this.permanent
					? t('photosweep', 'The trash is disabled on this server, so this cannot be undone.')
					: t('photosweep', 'They go to your Nextcloud trash and can be restored from here until your server clears them.')
			}
			return t('photosweep', 'They are moved into your collection folder. Nothing is deleted.')
		},

		confirmLabel() {
			if (this.mode !== 'trash') {
				return t('photosweep', 'Move to folder')
			}
			return this.permanent
				? t('photosweep', 'Delete permanently')
				: t('photosweep', 'Move to trash')
		},
	},

	async mounted() {
		await this.load()
	},

	methods: {
		t,
		n,
		preview: previewUrl,
		date: dateLabel,

		/**
		 * Names the photo, so a screen reader does not hear the same label on every tile.
		 *
		 * @param {object} item a decision from the list
		 * @return {string}
		 */
		keepLabel(item) {
			return t('photosweep', 'Keep {name} after all', { name: item.name }, undefined, PLAIN)
		},

		/**
		 * @param {object} item a decision from the list
		 * @return {string}
		 */
		restoreLabel(item) {
			return t('photosweep', 'Restore {name}', { name: item.name }, undefined, PLAIN)
		},

		async load() {
			this.loading = true
			try {
				const [pending, applied] = await Promise.all([api.pending(), api.applied()])
				this.pending = pending.decisions
				this.applied = applied.decisions
				this.loadFailed = false
			} catch {
				// Its own state rather than a toast over an empty list: an empty list
				// says "nothing marked", which this screen must not claim when it
				// simply could not ask.
				this.loadFailed = true
			} finally {
				this.loading = false
			}
		},

		confirmApply() {
			this.confirming = true
		},

		/**
		 * Runs one request for a file, refusing a second while the first is still out.
		 *
		 * A repeated click would reach the server after the first had already worked,
		 * and report a failure for something that succeeded.
		 *
		 * @param {number} fileId the file
		 * @param {() => Promise<void>} task the request
		 */
		async whileBusy(fileId, task) {
			if (this.busy.has(fileId)) {
				return
			}
			this.busy.add(fileId)
			try {
				await task()
			} finally {
				this.busy.delete(fileId)
			}
		},

		async pullOut(item) {
			await this.whileBusy(item.fileId, async () => {
				try {
					await api.undo(item.fileId)
					this.pending = this.pending.filter((d) => d.fileId !== item.fileId)
					this.$emit('changed')
				} catch {
					showError(t('photosweep', 'Could not take that one out of the list'))
				}
			})
		},

		async apply() {
			this.confirming = false
			this.applying = true
			// Exactly what is on screen. Anything marked on another device since this
			// list loaded stays pending until it has been looked at here.
			const fileIds = this.pending.map((item) => item.fileId)
			try {
				// `permanent` is only ever true once the dialog has said, in so many
				// words, that this cannot be undone. The server refuses a permanent
				// delete without it.
				const result = await api.apply(fileIds, this.permanent)
				if (result.error) {
					// The server's own text can carry a path or an exception message,
					// neither of which belongs in a toast.
					showError(result.mode === 'folder'
						? t('photosweep', 'Nothing was moved — the collection folder could not be created')
						: t('photosweep', 'Nothing was changed'))
				} else if (result.failed > 0) {
					showError(n('photosweep', '{done} done, %n could not be changed', '{done} done, %n could not be changed', result.failed, {
						done: result.succeeded,
					}))
				} else {
					showSuccess(n('photosweep', '%n photo dealt with', '%n photos dealt with', result.succeeded))
				}
				await this.load()
				this.$emit('changed')
			} catch (error) {
				await this.applyRefused(error)
			} finally {
				this.applying = false
			}
		},

		/**
		 * Explains an apply the server turned down. Nothing on disk changed in any case.
		 *
		 * @param {Error} error what the request threw
		 */
		async applyRefused(error) {
			switch (errorCode(error)) {
				case 'trash_unavailable':
					// The trash was switched off after this page loaded, so the dialog
					// promised something reversible. Refreshing brings up the warning,
					// and the next confirmation is an informed one.
					showError(t('photosweep', 'Nothing was changed. The trash has just been turned off on this server, so these files would be deleted permanently. Read the warning and confirm again if you still want to.'))
					this.$emit('changed')
					break
				case 'apply_running':
					showError(t('photosweep', 'Nothing was changed — another deletion is still running. The list has been reloaded.'))
					await this.load()
					this.$emit('changed')
					break
				default:
					showError(t('photosweep', 'Nothing was changed — the request failed'))
			}
		},

		async restore(item) {
			await this.whileBusy(item.fileId, async () => {
				try {
					const result = await api.restore([item.fileId])
					if (result.restored) {
						showSuccess(t('photosweep', 'Brought back'))
					} else {
						// The server words these reasons for people, in their language.
						showError(result.failures[0]?.reason ?? t('photosweep', 'Could not bring that back'))
					}
					await this.load()
					this.$emit('changed')
				} catch {
					showError(t('photosweep', 'Could not bring that back'))
				}
			})
		},
	},
}
</script>

<style scoped>
.pc-review {
	padding: 24px max(16px, 4%);
	max-width: 1100px;
	margin: 0 auto;
}

.pc-review__head h2 {
	margin: 0;
}

.pc-review__sub {
	color: var(--color-text-maxcontrast);
	margin: 4px 0 16px;
}

.pc-review__centre {
	display: flex;
	justify-content: center;
	padding: 48px 0;
}

.pc-review__actions {
	display: flex;
	align-items: center;
	gap: 12px;
	margin: 16px 0;
}

.pc-review__total {
	color: var(--color-text-maxcontrast);
}

.pc-review__grid {
	display: grid;
	grid-template-columns: repeat(auto-fill, minmax(130px, 1fr));
	gap: 10px;
	list-style: none;
	padding: 0;
	margin: 0;
}

.pc-tile {
	position: relative;
	display: flex;
	flex-direction: column;
	border-radius: var(--border-radius-large);
	overflow: hidden;
	background-color: var(--color-background-dark);
}

.pc-tile img {
	width: 100%;
	aspect-ratio: 1;
	object-fit: cover;
}

.pc-tile__name {
	padding: 6px 8px 0;
	font-size: 0.8em;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
}

.pc-tile__when {
	padding: 0 8px 6px;
	font-size: 0.75em;
	color: var(--color-text-maxcontrast);
}

.pc-tile--done img {
	opacity: 0.55;
}

.pc-tile__pull {
	position: absolute;
	top: 4px;
	inset-inline-end: 4px;
	background-color: var(--color-main-background);
	border-radius: 50%;
}

.pc-review__history {
	margin-top: 40px;
	padding-top: 24px;
	border-top: 1px solid var(--color-border);
}

.pc-review__history h3 {
	margin: 0;
}
</style>
