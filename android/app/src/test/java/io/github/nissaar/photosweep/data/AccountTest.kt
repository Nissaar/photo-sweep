package io.github.nissaar.photosweep.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which URLs get the app password, and which user a video is fetched as. */
class AccountTest {

    private val account = Account("https://cloud.example.com/nc", "jo@example.com", "pw")

    @Test
    fun `owns its own server's URLs`() {
        assertTrue(account.owns("https://cloud.example.com/nc/index.php/core/preview?fileId=1".toHttpUrl()))
        assertTrue(account.owns("https://cloud.example.com/nc".toHttpUrl()))
    }

    /** A string prefix match gave the password to every one of these. */
    @Test
    fun `does not own lookalikes`() {
        assertFalse(account.owns("https://cloud.example.com.evil.net/nc/index.php".toHttpUrl()))
        assertFalse(account.owns("https://cloud.example.com/nc-other/index.php".toHttpUrl()))
        assertFalse(account.owns("https://cloud.example.com/index.php".toHttpUrl()))
        assertFalse(account.owns("https://cloud.example.com:8443/nc/index.php".toHttpUrl()))
        assertFalse(account.owns("http://cloud.example.com/nc/index.php".toHttpUrl()))
    }

    @Test
    fun `videos use the user id once it is known`() {
        assertEquals(
            "https://cloud.example.com/nc/remote.php/dav/files/jo%40example.com/Photos/My%20clip.mp4",
            account.fileUrl("/Photos/My clip.mp4"),
        )
        assertEquals(
            "https://cloud.example.com/nc/remote.php/dav/files/u-1234/Photos/My%20clip.mp4",
            account.copy(userId = "u-1234").fileUrl("/Photos/My clip.mp4"),
        )
    }

    @Test
    fun `the key does not change when the user id arrives`() {
        assertEquals(account.key, account.copy(userId = "u-1234").key)
    }
}
