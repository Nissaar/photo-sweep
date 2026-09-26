package io.github.nissaar.photosweep.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import io.github.nissaar.photosweep.data.Account
import java.io.IOException
import java.net.URLEncoder

/**
 * The Photo Sweep server app's API.
 *
 * Every call carries the account's app password as Basic auth and the
 * `OCS-APIRequest` header, which is what tells Nextcloud this is an API call rather
 * than a browser and exempts it from the CSRF check.
 */
class PhotoSweepApi(
    private val client: OkHttpClient,
    private val accountProvider: () -> Account?,
    /**
     * Told when the server rejects an account's password, so the app signs out in one
     * place rather than every screen deciding for itself.
     */
    private val onUnauthorised: (Account) -> Unit = {},
) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // --- reads -------------------------------------------------------------

    suspend fun status(): StatusResponse = get("index")

    suspend fun months(): MonthsResponse = get("months")

    suspend fun month(yearMonth: String, skipDecided: Boolean? = null): MonthResponse {
        val query = skipDecided?.let { "?skipDecided=${if (it) 1 else 0}" } ?: ""
        return get("months/${encode(yearMonth)}$query")
    }

    suspend fun pending(): DecisionsResponse = get("decisions/pending")

    suspend fun applied(): DecisionsResponse = get("decisions/applied")

    // --- writes ------------------------------------------------------------

    /** Advances the server's index by one bounded chunk. */
    suspend fun scan(full: Boolean = false): ScanResponse =
        post("index", buildJsonObject { put("full", full) })

    /**
     * Sends a batch of verdicts in one request.
     *
     * This is what the outbox uses. A review session with no signal produces a hundred
     * verdicts, and they should reach the server in one round trip when it comes back,
     * not a hundred.
     */
    suspend fun recordMany(verdicts: List<PendingVerdict>): RecordBatchResult =
        post(
            "decisions",
            buildJsonObject {
                put(
                    "verdicts",
                    buildJsonArray {
                        verdicts.forEach { verdict ->
                            add(
                                JsonObject(
                                    mapOf(
                                        "fileId" to JsonPrimitive(verdict.fileId),
                                        "verdict" to JsonPrimitive(verdict.verdict),
                                    ),
                                ),
                            )
                        }
                    },
                )
            },
        )

    suspend fun undo(fileId: Long): JsonObject = delete("decisions/$fileId")

    suspend fun resetMonth(yearMonth: String): ClearedResult = delete("months/${encode(yearMonth)}")

    /**
     * The only call that changes files.
     *
     * @param fileIds exactly the photos the user saw and confirmed. The server acts on
     *   nothing else, so a verdict that arrived from the web UI in the meantime is not
     *   swept up with them.
     * @param permanent the user has been told the server has no trash and agreed to a
     *   permanent delete. Without it the server refuses rather than deleting for good.
     */
    suspend fun apply(fileIds: List<Long>, permanent: Boolean = false): ApplyResult =
        post(
            "apply",
            buildJsonObject {
                put("fileIds", buildJsonArray { fileIds.forEach { add(JsonPrimitive(it)) } })
                put("permanent", permanent)
            },
        )

    suspend fun restore(fileIds: List<Long>): RestoreResult =
        post(
            "restore",
            buildJsonObject {
                put("fileIds", buildJsonArray { fileIds.forEach { add(JsonPrimitive(it)) } })
            },
        )

    suspend fun updateConfig(patch: JsonObject): ServerConfig = put("config", patch)

    // --- Nextcloud core ----------------------------------------------------

    /**
     * The account's user id, which WebDAV paths are built from.
     *
     * Takes the account explicitly because it is asked for during sign-in, before the
     * account has been stored.
     */
    suspend fun userId(account: Account): String {
        val user: CloudUser = call(
            account,
            request(account, "${account.server}/ocs/v2.php/cloud/user").get().build(),
        )
        return user.id
    }

    /**
     * Revokes this device's app password on the server, for an explicit sign-out.
     *
     * Deleting it locally alone leaves a working credential in the server's session
     * list until someone remembers to remove it by hand.
     */
    suspend fun revokeAppPassword(account: Account) {
        call<JsonElement>(
            account,
            request(account, "${account.server}/ocs/v2.php/core/apppassword").delete().build(),
        )
    }

    // --- plumbing ----------------------------------------------------------

    private suspend inline fun <reified T> get(path: String): T =
        appCall(path) { it.get() }

    private suspend inline fun <reified T> post(path: String, body: JsonObject): T =
        appCall(path) { it.post(body.toString().toRequestBody(jsonMedia)) }

    private suspend inline fun <reified T> put(path: String, body: JsonObject): T =
        appCall(path) { it.put(body.toString().toRequestBody(jsonMedia)) }

    private suspend inline fun <reified T> delete(path: String): T =
        appCall(path) { it.delete() }

    private suspend inline fun <reified T> appCall(
        path: String,
        method: (Request.Builder) -> Request.Builder,
    ): T {
        val account = accountProvider() ?: throw NotSignedInException()
        val url = "${account.server}/ocs/v2.php/apps/photosweep/api/v1/$path"
        return call(account, method(request(account, url)).build())
    }

    private fun request(account: Account, url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", account.basicAuthHeader())
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .header("User-Agent", LoginFlow.USER_AGENT)

    private suspend inline fun <reified T> call(account: Account, request: Request): T {
        val response = client.newCall(request).await()
        val body = withContext(Dispatchers.IO) {
            response.use { it.body?.string().orEmpty() }
        }

        // An expired or revoked app password is the one failure worth naming
        // exactly, because the fix is to sign in again rather than to retry.
        if (response.code == 401) {
            onUnauthorised(account)
            throw NotSignedInException()
        }

        val data = readOcs(json, response.code, body)
        return try {
            json.decodeFromJsonElement<T>(data)
        } catch (e: SerializationException) {
            throw ApiException(UNREADABLE, response.code)
        } catch (e: IllegalArgumentException) {
            throw ApiException(UNREADABLE, response.code)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

private const val UNREADABLE = "Could not read the server's reply. Is the Photo Sweep app enabled?"

/**
 * Unwraps an OCS reply, or throws what the server said went wrong.
 *
 * OCS explains a failure in `meta.message`, and this app's own endpoints add a stable
 * code in `data.error`. Both arrive with an error status and a body that does not
 * match the success shape, so reading them has to happen before the body is decoded
 * as the expected type. Doing it the other way round is what turned every refusal
 * into a bare "HTTP 400".
 */
internal fun readOcs(json: Json, httpCode: Int, body: String): JsonElement {
    if (body.isBlank()) {
        throw ApiException("The server returned nothing (HTTP $httpCode)", httpCode)
    }

    val envelope = try {
        json.decodeFromString<OcsEnvelope<JsonElement>>(body)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
    val successful = httpCode in 200..299

    if (envelope == null) {
        throw ApiException(
            if (successful) UNREADABLE else "The server returned HTTP $httpCode",
            httpCode,
        )
    }

    // OCS can also report a failure inside the envelope with HTTP 200 underneath, so
    // the HTTP status alone is not enough to tell whether the call worked.
    val meta = envelope.ocs.meta
    val okStatus = meta.statuscode == 100 || meta.statuscode == 200
    if (successful && okStatus) return envelope.ocs.data

    val status = if (meta.statuscode != 0) meta.statuscode else httpCode
    val code = (envelope.ocs.data as? JsonObject)?.get("error")
        ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    val message = meta.message?.takeIf { it.isNotBlank() }
        ?: "The server refused that request (HTTP $status)"
    throw ApiException(message, status, code)
}

/**
 * A failure the server explained.
 *
 * @param status the OCS status code, or the HTTP one when there was no envelope
 * @param code the app's stable error code from `data.error`, when it sent one
 */
open class ApiException(
    message: String,
    val status: Int = 0,
    val code: String? = null,
) : IOException(message)

class NotSignedInException : ApiException("You are signed out. Sign in again to carry on.", 401)

/** The stable codes the server puts in `data.error`. */
object ApiError {
    /** Trash mode, no trash app, and the request did not agree to a permanent delete. */
    const val TRASH_UNAVAILABLE = "trash_unavailable"

    /** Another apply for the same user is still running. */
    const val APPLY_RUNNING = "apply_running"
}
