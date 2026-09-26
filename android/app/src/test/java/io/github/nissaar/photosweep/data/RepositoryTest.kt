package io.github.nissaar.photosweep.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.github.nissaar.photosweep.api.MediaItem
import io.github.nissaar.photosweep.api.PhotoSweepApi
import io.github.nissaar.photosweep.api.Verdict
import java.io.File

/**
 * The outbox as the screens use it, against a server that answers like the real one.
 */
class RepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var account: Account
    private var online = true
    private val pendingOnServer = mutableListOf<Long>()

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!online) return MockResponse().setResponseCode(503).setBody("down")
                val path = request.path.orEmpty().substringAfter("/api/v1/")
                return when {
                    request.method == "POST" && path == "decisions" ->
                        ok("""{"recorded":1,"skipped":[]}""")
                    // Nothing pending for that file: the server's 404.
                    request.method == "DELETE" && path.startsWith("decisions/") ->
                        MockResponse().setResponseCode(404).setBody(
                            """{"ocs":{"meta":{"status":"failure","statuscode":404,"message":"Nothing pending to undo for that file"},"data":[]}}""",
                        )
                    path == "decisions/pending" -> ok(
                        """{"decisions":[${pendingOnServer.joinToString(",") { """{"fileId":$it,"verdict":"delete"}""" }}]}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        account = Account(server.url("/").toString().trimEnd('/'), "jo", "secret")
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun ok(data: String) = MockResponse().setResponseCode(200)
        .setBody("""{"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},"data":$data}}""")

    private fun repository(): Repository {
        val api = PhotoSweepApi(OkHttpClient(), { account })
        val outbox = VerdictOutbox(File(folder.root, "outbox.json"), legacyOwner = { account.key })
        return Repository(api, outbox) { account.key }
    }

    private fun item(id: Long) = MediaItem(fileId = id, takenAt = 0, yearMonth = "2024-07")

    @Test
    fun `undoing a verdict the server never heard of counts as done`() = runTest {
        val repo = repository()
        online = false
        repo.record(item(1), Verdict.DELETE)

        online = true
        assertTrue(repo.undo(1))
        assertEquals(0, repo.queuedCount())
    }

    /**
     * "Keep this one after all" while offline. The tile has to stay gone on the next
     * reload, and the next apply must not be able to delete the photo.
     */
    @Test
    fun `a photo kept while offline stays off the list until the server hears`() = runTest {
        val repo = repository()
        pendingOnServer += listOf(1L, 2L)

        online = false
        assertFalse(repo.undo(1))
        online = true

        // The server still lists it, because the withdrawal has not been sent.
        assertEquals(listOf(2L), repo.pending().decisions.map { it.fileId })
        assertEquals(1, repo.queuedCount())
    }

    @Test
    fun `nothing reaches the server for another account`() = runTest {
        val repo = repository()
        online = false
        repo.record(item(1), Verdict.DELETE)
        online = true

        val jo = account
        account = account.copy(loginName = "sam")
        val before = server.requestCount
        assertTrue(repo.flushOutbox())
        assertEquals(before, server.requestCount)

        // Still waiting for jo.
        account = jo
        assertEquals(1, repo.queuedCount())
    }
}
