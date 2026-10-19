package io.github.nissaar.photosweep.data

import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.nissaar.photosweep.api.ApiError
import io.github.nissaar.photosweep.api.ApiException
import io.github.nissaar.photosweep.api.ApplyResult
import io.github.nissaar.photosweep.api.ClearedResult
import io.github.nissaar.photosweep.api.CleanupMode
import io.github.nissaar.photosweep.api.DecisionsResponse
import io.github.nissaar.photosweep.api.Failure
import io.github.nissaar.photosweep.api.MediaItem
import io.github.nissaar.photosweep.api.MonthResponse
import io.github.nissaar.photosweep.api.MonthsResponse
import io.github.nissaar.photosweep.api.NotSignedInException
import io.github.nissaar.photosweep.api.PendingVerdict
import io.github.nissaar.photosweep.api.PhotoSweepApi
import io.github.nissaar.photosweep.api.RestoreResult
import io.github.nissaar.photosweep.api.ScanResponse
import io.github.nissaar.photosweep.api.ServerConfig
import io.github.nissaar.photosweep.api.StatusResponse
import io.github.nissaar.photosweep.api.Verdict
import java.io.IOException

/**
 * Everything the screens need, with the offline queue folded in.
 *
 * The rule here is that giving a verdict never fails and never blocks. It is written
 * to the outbox, and reaching the server is a separate concern that retries. The one
 * thing that does talk to the server synchronously is applying verdicts, because that
 * is the step that changes files and its outcome has to be reported honestly.
 */
