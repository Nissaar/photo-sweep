package io.github.nissaar.photosweep.data

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

/**
 * A signed-in Nextcloud account.
 *
 * [appPassword] is not the user's password: it is a per-device credential issued by
 * Login Flow v2, which the user can revoke from Nextcloud's own security settings
 * without changing anything else about their account.
 */
data class Account(
    val server: String,
    val loginName: String,
    val appPassword: String,
    /**
     * The account's user id, which is what WebDAV paths are built from.
     *
     * Not the same thing as [loginName] on servers that sign people in by email
     * address, LDAP or OpenID Connect. Null for an account stored before the app
     * started asking for it, until the next time the server can be reached.
     */
    val userId: String? = null,
) {
    /**
     * Identifies the account without its secret, for tagging work that belongs to it.
     *
     * Built from what the account is known by at sign-in, so it is the same before and
     * after [userId] has been fetched.
     */
    val key: String get() = "$loginName@$server"

    fun basicAuthHeader(): String = Credentials.basic(loginName, appPassword)

    /**
     * Whether [url] points at this account's server, and so may carry its password.
     *
     * Compared by scheme, host, port and whole path segments. A prefix match on the
     * string would also hand the password to `https://cloud.example.com.evil.net`.
     */
    fun owns(url: HttpUrl): Boolean {
        val base = server.toHttpUrlOrNull() ?: return false
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) return false
        val basePath = base.pathSegments.filter { it.isNotEmpty() }
        return url.pathSegments.take(basePath.size) == basePath
    }

    /**
     * A thumbnail, from Nextcloud core rather than from this app.
     *
     * `a=1` keeps the aspect ratio. Without it every portrait photo arrives cropped to
     * a square, which is exactly the framing that makes a keep-or-delete judgement
     * harder than it needs to be.
     */
    fun previewUrl(fileId: Long, size: Int): String =
        "$server/index.php/core/preview?fileId=$fileId&x=$size&y=$size&a=1"

    /**
     * The file itself, for playing a video.
     *
     * @param path relative to the user's files root
     */
    fun fileUrl(path: String): String {
        val encoded = path.trim('/')
            .split('/')
            .joinToString("/") { encodeSegment(it) }
        return "$server/remote.php/dav/files/${encodeSegment(userId ?: loginName)}/$encoded"
    }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
