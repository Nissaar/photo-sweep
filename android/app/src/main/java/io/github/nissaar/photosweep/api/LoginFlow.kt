package io.github.nissaar.photosweep.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException

@Serializable
data class LoginPoll(val token: String, val endpoint: String)

@Serializable
data class LoginStart(val poll: LoginPoll, val login: String)

@Serializable
data class LoginResult(val server: String, val loginName: String, val appPassword: String)

/**
 * A sign-in that has been started and is waiting for the user.
 *
 * @param base the server as the user typed it, normalised; what the server's own
 *   answers are checked against
 */
data class PendingLogin(val base: String, val poll: LoginPoll, val login: String)

/**
 * Nextcloud's Login Flow v2.
 *
 * The app never sees the user's password. It asks the server to start a flow, opens
 * the resulting URL in a browser where the person signs in on their own Nextcloud —
 * with whatever two-factor or single sign-on their server uses, none of which an
 * in-app form could handle — and then polls for the app password that comes out.
 *
 * What is stored afterwards is that app password, which is revocable from Nextcloud's
 * own security settings without touching the account.
 */
class LoginFlow(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /** Overridden in tests, which cannot wait two seconds between polls. */
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) {

    companion object {
        /** Nextcloud shows this in the session list, so it should say what it is. */
        const val USER_AGENT = "Photo Sweep (Android)"

        /** The server drops an unclaimed flow after 20 minutes. */
        const val POLL_TIMEOUT_MS = 20 * 60 * 1000L
        private const val POLL_INTERVAL_MS = 2_000L
    }

    /**
     * Opens a flow and returns the URL the user has to visit.
     *
     * @param serverUrl what the user typed, in any of the forms people type it
     */
    suspend fun start(serverUrl: String): PendingLogin {
        val base = normaliseServerUrl(serverUrl)
        val request = Request.Builder()
            .url("$base/index.php/login/v2")
            .header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().build())
            .build()

        val response = client.newCall(request).await()
        val body = withContext(Dispatchers.IO) { response.use { it.body?.string().orEmpty() } }
        if (!response.isSuccessful) {
            throw LoginException(describeStartFailure(response.code))
        }
        val started = try {
            json.decodeFromString<LoginStart>(body)
        } catch (e: SerializationException) {
            throw LoginException("That address does not look like a Nextcloud server.")
        } catch (e: IllegalArgumentException) {
            throw LoginException("That address does not look like a Nextcloud server.")
        }
        return checkLoginStart(base, started)
    }

    /**
     * Waits for the user to finish signing in.
     *
     * The endpoint answers 404 until they do, which is the documented "not yet" and
     * not an error, so only anything else stops the wait.
     *
     * @param deadline wall-clock time to give up at. Passed in rather than worked out
     *   here, so a wait resumed after the process was killed keeps the flow's real
     *   expiry instead of starting a fresh twenty minutes.
     */
    suspend fun awaitLogin(
        pending: PendingLogin,
        deadline: Long = System.currentTimeMillis() + POLL_TIMEOUT_MS,
    ): LoginResult {
        while (System.currentTimeMillis() < deadline) {
            val request = Request.Builder()
                .url(pending.poll.endpoint)
                .header("User-Agent", USER_AGENT)
                .post(FormBody.Builder().add("token", pending.poll.token).build())
                .build()

            val result = try {
                val response = client.newCall(request).await()
                val body = withContext(Dispatchers.IO) { response.use { it.body?.string().orEmpty() } }
                when {
                    response.isSuccessful -> json.decodeFromString<LoginResult>(body)
                    // Still waiting for the person to get through their login page.
                    response.code == 404 -> null
                    else -> throw LoginException("The server rejected the sign-in (HTTP ${response.code})")
                }
            } catch (e: SSLException) {
                // A certificate problem does not fix itself while the user waits.
                // Retrying it for twenty minutes only left them watching a spinner.
                throw LoginException(describeConnectionFailure(e))
            } catch (e: UnknownServiceException) {
                // Cleartext refused by the network security config: permanent too.
                throw LoginException(describeConnectionFailure(e))
            } catch (e: IOException) {
                // Keep waiting. This loop runs for twenty minutes, every two seconds,
                // while the user is off in a browser — which is exactly when the phone
                // is most likely to drop to mobile data, doze the radio, or have the
                // proxy close the idle connection this call would have reused. Letting
                // one such failure out ended the whole sign-in with "could not reach
                // that server", seconds after the browser had said it worked.
                //
                // An unknown host is kept in this group on purpose. The host already
                // answered when the flow started, so failing to resolve it now means
                // the phone has no network for a moment, not a wrong address.
                null
            } catch (e: SerializationException) {
                // A half-read body is the same kind of accident as a dropped socket:
                // the connection broke, it just broke late enough to return bytes.
                null
            }
            if (result != null) return checkLoginResult(pending.base, result)

            delay(pollIntervalMs)
        }

        throw LoginException("Sign-in timed out. Start again when you are ready.")
    }

    private fun describeStartFailure(code: Int): String = when (code) {
        404 -> "That address does not look like a Nextcloud server."
        else -> "The server refused to start a sign-in (HTTP $code)."
    }
}

