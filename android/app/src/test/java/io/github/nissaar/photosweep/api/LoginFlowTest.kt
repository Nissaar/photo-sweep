package io.github.nissaar.photosweep.api

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * The poll runs every couple of seconds for twenty minutes while the user is off in a
 * browser, so it will meet a dropped connection sooner or later — a phone that moves
 * to mobile data, a dozing radio, a proxy closing the socket this call would reuse.
 * Treating one of those as the end of the sign-in is what made logging in fail at
 * random, seconds after the browser had said it worked.
 */
class LoginFlowTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun flow() = LoginFlow(
        client = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
        pollIntervalMs = 1,
    )

    private fun poll() = LoginPoll(token = "tok", endpoint = server.url("/poll").toString())

    private val success = """{"server":"https://cloud.example.com","loginName":"jo","appPassword":"secret"}"""

    @Test
    fun `keeps polling after a dropped connection`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200).setBody(success))

        val result = flow().awaitLogin(poll())

        assertEquals("jo", result.loginName)
        assertEquals("secret", result.appPassword)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `survives a run of dropped connections`() = runTest {
        repeat(5) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        server.enqueue(MockResponse().setResponseCode(200).setBody(success))

        assertEquals("jo", flow().awaitLogin(poll()).loginName)
    }

    /**
     * A refusal is not a blip: the server answered, and repeating the question for
     * twenty minutes would only leave the user staring at a spinner.
     */
    @Test
    fun `gives up when the server actually refuses`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val thrown = assertThrows(LoginException::class.java) {
            kotlinx.coroutines.runBlocking { flow().awaitLogin(poll()) }
        }
        assertEquals(true, thrown.message?.contains("500"))
    }

    @Test
    fun `404 means the user has not finished signing in yet`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setBody(success))

        assertEquals("jo", flow().awaitLogin(poll()).loginName)
        assertEquals(3, server.requestCount)
    }
}
