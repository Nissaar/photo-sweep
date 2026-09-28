package io.github.nissaar.photosweep.data

import android.util.Log
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.nissaar.photosweep.api.PendingVerdict
import java.io.File
import java.io.FileNotFoundException

/**
 * One instruction waiting to reach the server.
 *
 * @param seq position in the log. What a flush removes is decided by this rather
 *   than by file id, so an instruction given while an earlier one for the same file
 *   was in flight is never mistaken for the one that was sent.
 * @param account [Account.key] of the account that gave it. Only that account's
 *   flushes send it: file ids are small integers, and the same number on another
 *   server is an unrelated photo.
 * @param verdict the verdict to record, or null to withdraw whatever verdict the
 *   server holds for the file, which is what undo does.
 */
@Serializable
data class OutboxOp(
    val seq: Long,
    val account: String,
    val fileId: Long,
    val verdict: String? = null,
)

/** How the outbox reaches the server. An interface so the tests can play the server. */
interface OutboxTransport {
    suspend fun record(verdicts: List<PendingVerdict>)

    /** Withdraws a verdict. Returns normally when the server had nothing to withdraw. */
    suspend fun withdraw(fileId: Long)
}

@Serializable
private data class OutboxFile(val version: Int = FORMAT_VERSION, val ops: List<OutboxOp> = emptyList())

private const val FORMAT_VERSION = 2

/**
 * Verdicts, and withdrawals of verdicts, that have not reached the server yet.
 *
 * Swiping has to stay instant. Waiting for a round trip between every photo is what
 * makes going through a thousand of them unbearable, and a train tunnel should not end
 * a review session. So an instruction is written here first and sent afterwards, and
 * what is here survives the process being killed.
 *
 * It is an ordered log. A later instruction for a file replaces an earlier one that
 * has not left yet, because each one fully decides what the server should hold for
 * that file. One that is already in flight is left alone and the new one is sent
 * after it, so the server always ends up with the last thing the user said.
 *
 * The log is kept in memory and written through to disk off the main thread, with an
 * [AtomicFile] that syncs before it replaces the previous copy.
 *
 * @param legacyOwner the account a queue written by an older version belongs to.
 *   That format carried no account, and the only account it can have come from is
 *   the one still signed in when it is first read.
 */
