package io.github.nissaar.photosweep.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import io.github.nissaar.photosweep.Graph
import io.github.nissaar.photosweep.api.NotSignedInException
import io.github.nissaar.photosweep.api.ScanState
import io.github.nissaar.photosweep.api.ServerConfig
import io.github.nissaar.photosweep.api.Summary
import io.github.nissaar.photosweep.data.AccountState

data class AppState(
    val loading: Boolean = true,
    val summary: Summary = Summary(),
    val scan: ScanState = ScanState(),
    val config: ServerConfig = ServerConfig(),
    val trashAvailable: Boolean = true,
    val queued: Int = 0,
    val error: String? = null,
) {
    val serverName: String
        get() = Graph.accounts.current()?.server?.removePrefix("https://")?.removePrefix("http://") ?: ""
}

/**
 * Session-wide state: how the server's index is doing, for whoever is signed in.
 *
 * Who is signed in is not decided here. It comes from
 * [io.github.nissaar.photosweep.data.AccountStore], and this starts afresh whenever
 * that changes, so nothing from one account is shown to the next.
 */
class AppViewModel : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            Graph.accounts.state
                .map { (it as? AccountState.SignedIn)?.account?.key }
                .distinctUntilChanged()
                .collect { key ->
                    scanJob?.cancel()
                    scanJob = null
                    _state.value = AppState(loading = key != null)
                    if (key != null) refresh()
                }
        }
    }

    /** Signing out because the user asked; see [io.github.nissaar.photosweep.data.Session]. */
    fun signOut() {
        Graph.session.signOut()
    }

    fun refresh() {
        val key = Graph.accounts.current()?.key ?: return
        viewModelScope.launch {
            try {
                // Anything given while offline goes first, so the numbers below it
                // describe the same reality the user has been working in.
                val drained = runCatching { Graph.repository.flushOutbox() }.getOrDefault(false)
                val status = Graph.repository.status()
                // An answer about an account that has since been signed out of.
                if (Graph.accounts.current()?.key != key) return@launch
                _state.value = _state.value.copy(
                    loading = false,
                    summary = status.summary,
                    scan = status.scan,
                    config = status.config,
                    trashAvailable = status.trashAvailable,
                    queued = if (drained) 0 else Graph.repository.queuedCount(),
                    error = null,
                )

                fetchUserIdIfMissing()

                // Nothing indexed and no scan finished means a first run. Start one
                // without being asked: an empty grid with a button on it is a worse
                // first impression than months appearing as they are found.
                if (!status.scan.complete && !status.scan.running) startScan(full = false)
            } catch (e: NotSignedInException) {
                // The account store has already signed out; the screen follows it.
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        }
    }

    /**
     * Accounts stored before the app asked for the user id have none, and videos need
     * it. The server has just answered, so this is a good moment to ask.
     */
    private suspend fun fetchUserIdIfMissing() {
        val account = Graph.accounts.current() ?: return
        if (account.userId != null) return
        val userId = runCatching { Graph.api.userId(account) }.getOrNull() ?: return
        Graph.accounts.update(account.copy(userId = userId))
    }

    /**
     * Runs the server's index to completion, one bounded chunk per request.
     *
     * The server caps how much it does per call so the request returns inside the web
     * server's timeout; going again while it reports incomplete is what turns a long
     * first scan into visible progress rather than a failure.
     */
    fun startScan(full: Boolean = false) {
        if (scanJob?.isActive == true) return

        scanJob = viewModelScope.launch {
            try {
                var guard = 0
                do {
                    val result = Graph.repository.scan(full && guard == 0)
                    _state.value = _state.value.copy(scan = result.scan, summary = result.summary)
                    if (result.scan.error != null) break
                    guard++
                } while (!_state.value.scan.complete && guard < 500)
            } catch (e: NotSignedInException) {
                // Followed through the account store.
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }

    fun setMode(mode: String) = updateConfig { Graph.repository.setMode(mode) }

    fun setTargetFolder(path: String) = updateConfig { Graph.repository.setTargetFolder(path) }

    fun setSkipDecided(value: Boolean) = updateConfig { Graph.repository.setSkipDecided(value) }

    private fun updateConfig(block: suspend () -> ServerConfig) {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(config = block(), error = null)
            } catch (e: NotSignedInException) {
                // Followed through the account store.
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