class LoginException(message: String) : Exception(message)

private const val HTTPS_REQUIRED =
    "Photo Sweep only connects over HTTPS, so your app password is never sent in clear text. " +
        "Use your server's https:// address."

/** What to tell the user when the connection itself failed. */
fun describeConnectionFailure(e: IOException): String = when (e) {
    is UnknownHostException -> "Could not find a server at that address. Check it for typos."
    is SSLException -> "The server's security certificate could not be verified, so the app " +
        "will not send it your sign-in."
    is UnknownServiceException -> HTTPS_REQUIRED
    else -> "Could not reach that server. Check the address and your connection."
}

/**
 * Checks what the server said to use against what the user typed.
 *
 * The poll endpoint and login page come from the server's own configuration, and a
 * proxy in front of it can rewrite them to `http://` or an internal host. Following
 * those meant a twenty-minute spinner, or — for an internal host that resolves on
 * some networks — sending the poll token somewhere the user never chose.
 */
internal fun checkLoginStart(base: String, started: LoginStart): PendingLogin {
    val expected = requireNotNull(base.toHttpUrlOrNull())
    val endpoint = checkSameServer(started.poll.endpoint, expected)
    val login = started.login.toHttpUrlOrNull()
    if (login == null || !login.isHttps) {
        throw LoginException("The server's sign-in page is not an HTTPS address. " + ADMIN_HINT)
    }
    return PendingLogin(base, started.poll.copy(endpoint = endpoint.toString()), started.login)
}

/** The same check for the server address the sign-in hands back with the password. */
internal fun checkLoginResult(base: String, result: LoginResult): LoginResult {
    val expected = requireNotNull(base.toHttpUrlOrNull())
    val server = checkSameServer(result.server, expected)
    return result.copy(server = server.toString().trimEnd('/'))
}

private const val ADMIN_HINT =
    "Its overwrite.cli.url, overwritehost or overwriteprotocol setting may be wrong."

private fun checkSameServer(url: String, expected: HttpUrl): HttpUrl {
    val parsed = url.trim().toHttpUrlOrNull()
        ?: throw LoginException("The server answered with an address the app could not read. $ADMIN_HINT")
    if (!parsed.isHttps) {
        throw LoginException("The server answered with an address that is not HTTPS. $ADMIN_HINT")
    }
    if (parsed.host != expected.host) {
        throw LoginException(
            "The server answered with an address on ${parsed.host}, not ${expected.host}. $ADMIN_HINT",
        )
    }
    return parsed
}

/**
 * Where a Nextcloud install's own routes start, below which a pasted address is cut.
 * `apps` is only a route when an app id follows it; see [normaliseServerUrl].
 */
private val ROUTE_SEGMENTS = setOf("index.php", "remote.php", "ocs", "login", "settings")

/** App ids commonly seen in an address bar, which mark `/apps/<id>` as a route. */
private val KNOWN_APPS = setOf(
    "activity", "bookmarks", "calendar", "contacts", "dashboard", "deck", "files",
    "files_sharing", "files_trashbin", "forms", "gallery", "mail", "memories", "music",
    "news", "notes", "photos", "photosweep", "recognize", "settings", "spreed", "tasks",
)

/**
 * Turns what a person types into a usable base URL.
 *
 * People type "cloud.example.com", "https://cloud.example.com/", and sometimes the
 * full "https://cloud.example.com/index.php/apps/files" they copied from the address
 * bar. All three mean the same server.
 *
 * The address is parsed rather than searched as text, and only whole path segments
 * are cut. Searching for "/login" in the string also matched the "//login" of
 * `https://login.example.com` and reduced it to "https:".
 *
 * HTTPS is assumed when no scheme is given, and required when one is. The app refuses
 * cleartext traffic, so an http:// address could only ever fail — and it failed as an
 * unhelpful "could not reach that server".
 */
fun normaliseServerUrl(input: String): String {
    val typed = input.trim()
    if (typed.isEmpty()) throw LoginException("Enter your Nextcloud address")

    val withScheme = when {
        typed.startsWith("https://", ignoreCase = true) -> typed
        typed.startsWith("http://", ignoreCase = true) -> throw LoginException(HTTPS_REQUIRED)
        typed.contains("://") -> throw LoginException(HTTPS_REQUIRED)
        else -> "https://$typed"
    }
    val url = withScheme.toHttpUrlOrNull()
        ?: throw LoginException("That does not look like a web address.")

    val segments = url.pathSegments.filter { it.isNotEmpty() }
    val root = segments.indices.firstOrNull { i ->
        val segment = segments[i].lowercase()
        segment in ROUTE_SEGMENTS ||
            (segment == "apps" && (i == segments.lastIndex || segments[i + 1].lowercase() in KNOWN_APPS))
    } ?: segments.size

    val base = url.newBuilder()
        .username("")
        .password("")
        .query(null)
        .fragment(null)
        .encodedPath("/")
        .apply { segments.take(root).forEach { addPathSegment(it) } }
        .build()
    return base.toString().trimEnd('/')
}
