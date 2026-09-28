package io.github.nissaar.photosweep.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Signing out because the user asked to.
 *
 * Different from the server rejecting the password, which only has to forget the
 * credential: here the person is handing the phone on, or done with the account, so
 * what it left behind goes too. Previews of their photos are cleared from memory and
 * disk, and the app password is revoked on the server rather than left working in its
 * session list. Screens reset by themselves, because they are keyed to the account.
 *
 * Queued verdicts are kept. They are tagged with their account and only ever sent for
 * it, so they wait for the same person to sign in again rather than being lost.
 *
 * @param scope outlives any screen, so leaving the settings page does not cancel the
 *   revocation halfway
 */
class Session(
    private val accounts: AccountStore,
    private val revoke: suspend (Account) -> Unit,
    private val clearCaches: suspend () -> Unit,
    private val scope: CoroutineScope,
) {

    private companion object {
        const val TAG = "Session"
    }

    fun signOut(): Job = scope.launch {
        val account = accounts.current() ?: return@launch

        // The screens change first, so nothing waits on the network to leave.
        accounts.signOut()

        try {
            clearCaches()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear the image caches", e)
        }

        // Best effort. Offline, or a server that already revoked it, is not a reason
        // to keep someone signed in; the settings page tells them how to revoke it by
        // hand if this does not get through.
        try {
            revoke(account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not revoke the app password on the server", e)
        }
    }
}
