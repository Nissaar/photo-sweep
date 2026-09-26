package io.github.nissaar.photosweep.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OCS wraps every response in the same envelope. `meta` carries the real status —
 * an OCS error still arrives as HTTP 200 when the client asks for v2 responses, so
 * the status code inside is the one that matters.
 */
@Serializable
data class OcsEnvelope<T>(val ocs: Ocs<T>)

@Serializable
data class Ocs<T>(val meta: OcsMeta, val data: T)

@Serializable
data class OcsMeta(
    val status: String = "",
    val statuscode: Int = 0,
    val message: String? = null,
)

/** One photo or video, as the server's index holds it. */
@Serializable
data class MediaItem(
    val fileId: Long,
    /** Date taken, epoch seconds. */
    val takenAt: Long,
    val yearMonth: String,
    val isVideo: Boolean = false,
    val mimetype: String = "",
    val name: String = "",
    /** Path relative to the user's files root, which is what plays a video. */
    val path: String = "",
    val size: Long = 0,
    /**
     * Which strategy produced [takenAt]. Shown in the UI so a photo in a surprising
     * month is explainable rather than just wrong.
     */
    val dateSource: String = "mtime",
)

@Serializable
data class MonthEntry(
    val month: String,
    val total: Int,
    val reviewed: Int,
    val remaining: Int,
    val done: Boolean,
)

@Serializable
data class Summary(
    val indexed: Int = 0,
    val months: Int = 0,
    val monthsToReview: Int = 0,
    val photosLeft: Int = 0,
    val pendingDeletes: Int = 0,
    val kept: Int = 0,
)

@Serializable
data class ScanState(
    val complete: Boolean = false,
    val running: Boolean = false,
    val found: Int = 0,
    val startedAt: Long = 0,
    val updatedAt: Long = 0,
    val error: String? = null,
)

@Serializable
data class ServerConfig(
    val mode: String = CleanupMode.TRASH,
    val targetFolder: String = "/To Be Deleted",
    val sourceFolder: String = "/",
    val skipDecided: Boolean = true,
    val timezone: String = "UTC",
)

object CleanupMode {
    const val TRASH = "trash"
    const val FOLDER = "folder"
}

object Verdict {
    const val KEEP = "keep"
    const val DELETE = "delete"
}

@Serializable
data class StatusResponse(
    val scan: ScanState = ScanState(),
    val summary: Summary = Summary(),
    val config: ServerConfig = ServerConfig(),
    /**
     * False when `files_trashbin` is disabled, which makes trash mode permanent.
     * Surfaced before anything is deleted rather than after.
     */
    val trashAvailable: Boolean = true,
)

@Serializable
data class ScanResponse(
    val scan: ScanState = ScanState(),
    val summary: Summary = Summary(),
)

@Serializable
data class MonthsResponse(
    val months: List<MonthEntry> = emptyList(),
    val summary: Summary = Summary(),
)

@Serializable
data class MonthResponse(
    val month: String = "",
    val items: List<MediaItem> = emptyList(),
)

@Serializable
data class Decision(
    val fileId: Long,
    val verdict: String,
    val decidedAt: Long = 0,
    val applied: Boolean = false,
    val appliedAt: Long? = null,
    val appliedMode: String? = null,
    val takenAt: Long = 0,
    val yearMonth: String = "",
    val name: String = "",
    val size: Long = 0,
    val originPath: String? = null,
    val isVideo: Boolean = false,
)

@Serializable
data class DecisionsResponse(val decisions: List<Decision> = emptyList())

@Serializable
data class Failure(val fileId: Long, val reason: String)

@Serializable
data class ApplyResult(
    val mode: String = CleanupMode.TRASH,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val failures: List<Failure> = emptyList(),
    val targetFolder: String? = null,
    val error: String? = null,
)

@Serializable
data class RestoreResult(
    val restored: Int = 0,
    val failures: List<Failure> = emptyList(),
)

@Serializable
data class RecordBatchResult(
    val recorded: Int = 0,
    val skipped: List<Long> = emptyList(),
)

@Serializable
data class ClearedResult(val cleared: Int = 0)

/** Nextcloud core's description of the signed-in user; only the id is needed. */
@Serializable
data class CloudUser(val id: String)

/** One verdict on its way to the server. */
@Serializable
data class PendingVerdict(
    @SerialName("fileId") val fileId: Long,
    @SerialName("verdict") val verdict: String,
)