class VerdictOutbox(
    file: File,
    private val legacyOwner: suspend () -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val store = AtomicFile(file)

    /** Guards [ops], [inFlight] and writes to disk. */
    private val lock = Mutex()

    /** One flush at a time: overlapping flushes each re-sent the whole queue. */
    private val flushLock = Mutex()

    private var ops: MutableList<OutboxOp>? = null
    private var nextSeq = 1L
    private val inFlight = HashSet<Long>()

    private companion object {
        const val TAG = "VerdictOutbox"

        /**
         * A cap, so a pathological session cannot grow the file without limit. Ten
         * thousand instructions is far more than anyone gives in one offline stretch.
         */
        const val MAX_ENTRIES = 10_000

        /** Verdicts per request; keeps one request well inside any proxy's limits. */
        const val BATCH = 200
    }

    /** Queues a verdict. */
    suspend fun record(account: String, fileId: Long, verdict: String) = enqueue(account, fileId, verdict)

    /** Queues taking a verdict back, whether or not the earlier one has been sent. */
    suspend fun withdraw(account: String, fileId: Long) = enqueue(account, fileId, null)

    /** What is waiting for [account], one entry per file: the latest instruction. */
    suspend fun pending(account: String): List<OutboxOp> = lock.withLock {
        latestPerFile(loaded().filter { it.account == account })
    }

    suspend fun size(account: String): Int = pending(account).size

    /**
     * Sends everything queued for [account] and removes exactly what the server
     * accepted.
     *
     * Anything queued while the send is in flight stays for the next flush. A failure
     * stops the flush and keeps the rest, and the exception reaches the caller.
     */
    suspend fun flush(account: String, transport: OutboxTransport) = flushLock.withLock {
        val batch = lock.withLock {
            val list = loaded()
            // Nothing is in flight while the flush lock is held, so this is the one
            // moment the log can safely be cut down to the last word on each file.
            // After that, the order between files no longer matters.
            val latest = latestPerFile(list)
            if (latest.size != list.size) {
                list.retainAll(latest.toSet())
                persist(list)
            }
            list.filter { it.account == account }.also { mine -> inFlight += mine.map { it.seq } }
        }
        if (batch.isEmpty()) return@withLock

        try {
            val (verdicts, withdrawals) = batch.partition { it.verdict != null }
            for (chunk in verdicts.chunked(BATCH)) {
                transport.record(chunk.map { PendingVerdict(it.fileId, it.verdict!!) })
                delivered(chunk)
            }
            for (op in withdrawals) {
                transport.withdraw(op.fileId)
                delivered(listOf(op))
            }
        } finally {
            lock.withLock { inFlight -= batch.map { it.seq }.toSet() }
        }
    }

    private suspend fun enqueue(account: String, fileId: Long, verdict: String?) = lock.withLock {
        val list = loaded()
        list.removeAll { it.account == account && it.fileId == fileId && it.seq !in inFlight }
        list += OutboxOp(nextSeq++, account, fileId, verdict)
        while (list.size > MAX_ENTRIES) list.removeAt(0)
        persist(list)
    }

    private suspend fun delivered(sent: List<OutboxOp>) = lock.withLock {
        val seqs = sent.map { it.seq }.toHashSet()
        val list = loaded()
        list.removeAll { it.seq in seqs }
        inFlight -= seqs
        persist(list)
    }

    private fun latestPerFile(list: List<OutboxOp>): List<OutboxOp> {
        val last = HashMap<Pair<String, Long>, Long>()
        list.forEach { last[it.account to it.fileId] = it.seq }
        return list.filter { last[it.account to it.fileId] == it.seq }
    }

    /** The in-memory log, read from disk the first time it is needed. Call under [lock]. */
    private suspend fun loaded(): MutableList<OutboxOp> {
        ops?.let { return it }
        val (list, migrated) = withContext(io) { read() }.let { raw ->
            if (raw == null) emptyList<OutboxOp>() to false else parse(raw)
        }
        val loaded = list.toMutableList()
        nextSeq = (loaded.maxOfOrNull { it.seq } ?: 0L) + 1
        ops = loaded
        if (migrated) persist(loaded)
        return loaded
    }

    private fun read(): String? = try {
        String(store.readFully(), Charsets.UTF_8)
    } catch (e: FileNotFoundException) {
        null
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the outbox", e)
        null
    }

    /** @return the log, and whether it came from the old format and needs rewriting */
    private suspend fun parse(raw: String): Pair<List<OutboxOp>, Boolean> = try {
        if (raw.trimStart().startsWith("[")) {
            // Written by 1.0.2 and earlier: a bare list of verdicts with no account.
            val legacy = json.decodeFromString<List<PendingVerdict>>(raw)
            val owner = legacyOwner()
            if (owner == null) {
                // Nobody is signed in to have given them, and sending them to whoever
                // signs in next could delete an unrelated photo that shares an id.
                Log.w(TAG, "Dropping ${legacy.size} queued verdicts with no account to send them to")
                emptyList<OutboxOp>() to true
            } else {
                legacy.mapIndexed { i, v -> OutboxOp(i + 1L, owner, v.fileId, v.verdict) } to true
            }
        } else {
            json.decodeFromString<OutboxFile>(raw).ops to false
        }
    } catch (e: Exception) {
        // A damaged file is not worth crashing over, and its contents are
        // recoverable by reviewing the month again.
        Log.w(TAG, "Discarding an unreadable outbox", e)
        emptyList<OutboxOp>() to true
    }

    private suspend fun persist(list: List<OutboxOp>) {
        val snapshot = list.toList()
        withContext(io) {
            if (snapshot.isEmpty()) {
                store.delete()
                return@withContext
            }
            val stream = try {
                store.startWrite()
            } catch (e: Exception) {
                Log.w(TAG, "Could not write the outbox", e)
                return@withContext
            }
            try {
                stream.write(json.encodeToString(OutboxFile.serializer(), OutboxFile(ops = snapshot)).toByteArray())
                store.finishWrite(stream)
            } catch (e: Exception) {
                store.failWrite(stream)
                Log.w(TAG, "Could not write the outbox", e)
            }
        }
    }
}
