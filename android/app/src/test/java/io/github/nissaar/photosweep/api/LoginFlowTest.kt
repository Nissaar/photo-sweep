package io.github.nissaar.photosweep.api

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    private fun poll() = PendingLogin(
        base = "https://cloud.example.com",
        poll = LoginPoll(token = "tok", endpoint = server.url("/poll").toString()),
        login = "https://cloud.example.com/login/v2/flow/abc",
    )

    private val success = """{"server":"https://cloud.example.com","loginName":"jo","appPassword":"secret"}"""

    /**
     * Driven by a counter rather than a fixed queue. A dropped connection can cost
     * more than one attempt depending on how the client pools sockets, and a queue
     * would then hand the next test response to a retry and fail for the wrong
     * reason — which it duly did, on CI only.
     */
    private fun failFirst(failures: Int) {
        var seen = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen++
                return when {
                    seen <= failures -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                    else -> MockResponse().setResponseCode(200).setBody(success)
                }
            }
        }
    }

    @Test
    fun `keeps polling after a dropped connection`() = runTest {
        failFirst(1)

        val result = flow().awaitLogin(poll())

        assertEquals("jo", result.loginName)
        assertEquals("secret", result.appPassword)
    }

    @Test
    fun `survives a run of dropped connections`() = runTest {
        failFirst(5)

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

    /**
     * A certificate the phone does not trust will not start being trusted while the
     * user waits, so retrying it for twenty minutes only showed a spinner.
     */
    @Test
    fun `gives up at once on a certificate problem`() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw javax.net.ssl.SSLHandshakeException("untrusted") }
            .build()

        val thrown = assertThrows(LoginException::class.java) {
            kotlinx.coroutines.runBlocking { LoginFlow(client, pollIntervalMs = 1).awaitLogin(poll()) }
        }
        assertTrue(thrown.message!!.contains("certificate"))
    }

    @Test
    fun `gives up when the deadline has passed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        assertThrows(LoginException::class.java) {
            kotlinx.coroutines.runBlocking {
                flow().awaitLogin(poll(), deadline = System.currentTimeMillis() - 1)
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refuses a returned server that is not the one typed`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"server":"https://nextcloud.internal","loginName":"jo","appPassword":"secret"}"""),
        )

        val thrown = assertThrows(LoginException::class.java) {
            kotlinx.coroutines.runBlocking { flow().awaitLogin(poll()) }
        }
        assertTrue(thrown.message!!.contains("nextcloud.internal"))
    }

    @Test
    fun `accepts a returned server in a subdirectory of the typed host`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"server":"https://cloud.example.com/nc/","loginName":"jo","appPassword":"secret"}"""),
        )

        assertEquals("https://cloud.example.com/nc", flow().awaitLogin(poll()).server)
    }

    @Test
    fun `the poll endpoint must be https on the typed host`() {
        val base = "https://cloud.example.com"
        val login = "https://cloud.example.com/login/v2/flow/abc"

        val ok = checkLoginStart(base, LoginStart(LoginPoll("t", "https://cloud.example.com/login/v2/poll"), login))
        assertEquals("https://cloud.example.com/login/v2/poll", ok.poll.endpoint)

        // A proxy rewriting the scheme: the app refuses cleartext, so this would spin.
        assertThrows(LoginException::class.java) {
            checkLoginStart(base, LoginStart(LoginPoll("t", "http://cloud.example.com/login/v2/poll"), login))
        }
        // An internal host: the poll token would go somewhere the user never chose.
        assertThrows(LoginException::class.java) {
            checkLoginStart(base, LoginStart(LoginPoll("t", "https://10.0.0.5/login/v2/poll"), login))
        }
    }

    @Test
    fun `the login page must be https`() {
        assertThrows(LoginException::class.java) {
            checkLoginStart(
                "https://cloud.example.com",
                LoginStart(LoginPoll("t", "https://cloud.example.com/login/v2/poll"), "http://cloud.example.com/login"),
            )
        }
        assertThrows(LoginException::class.java) {
            checkLoginStart(
                "https://cloud.example.com",
                LoginStart(LoginPoll("t", "https://cloud.example.com/login/v2/poll"), "javascript:alert(1)"),
            )
        }
    }
}
