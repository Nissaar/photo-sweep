package io.github.nissaar.photosweep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.nissaar.photosweep.Graph
import io.github.nissaar.photosweep.api.CleanupMode
import io.github.nissaar.photosweep.api.Decision
import io.github.nissaar.photosweep.vm.ReviewState

/**
 * The only screen that changes files.
 *
 * Everything marked for deletion is shown first, individually removable, and the
 * confirmation names what will actually happen — including when the answer is
 * "permanently", because the server has no trash.
 */
@Composable
fun ReviewScreen(
    state: ReviewState,
    mode: String,
    trashAvailable: Boolean,
    onKeepAfterAll: (Long) -> Unit,
    onApply: () -> Unit,
    onRestore: (Long) -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val account = Graph.accounts.current()

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = GridItemSpanFull) {
            Column {
                ModeNote(mode, trashAvailable)

                if (state.queued > 0) {
                    Spacer(Modifier.height(8.dp))
                    Note(
                        "${state.queued} verdicts have not reached the server yet. " +
                            "They are not in the list below until they do.",
                        MaterialTheme.colorScheme.tertiaryContainer,
                    )
                }

                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Note(it, MaterialTheme.colorScheme.errorContainer)
                }

                state.result?.let { result ->
                    Spacer(Modifier.height(8.dp))
                    Note(
                        when {
                            result.error != null -> result.error
                            result.failed > 0 -> "${result.succeeded} done, ${result.failed} could not be changed"
                            else -> "${result.succeeded} photos dealt with"
                        },
                        MaterialTheme.colorScheme.secondaryContainer,
                    )
                }

                Spacer(Modifier.height(12.dp))

                if (state.pending.isEmpty()) {
                    Text("Nothing marked for deletion", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Go through a month and anything you swipe away is listed here first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { confirming = true },
                            enabled = !state.applying,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            if (state.applying) {
                                CircularProgressIndicator(
                                    Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onError,
                                )
                            } else {
                                Icon(Icons.Default.Delete, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Delete ${state.pending.size}")
                            }
                        }
                        Spacer(Modifier.size(12.dp))
                        Text(
                            sizeLabel(state.totalBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        items(state.pending, key = { "pending-${it.fileId}" }) { decision ->
            Tile(
                decision = decision,
                previewUrl = account?.previewUrl(decision.fileId, 320),
                icon = Icons.Default.Close,
                iconDescription = "Keep this one after all",
                onAction = { onKeepAfterAll(decision.fileId) },
            )
        }

        if (state.applied.isNotEmpty()) {
            item(span = GridItemSpanFull) {
                Column(Modifier.padding(top = 24.dp)) {
                    Text("Already dealt with", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Bring any of these back if you change your mind.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            items(state.applied, key = { "applied-${it.fileId}" }) { decision ->
                Tile(
                    decision = decision,
                    previewUrl = account?.previewUrl(decision.fileId, 320),
                    icon = Icons.Default.Restore,
                    iconDescription = "Restore",
                    dimmed = true,
                    onAction = { onRestore(decision.fileId) },
                )
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete ${state.pending.size} photos?") },
            text = { Text(confirmationText(mode, trashAvailable)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onApply()
                    },
                ) {
                    Text(
                        if (mode == CleanupMode.TRASH) "Move to trash" else "Move to folder",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

private val GridItemSpanFull: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> androidx.compose.foundation.lazy.grid.GridItemSpan =
    { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }

@Composable
private fun Tile(
    decision: Decision,
    previewUrl: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconDescription: String,
    dimmed: Boolean = false,
    onAction: () -> Unit,
) {
    Box {
        Column {
            // The background matters rather than being decoration: everything under
            // "already dealt with" has been moved to the trash, and the server has no
            // preview left to serve for a trashed file. Without something behind it
            // the tile is an invisible hole with a restore button floating in it.
            AsyncImage(
                model = previewUrl,
                contentDescription = decision.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .alpha(if (dimmed) 0.55f else 1f),
            )
            Text(
                decision.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (dimmed && decision.appliedAt != null) {
                Text(
                    dateLabel(decision.appliedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onAction),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .alpha(0.75f),
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxSize(),
                    shape = CircleShape,
                ) {}
            }
            Icon(icon, contentDescription = iconDescription, Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ModeNote(mode: String, trashAvailable: Boolean) {
    when {
        mode == CleanupMode.TRASH && !trashAvailable -> Note(
            "The trash app is disabled on this server, so deleting is permanent and " +
                "cannot be undone. Switch to collecting files in a folder in Settings " +
                "if you would rather check them first.",
            MaterialTheme.colorScheme.errorContainer,
        )
        mode == CleanupMode.TRASH -> Note(
            "These files move to your Nextcloud trash, where they stay until your " +
                "server's retention policy clears them.",
            MaterialTheme.colorScheme.surfaceVariant,
        )
        else -> Note(
            "These files are moved into your collection folder. Nothing is deleted — " +
                "you delete them yourself in Files.",
            MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun Note(text: String, container: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

private fun confirmationText(mode: String, trashAvailable: Boolean): String = when {
    mode == CleanupMode.TRASH && trashAvailable ->
        "They go to your Nextcloud trash and can be restored from here until your server clears them."
    mode == CleanupMode.TRASH ->
        "The trash is disabled on this server, so this cannot be undone."
    else ->
        "They are moved into your collection folder. Nothing is deleted."
}
