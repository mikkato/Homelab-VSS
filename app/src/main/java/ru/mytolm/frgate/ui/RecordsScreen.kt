package ru.mytolm.frgate.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mytolm.frgate.*
import ru.mytolm.frgate.R
import ru.mytolm.frgate.frigate.ExportItem
import ru.mytolm.frgate.util.TimeFmt
import java.io.File
import java.util.Calendar

/** §3.6 Records: календарь-грид наличия → выбор суток/часа → HLS-плеер; экспорт. */
@Composable
fun RecordsScreen(nav: NavHostController) {
    var cameras by remember { mutableStateOf<List<String>>(emptyList()) }
    var camera by remember { mutableStateOf<String?>(null) }
    var days by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedDay by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }
    var exports by remember { mutableStateOf<List<ExportItem>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            cameras = ru.mytolm.frgate.frigate.ConfigParse.cameras(appContainer.client.config()).map { it.name }
            camera = cameras.firstOrNull()
        } catch (_: Exception) {
            offline = true
        }
    }

    LaunchedEffect(camera) {
        val c = camera ?: return@LaunchedEffect
        try {
            val sum = appContainer.client.recordingsSummary(c, TimeFmt.daysAgo(14))
            days = sum.keys.sortedDescending()
            offline = false
        } catch (_: Exception) {
            offline = true
        }
        try {
            exports = appContainer.client.exports()
        } catch (_: Exception) {
        }
    }

    Scaffold(topBar = {
        ScreenHeader(stringResource(R.string.tab_records), actions = {
            if (cameras.size > 1) {
                var exp by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { exp = true }) { Text(camera ?: "") }
                    DropdownMenu(exp, onDismissRequest = { exp = false }) {
                        cameras.forEach {
                            DropdownMenuItem(text = { Text(it) }, onClick = { camera = it; selectedDay = null; exp = false })
                        }
                    }
                }
            }
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OfflineBanner(offline)
            Text(stringResource(R.string.rec_select_day), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (days.isEmpty()) {
                Text(stringResource(R.string.rec_no_data), modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    days.forEach { d ->
                        FilterChip(modifier = Modifier.height(40.dp), selected = selectedDay == d, onClick = { selectedDay = d }, label = { Text(d) })
                    }
                }
            }
            val day = selectedDay
            if (day != null) {
                Text(
                    stringResource(R.string.rec_select_hour, day),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                // Почасовые ячейки: в 0.17 summary отдаёт наличие по дням, часы — все 24 с
                // отметкой непрерывности из summary (при выборе открываем VOD за час).
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.height(200.dp)
                ) {
                    items(24) { h ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clickable {
                                    val y = day.substring(0, 4).toInt()
                                    val m = day.substring(5, 7).toInt()
                                    val d = day.substring(8, 10).toInt()
                                    val cal = Calendar.getInstance().apply { set(y, m - 1, d, h, 0, 0) }
                                    val s = cal.timeInMillis / 1000
                                    nav.navigate(Routes.recordPlay(camera ?: "", s, s + 3600))
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("%02d".format(h), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
            // Экспорт выбранного часа + список экспортов
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.rec_exports), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp))
            if (exports.isEmpty()) {
                Text(stringResource(R.string.rec_no_data), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            exports.take(10).forEach { ex ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ex.name.ifBlank { ex.id }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(listOf(ex.camera, TimeFmt.dt(ex.date), ex.state).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val ctx = LocalContext.current
                        TextButton(onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val dir = File(ctx.filesDir, "downloads").apply { mkdirs() }
                                    val dest = File(dir, "export_${ex.id}.mp4")
                                    withContext(Dispatchers.IO) {
                                        appContainer.client.download(appContainer.client.exportDownloadUrl(ex.id), dest) {}
                                    }
                                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", dest)
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "video/mp4")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    })
                                } catch (_: Exception) {
                                }
                                busy = false
                            }
                        }) { Text("⤓") }
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    appContainer.client.deleteExport(ex.id)
                                    exports = appContainer.client.exports()
                                } catch (_: Exception) {
                                }
                            }
                        }) { Text("🗑") }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

/** Плеер архива: HLS VOD go2rtc (master.m3u8), скорость 1/2/4×, фуллскрин встроен в PlayerView. */
@Composable
fun RecordPlayScreen(nav: NavHostController, camera: String, start: Long, end: Long) {
    val url = appContainer.client.vodUrl(camera, start, end)
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var exportMsg by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = {
        ScreenHeader("$camera · ${TimeFmt.hm(start.toDouble())}–${TimeFmt.hm(end.toDouble())}",
            onBack = { nav.popBackStack() },
            actions = {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(1f, 2f, 4f).forEach { s ->
                        FilterChip(modifier = Modifier.height(40.dp), selected = playbackSpeed == s, onClick = { playbackSpeed = s }, label = { Text("${s.toInt()}×") })
                    }
                }
            })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            HlsPlayer(url = url, speed = playbackSpeed, onError = { err = it })
            err?.let { Text(err!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    scope.launch {
                        try {
                            appContainer.client.createExport(camera, start, end)
                            exportMsg = null
                            nav.popBackStack()
                        } catch (e: Exception) {
                            err = errText((e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu ?: "http?")
                        }
                    }
                }) { Text(stringResource(R.string.rec_export)) }
                exportMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** media3 HLS-плеер; перемотка/полноэкранный — встроенными контролами PlayerView. */
@Composable
fun HlsPlayer(url: String, speed: Float, onError: (String?) -> Unit) {
    val player = rememberAuthExoPlayer()
    LaunchedEffect(url) {
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
        player.prepare()
    }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }
    DisposableEffect(Unit) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                onError(e.errorCodeName)
            }
        }
        player.addListener(listener)
        // release и пауза по ON_STOP — в rememberAuthExoPlayer (top10-3)
        onDispose { player.removeListener(listener) }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = {
            androidx.media3.ui.PlayerView(it).apply {
                useController = true
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        update = { it.player = player },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
    )
}
