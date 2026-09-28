package io.github.nissaar.photosweep.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address field is the first thing anyone touches, and people type every one of
 * these forms.
 */
class ServerUrlTest {

    @Test
    fun `assumes https when no scheme is given`() {
        assertEquals("https://cloud.example.com", normaliseServerUrl("cloud.example.com"))
    }

    @Test
    fun `refuses http with a message that says why`() {
        // The app blocks cleartext, so this could only ever fail later as "could not
        // reach that server". Saying so up front is the useful answer.
        val thrown = assertThrows(LoginException::class.java) {
            normaliseServerUrl("http://192.168.1.10:8080")
        }
        assertTrue(thrown.message!!.contains("HTTPS"))
        assertThrows(LoginException::class.java) { normaliseServerUrl("HTTP://cloud.example.com") }
        assertThrows(LoginException::class.java) { normaliseServerUrl("ftp://cloud.example.com") }
    }

    @Test
    fun `strips trailing slashes`() {
        assertEquals("https://cloud.example.com", normaliseServerUrl("https://cloud.example.com/"))
        assertEquals("https://cloud.example.com", normaliseServerUrl("https://cloud.example.com///"))
    }

    @Test
    fun `strips a pasted app path`() {
        assertEquals(
            "https://cloud.example.com",
            normaliseServerUrl("https://cloud.example.com/index.php/apps/files"),
        )
        assertEquals(
            "https://cloud.example.com",
            normaliseServerUrl("https://cloud.example.com/apps/photos/"),
        )
        assertEquals(
            "https://cloud.example.com",
            normaliseServerUrl("https://cloud.example.com/settings/user"),
        )
        assertEquals(
            "https://cloud.example.com",
            normaliseServerUrl("https://cloud.example.com/apps/files/?dir=/Photos&fileid=12#top"),
        )
    }

    /**
     * The bug this replaced: searching the string for "/login" also matched the
     * "//login" after the scheme, and the address came out as "https:".
     */
    @Test
    fun `a host that starts like a route is left alone`() {
        assertEquals("https://login.example.com", normaliseServerUrl("login.example.com"))
        assertEquals("https://login.example.com", normaliseServerUrl("https://login.example.com/login"))
        assertEquals("https://settings.example.com", normaliseServerUrl("settings.example.com"))
        assertEquals("https://apps.example.com", normaliseServerUrl("https://apps.example.com/apps/files"))
    }

    @Test
    fun `keeps a subdirectory install intact`() {
        // Nextcloud is often served from a subdirectory, and cutting that off would
        // point every request at the wrong host root.
        assertEquals(
            "https://example.com/nextcloud",
            normaliseServerUrl("https://example.com/nextcloud/"),
        )
        assertEquals(
            "https://example.com/nextcloud",
            normaliseServerUrl("https://example.com/nextcloud/index.php/apps/files"),
        )
        assertEquals(
            "https://example.com/nextcloud",
            normaliseServerUrl("https://example.com/nextcloud/login"),
        )
    }

    @Test
    fun `a subdirectory named apps is not mistaken for a route`() {
        assertEquals(
            "https://example.com/apps/nextcloud",
            normaliseServerUrl("https://example.com/apps/nextcloud"),
        )
        assertEquals(
            "https://example.com/apps/nextcloud",
            normaliseServerUrl("https://example.com/apps/nextcloud/apps/photos"),
        )
    }

    @Test
    fun `keeps a port and drops credentials typed into the address`() {
        assertEquals("https://cloud.example.com:8443", normaliseServerUrl("cloud.example.com:8443/index.php"))
        assertEquals("https://cloud.example.com", normaliseServerUrl("https://jo:secret@cloud.example.com/"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("https://cloud.example.com", normaliseServerUrl("  cloud.example.com  "))
    }

    @Test
    fun `refuses an empty or unreadable address`() {
        assertThrows(LoginException::class.java) { normaliseServerUrl("   ") }
        assertThrows(LoginException::class.java) { normaliseServerUrl("https://") }
        assertThrows(LoginException::class.java) { normaliseServerUrl("cloud example com") }
    }
}
