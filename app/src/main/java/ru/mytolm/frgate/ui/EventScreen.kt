package ru.mytolm.frgate.ui

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mytolm.frgate.*
import ru.mytolm.frgate.R
import ru.mytolm.frgate.data.toFrigateEvent
import ru.mytolm.frgate.frigate.FrigateEvent
import ru.mytolm.frgate.util.TimeFmt
import java.io.File

/** §3.4 Карточка события: клип, snapshot, метаданные, скачивание/retain/false positive. */
@Composable
fun EventScreen(nav: NavHostController, eventId: String) {
    val ctx = LocalContext.current
    var ev by remember { mutableStateOf<FrigateEvent?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var fromCache by remember { mutableStateOf(false) }
    var downloadState by remember { mutableStateOf<Pair<Float, File?>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(eventId) {
        try {
            ev = appContainer.client.event(eventId)
            fromCache = false
        } catch (e: Exception) {
            // top10-1: карточка события из Room-кэша, если сети нет
            val cached = try { appContainer.db.eventDao().byIdOnce(eventId) } catch (_: Exception) { null }
            if (cached != null) {
                ev = cached.toFrigateEvent()
                fromCache = true
            } else {
                err = errText((e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu ?: "http?")
            }
        }
    }

    Scaffold(topBar = { ScreenHeader(stringResource(R.string.tab_review) + " · $eventId", onBack = { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (err != null) Text(err!!, color = MaterialTheme.colorScheme.error)
            CachedBadge(fromCache)
            if (fromCache) Text(
                stringResource(R.string.cached_event_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val e = ev
            if (e == null && err == null) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            if (e != null) {
                // Клип
                if (e.has_clip) {
                    EventPlayer(url = appContainer.client.absoluteUrl("/api/events/${e.id}/clip.mp4"))
                } else {
                    Card(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.rec_no_data), Modifier.padding(16.dp))
                    }
                }
                // Snapshot
                if (e.has_snapshot) {
                    AsyncImage(
                            imageLoader = appContainer.imageLoader,
                        model = appContainer.client.absoluteUrl("/api/events/${e.id}/snapshot.jpg"),
                        contentDescription = "snapshot",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    )
                }
                // Метаданные
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MetaRow(stringResource(R.string.ev_camera), e.camera)
                        MetaRow(stringResource(R.string.ev_objects), listOfNotNull(
                            ru.mytolm.frgate.util.LabelsRu.labelRu(e.label), e.sub_label
                        ).joinToString(" · "))
                        if (e.zones.isNotEmpty()) MetaRow(stringResource(R.string.ev_zones), e.zones.joinToString(", "))
                        MetaRow(stringResource(R.string.ev_confidence), "${e.confidence}%")
                        MetaRow(stringResource(R.string.ev_time), TimeFmt.dt(e.since))
                        MetaRow(stringResource(R.string.ev_duration), TimeFmt.duration(e.duration))
                        MetaRow(stringResource(R.string.ev_id), e.id)
                    }
                }
                // Действия
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            downloadState = 0f to null
                            try {
                                val dir = File(ctx.filesDir, "downloads").apply { mkdirs() }
                                val dest = File(dir, "frgate_${e.camera}_${TimeFmt.fileStamp()}.mp4")
                                withContext(Dispatchers.IO) {
                                    appContainer.client.download(
                                        appContainer.client.absoluteUrl("/api/events/${e.id}/clip.mp4"), dest
                                    ) { p -> downloadState = p to null }
                                }
                                downloadState = 1f to dest
                            } catch (ex: Exception) {
                                err = errText((ex as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu ?: "http?")
                                downloadState = null
                            }
                        }
                    }, enabled = e.has_clip, modifier = Modifier.weight(1f).height(44.dp)) {
                        Text(stringResource(R.string.ev_download), maxLines = 1, softWrap = false, fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            try {
                                appContainer.client.setRetain(e.id, !e.retained)
                                ev = e.copy(retained = !e.retained)
                            } catch (ex: Exception) {
                            }
                        }
                    }, modifier = Modifier.weight(1f).height(44.dp)) {
                        Text(if (e.retained) stringResource(R.string.ev_unretain) else stringResource(R.string.ev_retain), maxLines = 1, softWrap = false, fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            try {
                                val fp = !(e.false_positive ?: false)
                                appContainer.client.setFalsePositive(e.id, fp)
                                ev = e.copy(false_positive = fp)
                            } catch (_: Exception) {
                            }
                        }
                    }) { Text(stringResource(R.string.ev_false_positive)) }
                }
                // Скачанный файл → Share
                downloadState?.let { (p, file) ->
                    if (file != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.ev_downloaded, file.name), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(share, file.name))
                            }) { Text(stringResource(R.string.ev_share)) }
                        }
                    } else if (p < 1f) {
                        Column {
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                            Text(stringResource(R.string.ev_downloading, (p * 100).toInt()), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                // Мини-таймлайн → Records на время события (F11: ±5 мин, а не округлый час)
                OutlinedButton(modifier = Modifier.fillMaxWidth().height(44.dp), onClick = {
                    val s = (e.since - 300).toLong()
                    val end = ((e.end_time ?: (e.since + 1800)) + 300).toLong()
                    nav.navigate(Routes.recordPlay(e.camera, s, end))
                }) { Text(stringResource(R.string.ev_timeline), maxLines = 1, softWrap = false, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun MetaRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodyMedium)
    }
}

/** PlayerView с контролами для клипа/записи. */
@Composable
fun EventPlayer(url: String) {
    val player = rememberAuthExoPlayer()
    LaunchedEffect(url) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        update = { it.player = player },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
    )
}
