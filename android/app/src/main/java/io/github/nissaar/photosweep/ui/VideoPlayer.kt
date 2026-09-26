package io.github.nissaar.photosweep.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.annotation.OptIn
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.github.nissaar.photosweep.Graph

/**
 * Plays a video straight from the user's Nextcloud.
 *
 * The file is fetched over WebDAV with the account's own credentials, so nothing is
 * downloaded to the device first and no separate share link is created just to watch
 * three seconds of a clip before deciding to delete it.
 */
// media3's data-source and player-view APIs are marked unstable, and lint only
// accepts androidx.annotation.OptIn for that — Kotlin's own @OptIn does not silence
// it. Opting in explicitly beats suppressing the check.
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val account = Graph.accounts.current()

    val player = remember(url) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Photo Sweep (Android)")
            .setAllowCrossProtocolRedirects(false)
            .apply {
                if (account != null) {
                    setDefaultRequestProperties(mapOf("Authorization" to account.basicAuthHeader()))
                }
            }

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
    }

    // Paused when the app goes to the background. Without this, going home or
    // turning the screen off left the clip's sound playing to nobody.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    AndroidView(
        modifier = modifier,
        // The view is detached from the player before it goes, so it does not keep
        // drawing into, or holding on to, a player that has been released.
        onRelease = { view -> view.player = null },
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        },
    )
}
