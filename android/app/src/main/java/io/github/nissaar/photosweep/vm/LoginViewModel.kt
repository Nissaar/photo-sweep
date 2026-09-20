package io.github.nissaar.photosweep.vm

import android.util.Log
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
import io.github.nissaar.photosweep.data.Account

data class LoginState(
    val serverUrl: String = "",
    val starting: Boolean = false,
    /** True once the browser is open and we are waiting for the user to come back. */
    val waiting: Boolean = false,
    val openUrl: String? = null,
    val error: String? = null,
    val signedIn: Boolean = false,
)

/**
 * Drives Login Flow v2.
 *
 * The app has no login form on purpose. Signing in happens on the user's own
 * Nextcloud page, in a browser, which is the only way an app can honour whatever
 * two-factor or single sign-on that server uses — and it means a password is never
 * typed into this app at all.
 */
class LoginViewModel : ViewModel() {

    private companion object {
        const val TAG = "LoginViewModel"
    }

    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun setServerUrl(value: String) {
        _state.value = _state.value.copy(serverUrl = value, error = null)
    }

    fun start() {
        if (_state.value.starting || _state.value.waiting) return

        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            _state.value = _state.value.copy(starting = true, error = null)
            try {
                val started = Graph.loginFlow.start(_state.value.serverUrl)
                _state.value = _state.value.copy(
                    starting = false,
                    waiting = true,
                    openUrl = started.login,
                )

                val result = Graph.loginFlow.awaitLogin(started.poll)
                Graph.accounts.save(
                    Account(
                        server = result.server.trimEnd('/'),
                        loginName = result.loginName,
                        appPassword = result.appPassword,
                    ),
                )
                _state.value = _state.value.copy(waiting = false, signedIn = true)
            } catch (e: LoginException) {
                _state.value = _state.value.copy(starting = false, waiting = false, error = e.message)
            } catch (e: CancellationException) {
                // Cancelling is not a failure, and it arrives here as an ordinary
                // exception. Swallowing it below turned every cancelled sign-in into
                // a connection error, and broke structured concurrency besides.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sign-in failed", e)
                _state.value = _state.value.copy(
                    starting = false,
                    waiting = false,
                    error = "Could not reach that server. Check the address and your connection.",
                )
            }
        }
    }

    /** The browser has been handed the URL; do not open it again on recomposition. */
    fun urlOpened() {
        _state.value = _state.value.copy(openUrl = null)
    }

    fun cancel() {
        pollJob?.cancel()
        _state.value = _state.value.copy(starting = false, waiting = false, openUrl = null)
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
