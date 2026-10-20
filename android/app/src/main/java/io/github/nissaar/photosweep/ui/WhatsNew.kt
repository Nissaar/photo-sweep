package io.github.nissaar.photosweep.ui

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.nissaar.photosweep.BuildConfig
import io.github.nissaar.photosweep.Graph

/**
 * What changed in this version, as the store changelog words it, or null if there
 * is no note for it. The changelogs are packaged as assets, named by versionCode.
 */
internal fun whatsNewText(context: Context, versionCode: Int = BuildConfig.VERSION_CODE): String? =
    try {
        context.assets.open("$versionCode.txt").bufferedReader().use { it.readText() }
            .let(::unwrap)
            .takeIf { it.isNotBlank() }
    } catch (e: java.io.IOException) {
        null
    }

/**
 * The changelog files are wrapped at about 80 columns. A dialog is not that wide, so
 * single line breaks become spaces; a blank line still starts a new paragraph.
 */
internal fun unwrap(text: String): String =
    text.trim().replace(Regex("(?<!\n)\n(?!\n)"), " ")

/**
 * Shows the notes for this version once, to someone who had an earlier one.
 *
 * A fresh install records the version when it opens signed out, in
 * [io.github.nissaar.photosweep.MainActivity]'s `Root`, so it never sees notes for
 * changes it was not around for.
 */
@Composable
fun WhatsNewDialog() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val seen = Graph.settings.whatsNewSeen.first()
        if (seen == BuildConfig.VERSION_CODE) return@LaunchedEffect
        val text = whatsNewText(context)
        if (text == null) {
            Graph.settings.setWhatsNewSeen(BuildConfig.VERSION_CODE)
        } else {
            notes = text
        }
    }

    notes?.let { text ->
        val dismiss = {
            notes = null
            scope.launch { Graph.settings.setWhatsNewSeen(BuildConfig.VERSION_CODE) }
            Unit
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("What’s new in ${BuildConfig.VERSION_NAME}") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = dismiss) { Text("Got it") } },
        )
    }
}
