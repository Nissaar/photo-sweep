package io.github.nissaar.photosweep.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
import io.github.nissaar.photosweep.api.ApiException
import io.github.nissaar.photosweep.api.PhotoSweepApi
import java.io.File

/**
 * Applying a long list: sent a few at a time, so no single request can time out.
 */
class ApplyBatchesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var account: Account

    /** The ids in each apply request, in order. */
    private val batches = mutableListOf<List<Long>>()

    /** Given the request number (from 1), the response to send instead of success. */
    private var override: (Int) -> MockResponse? = { null }

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val ids = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    .getValue("fileIds").jsonArray.map { it.jsonPrimitive.long }
                batches += ids
                return override(batches.size) ?: ok(ids.size)
            }
        }
        server.start()
        account = Account(server.url("/").toString().trimEnd('/'), "jo", "secret")
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun ok(succeeded: Int) = MockResponse().setResponseCode(200).setBody(
        """{"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},"data":""" +
            """{"mode":"trash","succeeded":$succeeded,"failed":0,"failures":[],"targetFolder":null,"error":null}}}""",
    )

    private fun failure(status: Int, error: String?) = MockResponse().setResponseCode(status).setBody(
        """{"ocs":{"meta":{"status":"failure","statuscode":$status,"message":"no"},"data":""" +
            (if (error == null) "[]" else """{"error":"$error"}""") + "}}",
    )

    private fun repository(): Repository {
        val api = PhotoSweepApi(OkHttpClient(), { account })
        val outbox = VerdictOutbox(File(folder.root, "outbox.json"), legacyOwner = { account.key })
        return Repository(api, outbox) { account.key }
    }

    @Test
    fun `two thousand photos go out in batches of 25`() = runTest {
        val ids = (1L..2000L).toList()
        val progress = mutableListOf<Int>()

        val result = repository().apply(ids, permanent = false) { done, _ -> progress += done }

        assertEquals(2000, result.succeeded)
        assertFalse(result.interrupted)
        assertEquals(80, batches.size)
        assertTrue(batches.all { it.size <= Repository.APPLY_BATCH_SIZE })
        assertEquals(ids, batches.flatten())
        assertEquals(2000, progress.last())
    }

    @Test
    fun `a batch told a deletion is running is sent again`() = runTest {
        override = { n -> if (n == 2) failure(409, "apply_running") else null }

        val result = repository().apply((1L..50L).toList(), permanent = false)

        assertEquals(50, result.succeeded)
        assertFalse(result.interrupted)
        assertEquals(listOf(25, 25, 25), batches.map { it.size })
        assertEquals(batches[1], batches[2])
    }

    @Test
    fun `a refusal on the first batch is thrown with nothing done`() = runTest {
        override = { failure(409, "trash_unavailable") }

        val e = try {
            repository().apply((1L..60L).toList(), permanent = false)
            null
        } catch (e: ApiException) {
            e
        }

        assertEquals("trash_unavailable", e?.code)
        assertEquals(1, batches.size)
    }

    @Test
    fun `a failure after the first batch keeps what was done`() = runTest {
        override = { n -> if (n >= 2) failure(403, null) else null }

        val result = repository().apply((1L..75L).toList(), permanent = false)

        assertTrue(result.interrupted)
        assertEquals(25, result.succeeded)
        assertEquals(2, batches.size)
    }
}
