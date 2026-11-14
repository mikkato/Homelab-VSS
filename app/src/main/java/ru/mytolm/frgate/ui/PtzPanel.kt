package ru.mytolm.frgate.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import ru.mytolm.frgate.R
import ru.mytolm.frgate.appContainer
import ru.mytolm.frgate.frigate.WsParser

/**
 * §6 PTZ: команды идут через WS /ws сообщением {topic:"{camera}/ptz"}.
 * Отдельное WS-соединение, чтобы не блокировать поток событий; авто-STOP при отпускании;
 * повтор при зажатии ≤200 мс.
 */
@Composable
fun PtzPanel(camera: String, features: List<String>, presets: List<String>) {
    val scope = rememberCoroutineScope()
    var ws by remember { mutableStateOf<WebSocket?>(null) }
    var repeatJob by remember { mutableStateOf<Job?>(null) }
    var autotracker by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }

    fun ensureWs(): WebSocket {
        ws?.let { return it }
        val w = appContainer.client.newWebSocket(object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lastError = "PTZ: ${t.message}"
            }
        })
        ws = w
        return w
    }

    fun send(cmd: String) {
        try { ensureWs().send(WsParser.ptzMessage(camera, cmd)) } catch (e: Exception) { lastError = "PTZ: ${e.message}" }
    }

    DisposableEffect(camera) {
        onDispose {
            repeatJob?.cancel()
            try { ws?.close(1000, "bye") } catch (_: Exception) {}
            ws = null
        }
    }

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ptz_title), style = MaterialTheme.typography.titleMedium)
            val canPan = features.any { it.contains("pan", true) || it == "stop" || it.contains("zoom", true) }
            if (canPan) {
                Box(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        HoldButton(Icons.Default.KeyboardArrowUp, R.string.cd_up, "UP") { send(it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            HoldButton(Icons.Default.KeyboardArrowLeft, R.string.cd_left, "LEFT") { send(it) }
                            HoldButton(Icons.Default.Stop, R.string.cd_stop, "STOP") { send(it) }
                            HoldButton(Icons.Default.KeyboardArrowRight, R.string.cd_right, "RIGHT") { send(it) }
                        }
                        HoldButton(Icons.Default.KeyboardArrowDown, R.string.cd_down, "DOWN") { send(it) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (features.any { it.contains("zoom", true) }) {
                        HoldButton(Icons.Outlined.ZoomIn, R.string.cd_zoom_in, "ZOOM_IN") { send(it) }
                        HoldButton(Icons.Outlined.ZoomOut, R.string.cd_zoom_out, "ZOOM_OUT") { send(it) }
                    }
                }
            }
            if (presets.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEachIndexed { i, name ->
                        OutlinedButton(onClick = { send("PRESET_${i + 1}") }) {
                            Text(name.ifBlank { "${i + 1}" }, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = autotracker,
                    onCheckedChange = {
                        autotracker = it
                        try { ensureWs().send(WsParser.autotrackerToggle(camera, it)) } catch (_: Exception) {}
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ptz_autotracker))
            }
            lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * Кнопка-джойстик: при отпускании — STOP; при удержании — повтор ≤200 мс.
 * top10-7: Material-иконки с contentDescription вместо ASCII-стрелок; cmd передаётся явно
 * (раньше onCmd("STOP") вызывал колбэк с игнорируемым параметром — авто-STOP не отправлялся).
 */
@Composable
private fun HoldButton(icon: ImageVector, @androidx.annotation.StringRes cdRes: Int, cmd: String, onCmd: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    OutlinedButton(
        onClick = {},
        modifier = Modifier
            .size(56.dp)
            .padding(0.dp)
            .pointerInput(cmd) {
                awaitEachGesture {
                    awaitFirstDown()
                    var holding = true
                    val job = scope.launch {
                        while (holding) {
                            onCmd(cmd)
                            delay(200)
                        }
                    }
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Final)
                        if (e.changes.none { it.pressed }) break
                    }
                    holding = false
                    job.cancel()
                    onCmd("STOP")
                }
            }
            .padding(4.dp)
    ) { Icon(icon, contentDescription = stringResource(cdRes)) }
}
