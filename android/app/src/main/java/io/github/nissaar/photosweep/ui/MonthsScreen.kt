package io.github.nissaar.photosweep.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nissaar.photosweep.api.MonthEntry
import io.github.nissaar.photosweep.vm.MonthFilter
import io.github.nissaar.photosweep.vm.MonthsState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MonthsScreen(
    state: MonthsState,
    scanning: Boolean,
    indexedCount: Int,
    onFilter: (MonthFilter) -> Unit,
    onOpen: (String) -> Unit,
    onReopen: (String) -> Unit,
) {
    var reopening by remember { mutableStateOf<MonthEntry?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                subtitle(state, scanning, indexedCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonthFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { onFilter(filter) },
                        label = {
                            Text(
                                when (filter) {
                                    MonthFilter.TO_REVIEW -> "${filter.label} (${state.toReviewCount})"
                                    MonthFilter.DONE -> "${filter.label} (${state.doneCount})"
                                    MonthFilter.ALL -> filter.label
                                },
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        when {
            state.loading -> Centre { CircularProgressIndicator() }

            state.visible.isEmpty() -> Centre {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(emptyTitle(state, scanning), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        emptyBody(state, scanning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.visible, key = { it.month }) { month ->
                    MonthTile(
                        month = month,
                        onClick = { onOpen(month.month) },
                        // A long press is how a finished month is put back into play.
                        // Deliberately not a visible button: reopening a month is a
                        // rare, deliberate act, and a tappable target on every tile
                        // would be hit by accident far more often than on purpose.
                        // It erases verdicts, pending deletes included, so it asks
                        // first rather than acting on a press that may have been an
                        // accident.
                        onLongClick = { reopening = month },
                    )
                }
            }
        }
    }

    reopening?.let { month ->
        ReviewAgainDialog(
            month = month.month,
            verdicts = month.reviewed,
            onConfirm = {
                reopening = null
                onReopen(month.month)
            },
            onDismiss = { reopening = null },
        )
    }
}

/**
 * Asks before a month's verdicts are forgotten.
 *
 * @param verdicts how many the server counts for the month, or null when that is not
 *   known on this screen
 */
@Composable
fun ReviewAgainDialog(month: String, verdicts: Int?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val what = when (verdicts) {
        null -> "the verdicts you have given in this month that have"
        1 -> "the 1 verdict you have given in this month that has"
        else -> "the $verdicts verdicts you have given in this month that have"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review ${monthLabel(month)} again?") },
        text = {
            Text(
                "This removes $what not been carried out " +
                    "yet, including photos marked for deletion. Nothing in " +
                    "Files changes, and photos already deleted or moved stay that way.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remove verdicts", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthTile(month: MonthEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                monthLabel(month.month),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (month.done) {
                    "All ${month.total} reviewed"
                } else {
                    "${month.remaining} of ${month.total} left"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = {
                    if (month.total == 0) 0f else month.reviewed.toFloat() / month.total
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Centre(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun subtitle(state: MonthsState, scanning: Boolean, indexed: Int): String = when {
    indexed == 0 && scanning -> "Reading your library…"
    indexed == 0 -> "No photos indexed yet"
    else -> "${state.summary.photosLeft} photos still to go through, across ${state.toReviewCount} months"
}

private fun emptyTitle(state: MonthsState, scanning: Boolean): String = when {
    scanning -> "Still reading your library"
    state.filter == MonthFilter.TO_REVIEW && state.months.isNotEmpty() -> "Every month is done"
    else -> "Nothing here yet"
}

private fun emptyBody(state: MonthsState, scanning: Boolean): String = when {
    scanning -> "Months appear as they are found."
    state.filter == MonthFilter.TO_REVIEW && state.months.isNotEmpty() ->
        "Switch to All, then long-press a month to go back over it."
    else -> "Once your photos have been indexed, the months they were taken in show up here."
}
