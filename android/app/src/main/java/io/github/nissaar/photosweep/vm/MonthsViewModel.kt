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
import io.github.nissaar.photosweep.api.MonthEntry
import io.github.nissaar.photosweep.api.NotSignedInException
import io.github.nissaar.photosweep.api.Summary

/**
 * Which months the grid shows.
 *
 * Defaults to [TO_REVIEW]: with a decade of months on screen, a badge on the finished
 * ones still leaves you scanning the whole grid to find what is left to do.
 */
enum class MonthFilter(val label: String) {
    TO_REVIEW("To review"),
    DONE("Done"),
    ALL("All"),
}

data class MonthsState(
    val months: List<MonthEntry> = emptyList(),
    val summary: Summary = Summary(),
    val filter: MonthFilter = MonthFilter.TO_REVIEW,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val visible: List<MonthEntry>
        get() = when (filter) {
            MonthFilter.TO_REVIEW -> months.filterNot { it.done }
            MonthFilter.DONE -> months.filter { it.done }
            MonthFilter.ALL -> months
        }

    val toReviewCount: Int get() = months.count { !it.done }
    val doneCount: Int get() = months.count { it.done }
}

class MonthsViewModel : ViewModel() {

    private val _state = MutableStateFlow(MonthsState())
    val state: StateFlow<MonthsState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun setFilter(filter: MonthFilter) {
        _state.value = _state.value.copy(filter = filter)
    }

    /**
     * Fetches the grid again.
     *
     * Called every time the grid comes back into view. A load already under way
     * answers that just as well, unless [force] says something has changed since it
     * started.
     */
    fun load(force: Boolean = false) {
        if (!force && loadJob?.isActive == true) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val response = Graph.repository.months()
                _state.value = _state.value.copy(
                    months = response.months,
                    summary = response.summary,
                    loading = false,
                    error = null,
                )
            } catch (e: NotSignedInException) {
                // The account store has signed out, and this screen goes with it.
                // Re-throwing here used to take the whole process down.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        }
    }

    /**
     * Forgets a month's verdicts so it can be gone through again.
     * Local to the server's records — nothing in Files changes. Asked for only after
     * the user has confirmed, because it erases pending deletes too.
     */
    fun reopen(month: String) {
        viewModelScope.launch {
            try {
                Graph.repository.resetMonth(month)
            } catch (e: NotSignedInException) {
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Could not reopen that month")
            }
            load(force = true)
        }
    }
}