class Repository(
    private val api: PhotoSweepApi,
    private val outbox: VerdictOutbox,
    /** [Account.key] of whoever is signed in, which is what the outbox is tagged with. */
    private val accountKey: () -> String?,
) {

    private val transport = object : OutboxTransport {
        override suspend fun record(verdicts: List<PendingVerdict>) {
            api.recordMany(verdicts)
        }

        override suspend fun withdraw(fileId: Long) {
            try {
                api.undo(fileId)
            } catch (e: ApiException) {
                // 404 is "nothing pending for that file": the verdict never arrived,
                // or was already withdrawn. Either way the server now holds what the
                // user asked for. Anything else, signing out included, is a failure.
                if (e is NotSignedInException || e.status != 404) throw e
            }
        }
    }

    private fun owner(): String = accountKey() ?: throw NotSignedInException()

    suspend fun status(): StatusResponse = api.status()

    suspend fun scan(full: Boolean = false): ScanResponse = api.scan(full)

    suspend fun months(): MonthsResponse = api.months()

    /**
     * One month's photos.
     *
     * Anything with a verdict still sitting in the outbox is filtered out here.
     * Without that, a month reopened before the queue has drained deals the same
     * photos again — the server has not heard about them yet, so it still thinks they
     * need a verdict.
     */
    suspend fun month(yearMonth: String, skipDecided: Boolean? = null): MonthResponse {
        val response = api.month(yearMonth, skipDecided)
        val queued = outbox.pending(owner()).filter { it.verdict != null }.map { it.fileId }.toHashSet()
        if (queued.isEmpty()) return response
        return response.copy(items = response.items.filterNot { it.fileId in queued })
    }

    /**
     * Records a verdict. Returns immediately; delivery is the outbox's problem.
     *
     * @return whether it reached the server straight away
     */
    suspend fun record(item: MediaItem, verdict: String): Boolean {
        outbox.record(owner(), item.fileId, verdict)
        return flushQuietly()
    }

    /**
     * Takes a verdict back.
     *
     * Always goes through the outbox, as an instruction of its own. Whether the
     * verdict is still queued, in flight or already on the server cannot be known
     * safely from here, and guessing "never sent" while it was in flight is what let
     * an undone delete survive on the server. The server refuses to withdraw a
     * verdict that has already been carried out, which is correct: the way back from
     * there is a restore, not an undo.
     *
     * @return whether the server has heard about it straight away
     */
    suspend fun undo(fileId: Long): Boolean {
        outbox.withdraw(owner(), fileId)
        return flushQuietly()
    }

    /** How many instructions have not reached the server yet. */
    suspend fun queuedCount(): Int = outbox.size(owner())

    /**
     * Pushes the queue to the server.
     *
     * @return true if the queue is now empty
     */
    suspend fun flushOutbox(): Boolean {
        val owner = owner()
        outbox.flush(owner, transport)
        return outbox.size(owner) == 0
    }

    private suspend fun flushQuietly(): Boolean = try {
        flushOutbox()
    } catch (e: NotSignedInException) {
        throw e
    } catch (e: Exception) {
        // Offline, or the server is having a moment. The queue keeps it.
        false
    }

    /**
     * Everything marked for deletion, as the user currently means it.
     *
     * A photo whose last queued instruction is not a delete — kept after all, or
     * undone — is left out even though the server has not heard yet, so taking one
     * off the list while offline does not put it back on the next reload.
     */
    suspend fun pending(): DecisionsResponse {
        val response = api.pending()
        val overridden = outbox.pending(owner())
            .filter { it.verdict != Verdict.DELETE }
            .map { it.fileId }
            .toHashSet()
        if (overridden.isEmpty()) return response
        return response.copy(decisions = response.decisions.filterNot { it.fileId in overridden })
    }

    suspend fun applied(): DecisionsResponse = api.applied()

    /**
     * Carries out the pending deletes the user confirmed, and no others.
     *
     * Sent a few at a time, however many there are. Deleting takes about a second a
     * photo on a busy server, so one request for two thousand outlives every proxy's
     * timeout, the phone gives up while the server carries on, and the next tap runs
     * into a deletion still in progress. Small requests each finish well inside any
     * timeout.
     *
     * Throws if the first batch fails, so the caller can explain a refusal with
     * nothing done. After that a failure ends the run with [ApplyResult.interrupted]
     * set: what went through stays done and the rest is still pending.
     *
     * The caller drains the queue and checks the list first: see
     * [io.github.nissaar.photosweep.vm.ReviewViewModel.apply].
     *
     * @param onProgress told how many of the photos have been dealt with after each batch
     */
    suspend fun apply(
        fileIds: List<Long>,
        permanent: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ApplyResult {
        var mode: String? = null
        var targetFolder: String? = null
        var succeeded = 0
        val failures = mutableListOf<Failure>()

        for ((index, batch) in fileIds.chunked(APPLY_BATCH_SIZE).withIndex()) {
            val result = try {
                applyBatch(batch, permanent)
            } catch (e: NotSignedInException) {
                throw e
            } catch (e: IOException) {
                if (index == 0) throw e
                return ApplyResult(
                    mode = mode ?: CleanupMode.TRASH,
                    succeeded = succeeded,
                    failed = failures.size,
                    failures = failures,
                    targetFolder = targetFolder,
                    interrupted = true,
                )
            }

            mode = result.mode
            targetFolder = result.targetFolder ?: targetFolder
            succeeded += result.succeeded
            failures += result.failures
            if (result.error != null) {
                // The server could not even start, and would say the same to the next
                // batch.
                return result.copy(succeeded = succeeded, failed = failures.size, failures = failures)
            }
            onProgress(minOf((index + 1) * APPLY_BATCH_SIZE, fileIds.size), fileIds.size)
        }

        return ApplyResult(
            mode = mode ?: CleanupMode.TRASH,
            succeeded = succeeded,
            failed = failures.size,
            failures = failures,
            targetFolder = targetFolder,
        )
    }

    /**
     * One batch, sent again if it only failed for a reason that will pass.
     *
     * A batch that timed out on the phone is usually still running on the server, so
     * its retry is told a deletion is in progress until it finishes. Sending it again
     * is safe either way: the server only acts on photos that are still pending.
     */
    private suspend fun applyBatch(batch: List<Long>, permanent: Boolean): ApplyResult {
        var attempt = 1
        while (true) {
            try {
                return api.apply(batch, permanent)
            } catch (e: NotSignedInException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= APPLY_ATTEMPTS || !isTransient(e)) throw e
                attempt++
                delay(APPLY_RETRY_DELAY_MS)
            }
        }
    }

    private fun isTransient(e: IOException): Boolean {
        if (e !is ApiException) return true // a timeout or a dropped connection
        return e.code == ApiError.APPLY_RUNNING || (e.code == null && e.status >= 500)
    }

    suspend fun restore(fileIds: List<Long>): RestoreResult = api.restore(fileIds)

    suspend fun resetMonth(yearMonth: String): ClearedResult = api.resetMonth(yearMonth)

    suspend fun setMode(mode: String): ServerConfig =
        api.updateConfig(buildJsonObject { put("mode", mode) })

    suspend fun setTargetFolder(path: String): ServerConfig =
        api.updateConfig(buildJsonObject { put("targetFolder", path) })

    suspend fun setSkipDecided(value: Boolean): ServerConfig =
        api.updateConfig(buildJsonObject { put("skipDecided", value) })

    companion object {
        /** Photos per apply request: each finishes well inside any proxy's timeout. */
        const val APPLY_BATCH_SIZE = 25

        /** Tries per batch before giving up on it. */
        private const val APPLY_ATTEMPTS = 5

        private const val APPLY_RETRY_DELAY_MS = 3000L
    }
}
