package ru.mytolm.frgate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import ru.mytolm.frgate.MediaSources

/**
 * Единая обёртка ExoPlayer (top10-3): auth-источник, low-latency LoadControl для live,
 * пауза на ON_STOP / возобновление на ON_RESUME, release при dispose.
 * Заменил три дублирующиеся реализации (Live / Event / Records).
 */
@Composable
fun rememberAuthExoPlayer(live: Boolean = false): ExoPlayer {
    val ctx = LocalContext.current
    val player = remember(live) {
        val loadControl = DefaultLoadControl.Builder()
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBufferDurationsMs(
                if (live) 1_500 else DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                if (live) 6_000 else DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                if (live) 1_000 else DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                if (live) 2_000 else DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(MediaSources.okHttpDataSourceFactory()))
            .setLoadControl(loadControl)
            .build().apply { playWhenReady = true }
    }

    // top10-3 (L1): в фоне не играем и не качаем; release — при уходе экрана
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        var wasPlaying = false
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    wasPlaying = player.playWhenReady
                    if (wasPlaying) player.playWhenReady = false
                }
                Lifecycle.Event.ON_RESUME -> if (wasPlaying && player.isPlaying.not()) {
                    player.playWhenReady = true
                    wasPlaying = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            player.release()
        }
    }
    return player
}

/** Live MediaItem с low-latency конфигом (top10-8): короткая задержка, ловля переднего края. */
fun liveMediaItem(url: String, mimeType: String? = null): MediaItem {
    val b = MediaItem.Builder().setUri(url)
    if (mimeType != null) b.setMimeType(mimeType)
    b.setLiveConfiguration(
        MediaItem.LiveConfiguration.Builder()
            .setMaxPlaybackSpeed(1.02f)
            .build()
    )
    return b.build()
}
