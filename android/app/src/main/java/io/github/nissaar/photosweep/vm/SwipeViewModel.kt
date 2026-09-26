package io.github.nissaar.photosweep.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.github.nissaar.photosweep.Graph
import io.github.nissaar.photosweep.api.MediaItem
import io.github.nissaar.photosweep.api.Verdict

data class SwipeState(
    val month: String? = null,
    val items: List<MediaItem> = emptyList(),
    val index: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
    val kept: Int = 0,
    val deleted: Int = 0,
    /** Verdicts given this session, newest last — this is what undo walks back. */
    val history: List<Pair<MediaItem, String>> = emptyList(),
    /** Verdicts that have not reached the server yet. */
    val queued: Int = 0,
) {
    val current: MediaItem? get() = items.getOrNull(index)
    val next: MediaItem? get() = items.getOrNull(index + 1)
    val finished: Boolean get() = !loading && items.isNotEmpty() && index >= items.size
    val isEmpty: Boolean get() = !loading && items.isEmpty()
    val progress: Float
        get() = if (items.isEmpty()) 0f else (index.toFloat() / items.size).coerceIn(0f, 1f)
}

/**
 * The one-photo-at-a-time deck for a single month.
 *
 * Verdicts are queued locally and sent in the background. The deck never waits on the
 * network: that is what makes it possible to get through a thousand photos, and what
 * means going into a tunnel does not end the session.
 */
class SwipeViewModel : ViewModel() {

    private val _state = MutableStateFlow(SwipeState())
    val state: StateFlow<SwipeState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Deals the month's deck, every time it is opened.
     *
     * A deck kept from an earlier visit goes stale: reopened on the grid, reviewed on
     * the web, or simply finished, it went on saying "Month finished" or dealing
     * photos that already had a verdict.
     */
    fun load(month: String) {
        startLoad(month)
    }

    /** Forgets this month's verdicts and deals the deck again. Confirmed upstream. */
    fun reviewAgain() {
        val month = _state.value.month ?: return
        startLoad(month, resetFirst = true)
    }

    private fun startLoad(month: String, resetFirst: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = SwipeState(month = month, loading = true)
            try {
                if (resetFirst) Graph.repository.resetMonth(month)
                val response = Graph.repository.month(month, skipDecided = if (resetFirst) false else null)
                _state.value = _state.value.copy(items = response.items, loading = false, index = 0)
            } catch (e: CancellationException) {
                // Replaced by a newer load, which owns the state now.
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Could not open that month",
                )
            }
        }
    }

    fun keep() = decide(Verdict.KEEP)

    fun delete() = decide(Verdict.DELETE)

    private fun decide(verdict: String) {
        val snapshot = _state.value
        val item = snapshot.current ?: return

        // The UI advances now; delivery follows. The deck must never feel like it is
        // waiting on a server.
        _state.value = snapshot.copy(
            index = snapshot.index + 1,
            kept = snapshot.kept + if (verdict == Verdict.KEEP) 1 else 0,
            deleted = snapshot.deleted + if (verdict == Verdict.DELETE) 1 else 0,
            history = snapshot.history + (item to verdict),
        )

        viewModelScope.launch {
            runCatching { Graph.repository.record(item, verdict) }
            _state.value = _state.value.copy(queued = runCatching { Graph.repository.queuedCount() }.getOrDefault(0))
        }
    }

    /** Steps back one photo and withdraws the verdict that was given. */
    fun undo() {
        val snapshot = _state.value
        val (item, verdict) = snapshot.history.lastOrNull() ?: return

        _state.value = snapshot.copy(
            index = (snapshot.index - 1).coerceAtLeast(0),
            kept = (snapshot.kept - if (verdict == Verdict.KEEP) 1 else 0).coerceAtLeast(0),
            deleted = (snapshot.deleted - if (verdict == Verdict.DELETE) 1 else 0).coerceAtLeast(0),
            history = snapshot.history.dropLast(1),
        )

        viewModelScope.launch {
            runCatching { Graph.repository.undo(item.fileId) }
            _state.value = _state.value.copy(queued = runCatching { Graph.repository.queuedCount() }.getOrDefault(0))
        }
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}
