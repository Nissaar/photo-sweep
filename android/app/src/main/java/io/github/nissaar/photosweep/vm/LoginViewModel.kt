package io.github.nissaar.photosweep.vm

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.github.nissaar.photosweep.Graph
import io.github.nissaar.photosweep.api.LoginException
import io.github.nissaar.photosweep.api.LoginFlow
import io.github.nissaar.photosweep.api.LoginPoll
import io.github.nissaar.photosweep.api.PendingLogin
import io.github.nissaar.photosweep.api.describeConnectionFailure
import io.github.nissaar.photosweep.data.Account
import java.io.IOException

data class LoginState(
    val serverUrl: String = "",
    val starting: Boolean = false,
    /** True once the browser is open and we are waiting for the user to come back. */
    val waiting: Boolean = false,
    val openUrl: String? = null,
    val error: String? = null,
)

/**
 * Drives Login Flow v2.
 *
 * The app has no login form on purpose. Signing in happens on the user's own
 * Nextcloud page, in a browser, which is the only way an app can honour whatever
 * two-factor or single sign-on that server uses — and it means a password is never
 * typed into this app at all.
 *
 * Success is reported to the account store and nowhere else. The screen switches
 * because the store says someone is signed in, which is also what makes signing in a
 * second time after a sign-out work.
 *
 * The flow being waited on is kept in [SavedStateHandle]. Android often kills an app
 * in the background while its user is signing in in a browser, most of all on phones
 * short of memory, and without it the sign-in they finished was lost along with the
 * app password the server had just issued for it.
 */
class LoginViewModel(private val saved: SavedStateHandle) : ViewModel() {

    private companion object {
        const val TAG = "LoginViewModel"

        const val KEY_SERVER_URL = "server_url"
        const val KEY_BASE = "pending_base"
        const val KEY_TOKEN = "pending_token"
        const val KEY_ENDPOINT = "pending_endpoint"
        const val KEY_LOGIN = "pending_login"
        const val KEY_DEADLINE = "pending_deadline"
    }

    private val _state = MutableStateFlow(LoginState(serverUrl = saved[KEY_SERVER_URL] ?: ""))
    val state: StateFlow<LoginState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        restorePending()?.let { (pending, deadline) ->
            _state.value = _state.value.copy(waiting = true)
            pollJob = viewModelScope.launch { await(pending, deadline) }
        }
    }

    fun setServerUrl(value: String) {
        saved[KEY_SERVER_URL] = value
        _state.value = _state.value.copy(serverUrl = value, error = null)
    }

    fun start() {
        if (_state.value.starting || _state.value.waiting) return

        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            _state.value = _state.value.copy(starting = true, error = null)
            try {
                val pending = Graph.loginFlow.start(_state.value.serverUrl)
                val deadline = System.currentTimeMillis() + LoginFlow.POLL_TIMEOUT_MS
                savePending(pending, deadline)
                _state.value = _state.value.copy(
                    starting = false,
                    waiting = true,
                    openUrl = pending.login,
                )
                await(pending, deadline)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private suspend fun await(pending: PendingLogin, deadline: Long) {
        try {
            val result = Graph.loginFlow.awaitLogin(pending, deadline)
            clearPending()
            val account = Account(
                server = result.server,
                loginName = result.loginName,
                appPassword = result.appPassword,
            )
            // Asked for now so videos work from the start. Not worth failing a
            // sign-in over: the app asks again the next time it reaches the server.
            val userId = try {
                Graph.api.userId(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            Graph.accounts.signIn(account.copy(userId = userId))
            _state.value = _state.value.copy(waiting = false, openUrl = null)
        } catch (e: CancellationException) {
            // Cancelling is not a failure, and it arrives here as an ordinary
            // exception. Swallowing it below turned every cancelled sign-in into
            // a connection error, and broke structured concurrency besides.
            throw e
        } catch (e: Exception) {
            clearPending()
            fail(e)
        }
    }

    private fun fail(e: Exception) {
        val message = when (e) {
            is LoginException -> e.message
            is IOException -> describeConnectionFailure(e)
            else -> "Could not reach that server. Check the address and your connection."
        }
        if (e !is LoginException) Log.w(TAG, "Sign-in failed", e)
        _state.value = _state.value.copy(starting = false, waiting = false, openUrl = null, error = message)
    }

    /** The browser has been handed the URL; do not open it again on recomposition. */
    fun urlOpened() {
        _state.value = _state.value.copy(openUrl = null)
    }

    /**
     * No browser could be opened. The flow is still valid, so keep waiting and let
     * the user open the address themselves.
     */
    fun browserUnavailable(url: String) {
        _state.value = _state.value.copy(
            openUrl = null,
            error = "No browser could be opened for the sign-in page. Open this address in one yourself: $url",
        )
    }

    fun cancel() {
        pollJob?.cancel()
        clearPending()
        _state.value = _state.value.copy(starting = false, waiting = false, openUrl = null)
    }

    private fun savePending(pending: PendingLogin, deadline: Long) {
        saved[KEY_BASE] = pending.base
        saved[KEY_TOKEN] = pending.poll.token
        saved[KEY_ENDPOINT] = pending.poll.endpoint
        saved[KEY_LOGIN] = pending.login
        saved[KEY_DEADLINE] = deadline
    }

    private fun restorePending(): Pair<PendingLogin, Long>? {
        val base = saved.get<String>(KEY_BASE) ?: return null
        val token = saved.get<String>(KEY_TOKEN) ?: return null
        val endpoint = saved.get<String>(KEY_ENDPOINT) ?: return null
        val login = saved.get<String>(KEY_LOGIN) ?: return null
        val deadline = saved.get<Long>(KEY_DEADLINE) ?: return null
        if (deadline <= System.currentTimeMillis()) {
            clearPending()
            return null
        }
        return PendingLogin(base, LoginPoll(token, endpoint), login) to deadline
    }

    private fun clearPending() {
        listOf(KEY_BASE, KEY_TOKEN, KEY_ENDPOINT, KEY_LOGIN, KEY_DEADLINE).forEach { saved.remove<Any>(it) }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
