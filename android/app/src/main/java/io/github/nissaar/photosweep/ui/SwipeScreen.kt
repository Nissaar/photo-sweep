package io.github.nissaar.photosweep.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import io.github.nissaar.photosweep.Graph
import io.github.nissaar.photosweep.api.MediaItem
import io.github.nissaar.photosweep.ui.theme.keepColour
import io.github.nissaar.photosweep.vm.SwipeState

/** How far the card must travel before letting go counts as a verdict. */
private const val COMMIT_FRACTION = 0.28f

/**
 * One photo at a time.
 *
 * Dragging is the primary gesture and the buttons do exactly the same thing, because
 * a swipe is fast once you trust it and a button is what you reach for until you do.
 */
/**
 * One size for both cards, and deliberately a power of four.
 *
 * The card behind prefetches the next photo; the card in front then displays it. Ask
 * for two different sizes and the browser cache never matches, so the prefetch is
 * thrown away and every swipe pays a fresh round trip. Nextcloud also snaps a preview
 * up to the next power of four and previewgenerator pre-renders that same ladder, so
 * 1024 is a size servers tend to already hold — where 1600 quietly becomes 4096.
 */
private const val DECK_PREVIEW = 1024

@Composable
fun SwipeScreen(
    state: SwipeState,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
    onUndo: () -> Unit,
    onReviewAgain: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    state.month?.let { monthLabel(it) } ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.items.isNotEmpty()) {
                    Text(
                        "${minOf(state.index + 1, state.items.size)} of ${state.items.size}" +
                            if (state.queued > 0) " · ${state.queued} waiting to sync" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            TextButton(onClick = onUndo, enabled = state.history.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("Undo")
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.loading -> CircularProgressIndicator()

                state.error != null -> Message(
                    title = "Could not open this month",
                    body = state.error,
                    actionLabel = "Back to months",
                    onAction = onBack,
                )

                state.isEmpty -> Message(
                    title = "Nothing left in this month",
                    body = "Every photo here already has a verdict.",
                    actionLabel = "Review this month again",
                    onAction = onReviewAgain,
                )

                state.finished -> Message(
                    title = "Month finished",
                    body = "Kept ${state.kept}, marked ${state.deleted} for deletion.",
                    actionLabel = "Back to months",
                    onAction = onBack,
                )

                else -> Deck(state = state, onKeep = onKeep, onDelete = onDelete)
            }
        }

        if (!state.loading && state.current != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Delete")
                }

                Button(
                    onClick = onKeep,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = keepColour(),
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Keep")
                }
            }
        }
    }
}

@Composable
private fun Deck(state: SwipeState, onKeep: () -> Unit, onDelete: () -> Unit) {
    val current = state.current ?: return
    val account = Graph.accounts.current() ?: return
    // The drag offset is in pixels, so the threshold has to be too. Comparing it
    // against a dp figure made the card commit after a few millimetres on a dense
    // screen, which turns every scroll into an accidental verdict.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val commitDistance = with(LocalDensity.current) { screenWidthDp.toPx() } * COMMIT_FRACTION

    val offset = remember(current.fileId) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var playing by remember(current.fileId) { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        // The next photo sits behind the current one, so a decision reveals it at once
        // rather than flashing an empty frame while it loads.
        state.next?.let { next ->
            AsyncImage(
                model = account.previewUrl(next.fileId, DECK_PREVIEW),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .alpha(0.35f),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offset.value
                    rotationZ = offset.value / 40f
                }
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .pointerInput(current.fileId, playing) {
                    if (playing) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val travelled = offset.value
                            scope.launch {
                                when {
                                    travelled <= -commitDistance -> onDelete()
                                    travelled >= commitDistance -> onKeep()
                                    else -> offset.animateTo(0f)
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { _, dragAmount ->
                        scope.launch { offset.snapTo(offset.value + dragAmount) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (current.isVideo && playing) {
                VideoPlayer(url = account.fileUrl(current.path), modifier = Modifier.fillMaxSize())
            } else {
                AsyncImage(
                    model = account.previewUrl(current.fileId, DECK_PREVIEW),
                    contentDescription = current.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (current.isVideo && !playing) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(40.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { playing = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play video",
                        tint = Color.White,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }

            Stamps(offset.value, commitDistance)
            Caption(current, Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun Stamps(offsetX: Float, commitDistance: Float) {
    val threshold = commitDistance * 0.35f

    if (offsetX <= -threshold) {
        Stamp("DELETE", MaterialTheme.colorScheme.error, Alignment.TopStart, -12f)
    } else if (offsetX >= threshold) {
        Stamp("KEEP", keepColour(), Alignment.TopEnd, 12f)
    }
}

@Composable
private fun Stamp(label: String, colour: Color, alignment: Alignment, rotation: Float) {
    Box(Modifier.fillMaxSize()) {
        Text(
            label,
            color = colour,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 26.sp,
            modifier = Modifier
                .align(alignment)
                .padding(24.dp)
                .graphicsLayer { rotationZ = rotation }
                .border(3.dp, colour, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Caption(item: MediaItem, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            item.name,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf(dateLabel(item.takenAt), sizeLabel(item.size))
                .filter { it.isNotEmpty() }
                .joinToString(" · "),
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Message(title: String, body: String, actionLabel: String, onAction: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(24.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}
