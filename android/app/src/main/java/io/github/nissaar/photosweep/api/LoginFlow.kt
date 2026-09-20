package io.github.nissaar.photosweep.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class LoginPoll(val token: String, val endpoint: String)

@Serializable
data class LoginStart(val poll: LoginPoll, val login: String)

@Serializable
data class LoginResult(val server: String, val loginName: String, val appPassword: String)

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
        private const val POLL_TIMEOUT_MS = 20 * 60 * 1000L
        private const val POLL_INTERVAL_MS = 2_000L
    }

    /**
     * Opens a flow and returns the URL the user has to visit.
     *
     * @param serverUrl what the user typed, in any of the forms people type it
     */
    suspend fun start(serverUrl: String): LoginStart = withContext(Dispatchers.IO) {
        val base = normaliseServerUrl(serverUrl)
        val request = Request.Builder()
            .url("$base/index.php/login/v2")
            .header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().build())
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw LoginException(describeStartFailure(response.code))
            }
            json.decodeFromString<LoginStart>(body)
        }
    }

    /**
     * Waits for the user to finish signing in.
     *
     * The endpoint answers 404 until they do, which is the documented "not yet" and
     * not an error, so only anything else stops the wait.
     */
    suspend fun awaitLogin(poll: LoginPoll): LoginResult = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            val request = Request.Builder()
                .url(poll.endpoint)
                .header("User-Agent", USER_AGENT)
                .post(FormBody.Builder().add("token", poll.token).build())
                .build()

            val result = try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful ->
                            json.decodeFromString<LoginResult>(response.body?.string().orEmpty())
                        // Still waiting for the person to get through their login page.
                        response.code == 404 -> null
                        else -> throw LoginException("The server rejected the sign-in (HTTP ${response.code})")
                    }
                }
            } catch (e: IOException) {
                // Keep waiting. This loop runs for twenty minutes, every two seconds,
                // while the user is off in a browser — which is exactly when the phone
                // is most likely to drop to mobile data, doze the radio, or have the
                // proxy close the idle connection this call would have reused. Letting
                // one such failure out ended the whole sign-in with "could not reach
                // that server", seconds after the browser had said it worked.
                null
            } catch (e: SerializationException) {
                // A half-read body is the same kind of accident as a dropped socket:
                // the connection broke, it just broke late enough to return bytes.
                null
            }
            if (result != null) return@withContext result

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

/**
 * Turns what a person types into a usable base URL.
 *
 * People type "cloud.example.com", "https://cloud.example.com/", and sometimes the
 * full "https://cloud.example.com/index.php/apps/files" they copied from the address
 * bar. All three mean the same server.
 *
 * HTTPS is assumed when no scheme is given: guessing http would silently send an app
 * password in clear text, and a server that genuinely has no TLS is rare enough to be
 * worth typing out.
 */
fun normaliseServerUrl(input: String): String {
    var url = input.trim()
    if (url.isEmpty()) throw LoginException("Enter your Nextcloud address")

    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        url = "https://$url"
    }
    url = url.trimEnd('/')

    // Strip anything below the Nextcloud root that a pasted address might carry.
    for (marker in listOf("/index.php", "/apps/", "/settings/", "/login")) {
        val at = url.indexOf(marker)
        if (at > 0) {
            url = url.substring(0, at)
            break
        }
    }

    return url.trimEnd('/')
}
