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
import io.github.nissaar.photosweep.api.ApiError
import io.github.nissaar.photosweep.api.ApiException
import io.github.nissaar.photosweep.api.ApplyResult
import io.github.nissaar.photosweep.api.Decision
import io.github.nissaar.photosweep.api.NotSignedInException

data class ReviewState(
    val pending: List<Decision> = emptyList(),
    val applied: List<Decision> = emptyList(),
    val loading: Boolean = true,
    val applying: Boolean = false,
    val result: ApplyResult? = null,
    val error: String? = null,
    /** Verdicts still sitting in the outbox; the list below is incomplete while > 0. */
    val queued: Int = 0,
    /**
     * The photos the user confirmed, held while they are asked a second time: the
     * server has no trash, so going ahead deletes them permanently.
     */
    val confirmPermanent: List<Long>? = null,
) {
    val totalBytes: Long get() = pending.sumOf { it.size }
}

/**
 * The review-and-confirm screen.
 *
 * This is the only view model that can change files, and it does so exactly once, on
 * an explicit call from a confirmation dialog, for exactly the photos that dialog
 * listed.
 */
class ReviewViewModel : ViewModel() {

    private val _state = MutableStateFlow(ReviewState())
    val state: StateFlow<ReviewState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /**
     * Fetches the list again. Called every time the screen is shown: a list left over
     * from an earlier visit may be missing verdicts given since, on this phone or on
     * the web.
     */
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                // Drained first: the list would otherwise be missing exactly the
                // photos the user has just finished marking.
                runCatching { Graph.repository.flushOutbox() }
                _state.value = _state.value.copy(
                    pending = Graph.repository.pending().decisions,
                    applied = Graph.repository.applied().decisions,
                    queued = runCatching { Graph.repository.queuedCount() }.getOrDefault(0),
                    loading = false,
                    error = null,
                )
            } catch (e: NotSignedInException) {
                // Followed through the account store.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        }
    }

    /**
     * Takes one photo back out of the list, before anything happens to it.
     *
     * The withdrawal goes through the outbox like any verdict, so it holds while
     * offline: the tile goes now, and the server hears when it can. Apply drains the
     * outbox first, so a photo kept here can never be deleted by the next apply.
     */
    fun keepAfterAll(fileId: Long) {
        _state.value = _state.value.copy(pending = _state.value.pending.filterNot { it.fileId == fileId })
        viewModelScope.launch {
            runCatching { Graph.repository.undo(fileId) }
            _state.value = _state.value.copy(
                queued = runCatching { Graph.repository.queuedCount() }.getOrDefault(0),
            )
        }
    }

    /**
     * Carries out the deletes the user confirmed. Requires confirmation upstream.
     *
     * @param fileIds the photos the confirmation dialog was about. If draining the
     *   outbox or another client changed the list since, nothing is done: the new list
     *   is shown and the user is asked again, rather than deleting photos they never
     *   saw in the dialog.
     * @param permanent the user has agreed that, with no trash on the server, these are
     *   deleted for good
     */
    fun apply(fileIds: List<Long>, permanent: Boolean = false) {
        if (_state.value.applying) return

        viewModelScope.launch {
            _state.value = _state.value.copy(applying = true, result = null, error = null, confirmPermanent = null)
            try {
                if (!Graph.repository.flushOutbox()) {
                    _state.value = _state.value.copy(
                        applying = false,
                        queued = Graph.repository.queuedCount(),
                        error = "Some verdicts have not reached the server yet. Try again when you are connected.",
                    )
                    return@launch
                }

                val fresh = Graph.repository.pending().decisions
                if (fresh.map { it.fileId }.toSet() != fileIds.toSet()) {
                    _state.value = _state.value.copy(
                        applying = false,
                        pending = fresh,
                        queued = 0,
                        error = "The list changed since you confirmed. Check it and confirm again.",
                    )
                    return@launch
                }

                val result = Graph.repository.apply(fileIds, permanent)
                _state.value = _state.value.copy(applying = false, result = result)
                load()
            } catch (e: NotSignedInException) {
                _state.value = _state.value.copy(applying = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                when (e.code) {
                    ApiError.TRASH_UNAVAILABLE -> _state.value = _state.value.copy(
                        applying = false,
                        confirmPermanent = fileIds,
                    )
                    ApiError.APPLY_RUNNING -> {
                        _state.value = _state.value.copy(
                            applying = false,
                            error = "A deletion is already running for your account, from this " +
                                "phone or the web. Wait for it to finish, then check the list again.",
                        )
                        load()
                    }
                    else -> _state.value = _state.value.copy(applying = false, error = e.message)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(applying = false, error = e.message)
            }
        }
    }

    /** The user declined the permanent delete. */
    fun cancelPermanent() {
        _state.value = _state.value.copy(confirmPermanent = null)
    }

    fun restore(fileId: Long) {
        viewModelScope.launch {
            try {
                val result = Graph.repository.restore(listOf(fileId))
                if (result.restored == 0) {
                    _state.value = _state.value.copy(
                        error = result.failures.firstOrNull()?.reason ?: "Could not bring that back",
                    )
                }
                load()
            } catch (e: NotSignedInException) {
                // Followed through the account store.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }

    fun clearResult() {
        _state.value = _state.value.copy(result = null, error = null)
    }
}
