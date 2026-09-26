package io.github.nissaar.photosweep.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signing in, out and in again.
 *
 * Every screen follows the account store, so the cycle is tested there. The bug it
 * replaced: the login screen kept its own "signed in" flag, which never went back to
 * false, so signing in again after a sign-out left the app on the login screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {

    private class MemoryStorage(var stored: Account? = null) : AccountStorage {
        override val isPersistent = true
        var clears = 0

        override fun load(): Account? = stored

        override fun save(account: Account) {
            stored = account
        }

        override fun clear() {
            clears++
            stored = null
        }
    }

    private val jo = Account("https://cloud.example.com", "jo", "pw-1")
    private val sam = Account("https://other.example.com", "sam", "pw-2")

    private fun TestScope.store(storage: AccountStorage): AccountStore {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return AccountStore(storage, this, dispatcher)
    }

    @Test
    fun `starts loading, then reports what was stored`() = runTest {
        val accounts = store(MemoryStorage(stored = jo))
        assertEquals(AccountState.Loading, accounts.state.value)

        advanceUntilIdle()

        assertEquals(AccountState.SignedIn(jo), accounts.state.value)
        assertEquals(jo, accounts.awaitLoaded())
    }

    @Test
    fun `sign in, sign out, sign in again`() = runTest {
        val storage = MemoryStorage()
        val accounts = store(storage)
        advanceUntilIdle()
        assertEquals(AccountState.SignedOut, accounts.state.value)

        accounts.signIn(jo)
        assertEquals(AccountState.SignedIn(jo), accounts.state.value)
        assertEquals(jo, storage.stored)

        accounts.signOut()
        assertEquals(AccountState.SignedOut, accounts.state.value)
        assertNull(storage.stored)

        accounts.signIn(sam)
        assertEquals(AccountState.SignedIn(sam), accounts.state.value)
        assertEquals(sam, accounts.current())
    }

    @Test
    fun `a rejected password signs that account out`() = runTest {
        val storage = MemoryStorage()
        val accounts = store(storage)
        advanceUntilIdle()
        accounts.signIn(jo)

        accounts.rejected(jo)

        assertEquals(AccountState.SignedOut, accounts.state.value)
        assertNull(storage.stored)
    }

    @Test
    fun `a late rejection for the previous account leaves the new one alone`() = runTest {
        val storage = MemoryStorage()
        val accounts = store(storage)
        advanceUntilIdle()
        accounts.signIn(jo)
        accounts.signOut()
        accounts.signIn(sam)
        val clearsBefore = storage.clears

        // A request sent for jo before the switch comes back 401 now.
        accounts.rejected(jo)

        assertEquals(AccountState.SignedIn(sam), accounts.state.value)
        assertEquals(clearsBefore, storage.clears)
    }

    @Test
    fun `an update after the fact keeps the account signed in`() = runTest {
        val storage = MemoryStorage()
        val accounts = store(storage)
        advanceUntilIdle()
        accounts.signIn(jo)

        accounts.update(jo.copy(userId = "u-1"))
        assertEquals("u-1", accounts.current()?.userId)
        assertEquals("u-1", storage.stored?.userId)

        // Ignored once someone else is signed in.
        accounts.signOut()
        accounts.signIn(sam)
        accounts.update(jo.copy(userId = "u-2"))
        assertEquals(sam, accounts.current())
    }

    @Test
    fun `explicit sign-out clears caches and revokes the password`() = runTest {
        val accounts = store(MemoryStorage())
        advanceUntilIdle()
        accounts.signIn(jo)

        val revoked = mutableListOf<Account>()
        var cachesCleared = false
        val session = Session(accounts, revoke = { revoked += it }, clearCaches = { cachesCleared = true }, scope = this)

        session.signOut().join()

        assertEquals(AccountState.SignedOut, accounts.state.value)
        assertTrue(cachesCleared)
        assertEquals(listOf(jo), revoked)
    }

    @Test
    fun `sign-out goes through even when the server cannot be reached`() = runTest {
        val accounts = store(MemoryStorage())
        advanceUntilIdle()
        accounts.signIn(jo)

        val session = Session(
            accounts,
            revoke = { throw java.io.IOException("offline") },
            clearCaches = { throw IllegalStateException("disk") },
            scope = this,
        )

        session.signOut().join()

        assertEquals(AccountState.SignedOut, accounts.state.value)

        // And the next sign-in works.
        accounts.signIn(sam)
        assertEquals(AccountState.SignedIn(sam), accounts.state.value)
    }

    @Test
    fun `sign-out with nobody signed in does nothing`() = runTest {
        val accounts = store(MemoryStorage())
        advanceUntilIdle()
        var revoked = false
        Session(accounts, revoke = { revoked = true }, clearCaches = {}, scope = this).signOut().join()
        assertFalse(revoked)
    }
}
