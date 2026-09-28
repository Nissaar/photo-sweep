package io.github.nissaar.photosweep.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.nissaar.photosweep.api.PendingVerdict
import io.github.nissaar.photosweep.api.Verdict
import java.io.File

/**
 * The outbox is what lets swiping stay instant and survive going offline, so its
 * edges — replacing a verdict, losing the process, a failed send, and above all a
 * verdict changed while an earlier one is on the wire — are worth pinning down.
 */
class VerdictOutboxTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val me = "jo@https://cloud.example.com"
    private val someoneElse = "sam@https://other.example.com"

    private fun outbox(name: String = "outbox.json", owner: String? = me): Pair<VerdictOutbox, File> {
        val file = File(folder.root, name)
        return VerdictOutbox(file, legacyOwner = { owner }) to file
    }

    /**
     * Plays the server: holds the verdict for each file, the way the decisions table
     * does, and can be made to stop mid-request so a test can act while a send is in
     * flight.
     */
    private class FakeServer : OutboxTransport {
        val verdicts = HashMap<Long, String>()
        val requests = mutableListOf<List<PendingVerdict>>()
        var failNext = false
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()

        override suspend fun record(verdicts: List<PendingVerdict>) {
            if (failNext) {
                failNext = false
                throw java.io.IOException("offline")
            }
            requests += verdicts
            entered.complete(Unit)
            gate?.await()
            verdicts.forEach { this.verdicts[it.fileId] = it.verdict }
        }

        override suspend fun withdraw(fileId: Long) {
            verdicts.remove(fileId)
        }
    }

    @Test
    fun `keeps verdicts in the order they were given`() = runTest {
        val (box, _) = outbox()
        box.record(me, 1, Verdict.KEEP)
        box.record(me, 2, Verdict.DELETE)
        box.record(me, 3, Verdict.KEEP)

        assertEquals(listOf(1L, 2L, 3L), box.pending(me).map { it.fileId })
    }

    @Test
    fun `changing your mind replaces the earlier verdict`() = runTest {
        val (box, _) = outbox()
        box.record(me, 1, Verdict.DELETE)
        box.record(me, 1, Verdict.KEEP)

        // Sending both would tell the server two contradictory things about one file.
        assertEquals(1, box.size(me))
        assertEquals(Verdict.KEEP, box.pending(me).single().verdict)
    }

    @Test
    fun `undo is queued as a withdrawal`() = runTest {
        val (box, _) = outbox()
        box.record(me, 1, Verdict.DELETE)
        box.withdraw(me, 1)

        val op = box.pending(me).single()
        assertEquals(1L, op.fileId)
        assertNull(op.verdict)
    }

    @Test
    fun `survives being reopened`() = runTest {
        val file = File(folder.root, "persist.json")
        VerdictOutbox(file, legacyOwner = { me }).record(me, 7, Verdict.DELETE)

        // A process killed between swiping and syncing must not lose the verdicts.
        val reopened = VerdictOutbox(file, legacyOwner = { me })
        assertEquals(listOf(7L), reopened.pending(me).map { it.fileId })

        // And the log goes on numbering after what it read, so a new entry is not
        // mistaken for an old one.
        reopened.record(me, 8, Verdict.KEEP)
        assertEquals(listOf(7L, 8L), reopened.pending(me).map { it.fileId })
    }

    @Test
    fun `reads the queue an older version wrote, for the signed-in account`() = runTest {
        val file = File(folder.root, "legacy.json")
        file.writeText("""[{"fileId":1,"verdict":"delete"},{"fileId":2,"verdict":"keep"}]""")

        val box = VerdictOutbox(file, legacyOwner = { me })

        assertEquals(
            listOf(1L to Verdict.DELETE, 2L to Verdict.KEEP),
            box.pending(me).map { it.fileId to it.verdict },
        )
        // Rewritten in the new format straight away, so it is only migrated once.
        assertTrue(file.readText().trimStart().startsWith("{"))
        assertEquals(2, VerdictOutbox(file, legacyOwner = { null }).size(me))
    }

    @Test
    fun `drops an old queue when nobody is signed in to own it`() = runTest {
        val file = File(folder.root, "orphan.json")
        file.writeText("""[{"fileId":1,"verdict":"delete"}]""")

        val box = VerdictOutbox(file, legacyOwner = { null })

        // Sending it to whoever signs in next could delete an unrelated photo that
        // happens to have the same id on their server.
        assertEquals(0, box.size(me))
        box.record(someoneElse, 5, Verdict.KEEP)
        assertEquals(0, box.size(me))
    }

    @Test
    fun `only sends what belongs to the account flushing`() = runTest {
        val (box, _) = outbox()
        box.record(me, 1, Verdict.DELETE)
        box.record(someoneElse, 1, Verdict.DELETE)

        val server = FakeServer()
        box.flush(me, server)

        assertEquals(listOf(listOf(PendingVerdict(1, Verdict.DELETE))), server.requests)
        assertEquals(0, box.size(me))
        assertEquals(1, box.size(someoneElse))
    }

    @Test
    fun `clears only what was sent`() = runTest {
        val (box, _) = outbox()
        box.record(me, 1, Verdict.KEEP)
        box.record(me, 2, Verdict.KEEP)

        var sent: List<PendingVerdict>? = null
        box.flush(
            me,
            object : OutboxTransport {
                override suspend fun record(verdicts: List<PendingVerdict>) {
                    sent = verdicts
                    // Arrives while the send is in flight. It was never transmitted,
                    // so it must still be queued afterwards.
                    box.record(me, 3, Verdict.DELETE)
                }

                override suspend fun withdraw(fileId: Long) = Unit
            },
        )

        assertEquals(listOf(1L, 2L), sent?.map { it.fileId })
        assertEquals(listOf(3L), box.pending(me).map { it.fileId })
    }

    /**
     * The scenario that lost verdicts: delete X, and while that is on the wire undo
     * it and keep X instead. Removing by file id after the send threw the keep away
     * with the delete, and the server was left holding the delete.
     */
    @Test
    fun `a verdict changed while the old one is in flight still reaches the server`() = runTest {
        val (box, _) = outbox()
        val server = FakeServer().apply { gate = CompletableDeferred() }

        box.record(me, 42, Verdict.DELETE)
        val flight = async { box.flush(me, server) }
        server.entered.await()

        box.withdraw(me, 42)
        box.record(me, 42, Verdict.KEEP)

        server.gate!!.complete(Unit)
        flight.await()

        // The delete arrived and was removed; the keep is still waiting.
        assertEquals(Verdict.DELETE, server.verdicts[42])
        assertEquals(Verdict.KEEP, box.pending(me).single().verdict)

        server.gate = null
        box.flush(me, server)

        assertEquals(Verdict.KEEP, server.verdicts[42])
        assertEquals(0, box.size(me))
    }

    @Test
    fun `an undo while the verdict is in flight withdraws it afterwards`() = runTest {
        val (box, _) = outbox()
        val server = FakeServer().apply { gate = CompletableDeferred() }

        box.record(me, 42, Verdict.DELETE)
        val flight = async { box.flush(me, server) }
        server.entered.await()

        // "Still queued" used to be taken as "never sent", so this undo stayed local
        // and the delete it was meant to cancel went through.
        box.withdraw(me, 42)

        server.gate!!.complete(Unit)
        flight.await()
        server.gate = null
        box.flush(me, server)

        assertFalse(server.verdicts.containsKey(42))
        assertEquals(0, box.size(me))
    }

    @Test
    fun `overlapping flushes send each verdict once`() = runTest {
        val (box, _) = outbox()
        val server = FakeServer().apply { gate = CompletableDeferred() }
        box.record(me, 1, Verdict.DELETE)
        box.record(me, 2, Verdict.KEEP)

        val first = async { box.flush(me, server) }
        server.entered.await()
        val second = async { box.flush(me, server) }

        server.gate!!.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, server.requests.size)
        assertEquals(0, box.size(me))
    }

    @Test
    fun `a withdrawal queued after a failed send is still sent after it`() = runTest {
        val (box, _) = outbox()
        val server = FakeServer()

        box.record(me, 9, Verdict.DELETE)
        server.failNext = true
        runCatching { box.flush(me, server) }
        box.withdraw(me, 9)
        box.record(me, 9, Verdict.KEEP)
        box.flush(me, server)

        assertEquals(Verdict.KEEP, server.verdicts[9])
    }

    @Test
    fun `a failed send keeps everything queued`() = runTest {
        val (box, _) = outbox()
        box.record(me, 1, Verdict.KEEP)

        val failed = runCatching {
            box.flush(me, FakeServer().apply { failNext = true })
        }

        assertTrue(failed.isFailure)
        assertEquals(1, box.size(me))
    }

    @Test
    fun `an empty queue does not call the sender`() = runTest {
        val (box, _) = outbox()
        val server = FakeServer()
        box.flush(me, server)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `a corrupted file is discarded rather than crashing`() = runTest {
        val file = File(folder.root, "broken.json")
        file.writeText("{\"version\":2,\"ops\":[{\"seq\":1,\"acc")

        val box = VerdictOutbox(file, legacyOwner = { me })

        // Half a file is not worth taking the app down over; the verdicts can be
        // given again by reviewing the month.
        assertEquals(0, box.size(me))
        box.record(me, 9, Verdict.KEEP)
        assertEquals(listOf(9L), box.pending(me).map { it.fileId })
    }

    @Test
    fun `emptying the queue removes the file`() = runTest {
        val (box, file) = outbox()
        box.record(me, 1, Verdict.KEEP)
        assertTrue(file.exists())

        box.flush(me, FakeServer())

        assertFalse(file.exists())
    }
}
