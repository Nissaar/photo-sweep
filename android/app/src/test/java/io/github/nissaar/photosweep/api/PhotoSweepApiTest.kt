package io.github.nissaar.photosweep.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.nissaar.photosweep.data.Account

/**
 * What the app shows when the server says no. The server explains itself in
 * `meta.message` and gives a stable code in `data.error`, and both used to be thrown
 * away in favour of "HTTP 400".
 */
class PhotoSweepApiTest {

    private lateinit var server: MockWebServer
    private lateinit var account: Account
    private val rejected = mutableListOf<Account>()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        account = Account(server.url("/").toString().trimEnd('/'), "jo", "secret")
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun api() = PhotoSweepApi(OkHttpClient(), { account }, { rejected += it })

    private fun ocsFailure(status: Int, message: String, data: String = "[]") =
        """{"ocs":{"meta":{"status":"failure","statuscode":$status,"message":"$message"},"data":$data}}"""

    private fun ocsOk(data: String) =
        """{"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},"data":$data}}"""

    private suspend fun failureOf(block: suspend () -> Unit): ApiException {
        try {
            block()
        } catch (e: ApiException) {
            return e
        }
        throw AssertionError("expected an ApiException")
    }

    @Test
    fun `reads the server's message and code from a refusal`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                ocsFailure(409, "The trash is not available", """{"error":"trash_unavailable"}"""),
            ),
        )

        val e = failureOf { api().apply(listOf(1, 2)) }

        assertEquals("The trash is not available", e.message)
        assertEquals(409, e.status)
        assertEquals(ApiError.TRASH_UNAVAILABLE, e.code)
    }

    @Test
    fun `a refusal without a code still carries its message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody(ocsFailure(400, "Expected a month like 2024-07")))

        val e = failureOf { api().month("nonsense") }

        assertEquals("Expected a month like 2024-07", e.message)
        assertEquals(400, e.status)
        assertNull(e.code)
    }

    @Test
    fun `a failure reported inside a 200 is still a failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(ocsFailure(998, "Not found")))

        val e = failureOf { api().months() }

        assertEquals("Not found", e.message)
        assertEquals(998, e.status)
    }

    @Test
    fun `an error page that is not OCS reports the HTTP status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))

        val e = failureOf { api().status() }

        assertEquals("The server returned HTTP 502", e.message)
        assertEquals(502, e.status)
    }

    @Test
    fun `an unreadable success says the app may not be enabled`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>Welcome</html>"))

        val e = failureOf { api().status() }

        assertTrue(e.message!!.contains("Photo Sweep app enabled"))
    }

    @Test
    fun `401 signs the account out once, through the store`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody(ocsFailure(997, "Unauthorised")))

        val e = failureOf { api().months() }

        assertTrue(e is NotSignedInException)
        assertEquals(listOf(account), rejected)
    }

    @Test
    fun `apply sends exactly the confirmed photos`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(ocsOk("""{"mode":"trash","succeeded":2}""")))

        val result = api().apply(listOf(5, 7), permanent = true)

        assertEquals(2, result.succeeded)
        val request = server.takeRequest()
        assertEquals("/ocs/v2.php/apps/photosweep/api/v1/apply", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(listOf(5L, 7L), body["fileIds"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals("true", body["permanent"]!!.jsonPrimitive.content)
    }

    @Test
    fun `fetches the user id from Nextcloud core`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(ocsOk("""{"id":"u-1234","display-name":"Jo"}""")))

        assertEquals("u-1234", api().userId(account))
        assertEquals("/ocs/v2.php/cloud/user", server.takeRequest().path)
    }

    @Test
    fun `revoking the app password deletes it on the server`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(ocsOk("[]")))

        api().revokeAppPassword(account)

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/ocs/v2.php/core/apppassword", request.path)
        assertEquals("true", request.getHeader("OCS-APIRequest"))
    }

    @Test
    fun `no account means signed out, without a request`() = runTest {
        val api = PhotoSweepApi(OkHttpClient(), { null })

        val e = failureOf { api.months() }

        assertSame(NotSignedInException::class.java, e.javaClass)
        assertEquals(0, server.requestCount)
    }
}
