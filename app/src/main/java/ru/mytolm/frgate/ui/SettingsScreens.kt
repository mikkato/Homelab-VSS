package ru.mytolm.frgate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mytolm.frgate.*
import ru.mytolm.frgate.R
import ru.mytolm.frgate.data.LiveMode
import ru.mytolm.frgate.data.ThemeMode
import ru.mytolm.frgate.frigate.CameraInfo
import ru.mytolm.frgate.frigate.ConfigParse
import ru.mytolm.frgate.frigate.UserInfo

/** §3.8 Настройки. */
@Composable
fun SettingsScreen(nav: NavHostController) {
    val s = appContainer.settings.state.collectAsState().value
    val role by appContainer.userRole.collectAsState()
    var version by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var confirmRestart by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        version = try { appContainer.client.version() } catch (e: Exception) { null }
        appContainer.refreshRole() // top10-5 (F6)
    }

    Scaffold(topBar = { ScreenHeader(stringResource(R.string.tab_settings)) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            // --- Подключение ---
            Section(stringResource(R.string.settings_connection))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${s.baseUrl}  ·  ${s.login}", style = MaterialTheme.typography.bodyMedium)
                    version?.let {
                        Text("Frigate $it", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                    } ?: err?.let { Text(errText(it), color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { nav.navigate(Routes.CONNECTION) }) {
                        Text(stringResource(R.string.conn_title))
                    }
                }
            }

            // --- Внешний вид ---
            Section(stringResource(R.string.settings_appearance))
            var themeExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { themeExpanded = true }) {
                    Text(stringResource(R.string.settings_theme) + ": " + when (s.theme) {
                        ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                        ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                        ThemeMode.DARK -> stringResource(R.string.theme_dark)
                    }, maxLines = 1, softWrap = false)
                }
                DropdownMenu(themeExpanded, onDismissRequest = { themeExpanded = false }) {
                    listOf(ThemeMode.SYSTEM to R.string.theme_system,
                           ThemeMode.LIGHT to R.string.theme_light,
                           ThemeMode.DARK to R.string.theme_dark).forEach { (m, res) ->
                        DropdownMenuItem(text = { Text(stringResource(res)) }, onClick = {
                            appContainer.settings.update { it.copy(theme = m) }
                            themeExpanded = false
                        })
                    }
                }
            }

            // --- Live ---
            Section(stringResource(R.string.settings_live))
            var liveExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { liveExpanded = true }) {
                    Text(stringResource(R.string.settings_live_mode) + ": " + if (s.liveMode == LiveMode.HTTP) "HTTP" else "WebRTC", maxLines = 1, softWrap = false)
                }
                DropdownMenu(liveExpanded, onDismissRequest = { liveExpanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.live_mode_http)) }, onClick = {
                        appContainer.settings.update { it.copy(liveMode = LiveMode.HTTP) }; liveExpanded = false
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.live_mode_webrtc)) }, onClick = {
                        appContainer.settings.update { it.copy(liveMode = LiveMode.WEBRTC) }; liveExpanded = false
                    })
                }
            }
            if (s.liveMode == LiveMode.WEBRTC) {
                Text(stringResource(R.string.webrtc_warning), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    scope.launch {
                        err = null
                        val cam = try { ConfigParse.cameras(appContainer.client.config()).firstOrNull()?.name } catch (e: Exception) { null }
                        if (cam == null) { err = "badgateway"; return@launch }
                        // Пробный signaling-запрос: SDP-обмен без медиа — замер HTTP-кода
                        try {
                            val t0 = System.currentTimeMillis()
                            val req = okhttp3.Request.Builder()
                                .url(appContainer.client.webrtcUrl(cam))
                                .post("{\"type\":\"unspecified\"}".toRequestBody("application/json".toMediaType()))
                                .build()
                            val code = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                appContainer.client.streamClient.newCall(req).execute().use { it.code }
                            }
                            val dt = System.currentTimeMillis() - t0
                            version = if (code in 200..299 || code == 400) "SDP OK ($code, ${dt} мс)" else "SDP fail $code"
                        } catch (e: Exception) {
                            err = "timeout"
                        }
                    }
                }) { Text(stringResource(R.string.webrtc_check)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = s.lteAutoQuality, onCheckedChange = { v ->
                    appContainer.settings.update { prev -> prev.copy(lteAutoQuality = v) }
                })
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_lte_quality))
            }

            // --- Уведомления ---
            Section(stringResource(R.string.settings_notifications))
            OutlinedButton(onClick = { nav.navigate(Routes.S_NOTIF) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_notifications), maxLines = 1, softWrap = false) }

            // --- Администрирование (top10-5 F6: скрываем для роли viewer) ---
            if (role != "viewer") {
                Section(stringResource(R.string.settings_admin))
                OutlinedButton(onClick = { nav.navigate(Routes.S_CAMERAS) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_cameras))
                }
                OutlinedButton(onClick = { nav.navigate(Routes.S_OBJECTS) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_objects))
                }
                OutlinedButton(onClick = { nav.navigate(Routes.S_REVIEW) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_review_settings))
                }
                OutlinedButton(onClick = { nav.navigate(Routes.S_USERS) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_users))
                }
                OutlinedButton(onClick = { nav.navigate(Routes.S_SYSTEM) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_system))
                }
                OutlinedButton(onClick = { nav.navigate(Routes.S_LOGS) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_logs))
                }
                OutlinedButton(onClick = { confirmRestart = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_restart))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // top10-5 (F5): рестарт NVR — только через подтверждение
    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text(stringResource(R.string.settings_restart)) },
            text = { Text(stringResource(R.string.confirm_restart_text, s.baseUrl)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestart = false
                    scope.launch {
                        try { appContainer.client.restart(); err = null }
                        catch (e: Exception) { err = (e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu ?: "badgateway" }
                    }
                }) { Text(stringResource(R.string.settings_restart), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestart = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
}

/** §3.7 настройки уведомлений: категории/классы/камеры/тихие часы. */
@Composable
fun NotificationSettingsScreen(nav: NavHostController) {
    val s = appContainer.settings.state.collectAsState().value
    var labels by remember { mutableStateOf<List<String>>(emptyList()) }
    var cameras by remember { mutableStateOf<List<String>>(emptyList()) }
    var quietDlg by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            labels = appContainer.client.labels()
            cameras = ConfigParse.cameras(appContainer.client.config()).map { it.name }
        } catch (_: Exception) {
        }
    }

    Scaffold(topBar = { ScreenHeader(stringResource(R.string.settings_notifications), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = s.notifEnabled, onCheckedChange = { en ->
                    appContainer.settings.update { it.copy(notifEnabled = en) }
                    if (en) appContainer.ensureService()
                })
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.notif_enabled))
            }
            Text(stringResource(R.string.notif_filter_cats), style = MaterialTheme.typography.titleSmall)
            FlowRowWrap {
                listOf("alarm" to R.string.cat_alarm, "detection" to R.string.cat_detection, "motion" to R.string.cat_motion)
                    .forEach { (cat, res) ->
                        FilterChip(modifier = Modifier.height(40.dp), selected = s.notifCategories.contains(cat), onClick = {
                                val cur = s.notifCategories.toMutableSet()
                                if (cur.contains(cat)) cur.remove(cat) else cur.add(cat)
                                appContainer.settings.update { it.copy(notifCategories = cur) }
                            }, label = { Text(stringResource(res)) })
                    }
            }
            if (cameras.isNotEmpty()) {
                Text(stringResource(R.string.notif_filter_cameras), style = MaterialTheme.typography.titleSmall)
                FlowRowWrap {
                    cameras.forEach { c ->
                        FilterChip(modifier = Modifier.height(40.dp), selected = s.notifCameras.contains(c) || s.notifCameras.isEmpty(), onClick = {
                            val cur = if (s.notifCameras.isEmpty()) cameras.toMutableSet() else s.notifCameras.toMutableSet()
                            if (cur.contains(c)) cur.remove(c) else cur.add(c)
                            if (cur.isEmpty() || cur.size == cameras.size) {
                                appContainer.settings.update { it.copy(notifCameras = emptySet()) }
                            } else {
                                appContainer.settings.update { it.copy(notifCameras = cur) }
                            }
                        }, label = { Text(c) })
                    }
                }
            }
            if (labels.isNotEmpty()) {
                Text(stringResource(R.string.notif_filter_labels), style = MaterialTheme.typography.titleSmall)
                FlowRowWrap {
                    labels.forEach { l ->
                        FilterChip(modifier = Modifier.height(40.dp), selected = s.notifLabels.contains(l) || s.notifLabels.isEmpty(), onClick = {
                            val cur = if (s.notifLabels.isEmpty()) labels.toMutableSet() else s.notifLabels.toMutableSet()
                            if (cur.contains(l)) cur.remove(l) else cur.add(l)
                            appContainer.settings.update {
                                it.copy(notifLabels = if (cur.size == labels.size) emptySet() else cur)
                            }
                        }, label = { Text(ru.mytolm.frgate.util.LabelsRu.labelRu(l)) })
                    }
                }
            }
            Text(stringResource(R.string.notif_quiet), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = { quietDlg = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (s.quietFrom < 0) stringResource(R.string.preset_all)
                else "%02d:%02d–%02d:%02d".format(s.quietFrom / 60, s.quietFrom % 60, s.quietTo / 60, s.quietTo % 60))
            }
            if (quietDlg) {
                var fromH by remember { mutableIntStateOf(if (s.quietFrom >= 0) s.quietFrom / 60 else 22) }
                var fromM by remember { mutableIntStateOf(if (s.quietFrom >= 0) s.quietFrom % 60 else 0) }
                var toH by remember { mutableIntStateOf(if (s.quietTo >= 0) s.quietTo / 60 else 7) }
                var toM by remember { mutableIntStateOf(if (s.quietTo >= 0) s.quietTo % 60 else 0) }
                AlertDialog(
                    onDismissRequest = { quietDlg = false },
                    title = { Text(stringResource(R.string.notif_quiet)) },
                    text = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.notif_quiet_from))
                                NumberField("${"%02d".format(fromH)}:${"%02d".format(fromM)}") { h, m -> fromH = h; fromM = m }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.notif_quiet_to))
                                NumberField("${"%02d".format(toH)}:${"%02d".format(toM)}") { h, m -> toH = h; toM = m }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            appContainer.settings.update {
                                it.copy(quietFrom = fromH * 60 + fromM, quietTo = toH * 60 + toM)
                            }
                            quietDlg = false
                        }) { Text(stringResource(R.string.save)) }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            appContainer.settings.update { it.copy(quietFrom = -1, quietTo = -1) }
                            quietDlg = false
                        }) { Text(stringResource(R.string.preset_all)) }
                    }
                )
            }
        }
    }
}

@Composable
private fun FlowRowWrap(content: @Composable () -> Unit) {
    // простая переноска без experimental FoundationFlowLayout
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        content()
    }
}

@Composable
private fun NumberField(value: String, onHm: (Int, Int) -> Unit) {
    var txt by remember { mutableStateOf(value) }
    OutlinedTextField(
        value = txt, onValueChange = { t ->
            txt = t
            val m = Regex("(\\d{1,2}):(\\d{1,2})").find(t)
            if (m != null) {
                val h = m.groupValues[1].toIntOrNull() ?: 0
                val mm = m.groupValues[2].toIntOrNull() ?: 0
                if (h in 0..23 && mm in 0..59) onHm(h, mm)
            }
        },
        singleLine = true,
        modifier = Modifier.padding(start = 8.dp).width(110.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

/** §3.8 Камеры: вкл/выкл, детекция, restream + сохранение/рестарт. */
@Composable
fun AdminCamerasScreen(nav: NavHostController) {
    var cams by remember { mutableStateOf<List<CameraInfo>>(emptyList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try { cams = ConfigParse.cameras(appContainer.client.config()) } catch (_: Exception) {
        }
    }

    Scaffold(topBar = {
        ScreenHeader(stringResource(R.string.settings_cameras), onBack = { nav.popBackStack() }, actions = {
            if (dirty) Button(onClick = {
                scope.launch {
                    try {
                        appContainer.client.configSave()
                        msg = null
                        dirty = false
                    } catch (e: Exception) {
                        msg = appContainer.app.getString(R.string.settings_save_failed, (e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu ?: "?")
                    }
                }
            }) { Text(stringResource(R.string.save)) }
        })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            msg?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cams.size) { i ->
                    val cam = cams[i]
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(cam.name, style = MaterialTheme.typography.titleMedium)
                            ToggleRow(stringResource(R.string.live_debug) + " detect", cam.detectEnabled) { on ->
                                scope.launch {
                                    try {
                                        appContainer.client.configSetTyped(mapOf("cameras.${cam.name}.detect.enabled" to on))
                                        cams = cams.toMutableList().also { it[i] = cam.copy(detectEnabled = on) }
                                        dirty = true
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                            ToggleRow("record", cam.recordEnabled) { on ->
                                scope.launch {
                                    try {
                                        appContainer.client.configSetTyped(mapOf("cameras.${cam.name}.record.enabled" to on))
                                        cams = cams.toMutableList().also { it[i] = cam.copy(recordEnabled = on) }
                                        dirty = true
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                            ToggleRow("enabled", cam.enabled) { on ->
                                scope.launch {
                                    try {
                                        appContainer.client.configSetTyped(mapOf("cameras.${cam.name}.enabled" to on))
                                        cams = cams.toMutableList().also { it[i] = cam.copy(enabled = on) }
                                        dirty = true
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** §3.8 Объекты: вкл/выкл классов детекции (objects.track). */
@Composable
fun AdminObjectsScreen(nav: NavHostController) {
    var tracked by remember { mutableStateOf<List<String>>(emptyList()) }
    var all by remember { mutableStateOf<List<String>>(emptyList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val cfg = appContainer.client.config()
            tracked = ConfigParse.objectTrack(cfg)
            all = (tracked + appContainer.client.labels()).distinct().sorted()
        } catch (_: Exception) {
        }
    }

    Scaffold(topBar = { ScreenHeader(stringResource(R.string.settings_objects), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            all.forEach { label ->
                val on = tracked.contains(label)
                ToggleRow(label, on) { enable ->
                    scope.launch {
                        val next = if (enable) (tracked + label) else tracked - label
                        try {
                            appContainer.client.configSetTyped(mapOf("objects.track" to next))
                            tracked = next
                            msg = appContainer.app.getString(R.string.settings_saved)
                        } catch (e: Exception) {
                            msg = errText((e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                scope.launch {
                    try { appContainer.client.configSave() } catch (_: Exception) {
                    }
                }
            }) { Text(stringResource(R.string.save)) }
        }
    }
}

/** §3.8 Категории review по камерам. */
@Composable
fun AdminReviewScreen(nav: NavHostController) {
    var reviewMap by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try { reviewMap = ConfigParse.reviewSettings(appContainer.client.config()) } catch (_: Exception) {
        }
    }

    Scaffold(topBar = { ScreenHeader(stringResource(R.string.settings_review_settings), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            reviewMap.forEach { (cam, cats) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(cam, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("alarm", "detection", "motion").forEach { cat ->
                                val on = cats.contains(cat) ||
                                    (cat == "detection" && cats.isEmpty()) // default в 0.17 — detection
                                FilterChip(modifier = Modifier.height(40.dp), selected = on, onClick = {
                                    scope.launch {
                                        val next = if (on) cats - cat else cats + cat
                                        val key = when (cat) {
                                            "alarm" -> "alerts"
                                            "motion" -> "motion"
                                            else -> "detections"
                                        }
                                        val payload: List<String> = if (next.isEmpty()) listOf("all") else next
                                        try {
                                            appContainer.client.configSetTyped(mapOf("cameras.$cam.review.$key" to payload))
                                            reviewMap = reviewMap + (cam to next)
                                            appContainer.client.configSave()
                                        } catch (e: Exception) {
                                            msg = errText((e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu)
                                        }
                                    }
                                }, label = { Text(cat) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** §3.8 Пользователи и роли (admin). */
@Composable
fun AdminUsersScreen(nav: NavHostController) {
    var users by remember { mutableStateOf<List<UserInfo>>(emptyList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var addDlg by remember { mutableStateOf(false) }
    var pwdFor by remember { mutableStateOf<String?>(null) }
    var confirmDeleteFor by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try { users = appContainer.client.users() } catch (_: Exception) {
        }
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(topBar = {
        ScreenHeader(stringResource(R.string.settings_users), onBack = { nav.popBackStack() }, actions = {
            TextButton(onClick = { addDlg = true }) { Text("+") }
        })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            msg?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(users.size) { i ->
                    val u = users[i]
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(u.username, style = MaterialTheme.typography.titleMedium)
                                Text(u.role, style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary)
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        appContainer.client.setUserRole(u.username, if (u.role == "admin") "viewer" else "admin")
                                        load()
                                    } catch (e: Exception) {
                                        msg = errText((e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu)
                                    }
                                }
                            }) { Text("role ⇄") }
                            TextButton(onClick = { pwdFor = u.username }) { Text(stringResource(R.string.settings_user_password)) }
                            // top10-5 (F5): удаление — только через подтверждение
                            IconButton(onClick = { confirmDeleteFor = u.username }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
                            }
                        }
                    }
                }
            }
        }
    }

    if (addDlg) {
        var uname by remember { mutableStateOf("") }
        var upass by remember { mutableStateOf("") }
        var role by remember { mutableStateOf("viewer") }
        AlertDialog(
            onDismissRequest = { addDlg = false },
            title = { Text(stringResource(R.string.settings_users)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = uname, onValueChange = { uname = it }, label = { Text(stringResource(R.string.conn_login)) }, singleLine = true)
                    OutlinedTextField(value = upass, onValueChange = { upass = it }, label = { Text(stringResource(R.string.conn_password)) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("admin", "viewer").forEach { r ->
                            FilterChip(modifier = Modifier.height(40.dp), selected = role == r, onClick = { role = r }, label = { Text(r) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            appContainer.client.createUser(uname, upass, role)
                            addDlg = false
                            load()
                        } catch (e: Exception) {
                            msg = errText((e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu)
                        }
                    }
                }) { Text(stringResource(R.string.save)) }
            }
        )
    }

    if (confirmDeleteFor != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteFor = null },
            title = { Text(stringResource(R.string.settings_users)) },
            text = { Text(stringResource(R.string.confirm_delete_user_text, confirmDeleteFor!!)) },
            confirmButton = {
                TextButton(onClick = {
                    val name = confirmDeleteFor!!
                    confirmDeleteFor = null
                    scope.launch {
                        try {
                            appContainer.client.deleteUser(name)
                            load()
                        } catch (e: Exception) {
                            msg = errText((e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu)
                        }
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteFor = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (pwdFor != null) {
        var np by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pwdFor = null },
            title = { Text("$pwdFor · " + stringResource(R.string.conn_password)) },
            text = { OutlinedTextField(np, { np = it }, singleLine = true, visualTransformation = PasswordVisualTransformation()) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            appContainer.client.setUserPassword(pwdFor!!, np)
                            pwdFor = null
                        } catch (e: Exception) {
                            msg = errText((e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu)
                        }
                    }
                }) { Text(stringResource(R.string.save)) }
            }
        )
    }
}

/** §3.8 Система: CPU/RAM/storage/detection fps по камерам. */
@Composable
fun SystemScreen(nav: NavHostController) {
    var stats by remember { mutableStateOf<JsonObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try { stats = appContainer.client.stats() } catch (e: Exception) {
            err = errText((e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu ?: "timeout")
        }
    }

    Scaffold(topBar = { ScreenHeader(stringResource(R.string.settings_system), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val st = stats
            if (st == null && err == null) CircularProgressIndicator()
            if (st != null) {
                val cpu = st["cpu"]?.jsonObject
                val mem = st["memory"]?.jsonObject
                val storage = st["storage"]?.jsonObject
                cpu?.let { c ->
                    StatCard(stringResource(R.string.sys_cpu),
                        (c["usage"] as? JsonPrimitive)?.content ?: "—",
                        ((c["average_load"] as? JsonArray)?.joinToString(" ") ?: ""))
                }
                mem?.let { m ->
                    val used = (m["rss"] as? JsonPrimitive)?.doubleOrNull?.let { "%.0f MB".format(it / 1048576) } ?: "—"
                    StatCard(stringResource(R.string.sys_ram), used,
                        (m["total"] as? JsonPrimitive)?.doubleOrNull?.let { "%.0f MB".format(it / 1048576) } ?: "")
                }
                storage?.let { s ->
                    val cap = (s["capacity"] as? JsonPrimitive)?.longOrNull
                    val used = (s["used"] as? JsonPrimitive)?.longOrNull
                    val free = (s["free"] as? JsonPrimitive)?.longOrNull
                    StatCard(stringResource(R.string.sys_storage),
                        cap?.let { "%.1f GB".format(it / 1073741824.0) } ?: "—",
                        "used ${used?.let { "%.1f".format(it / 1073741824.0) } ?: "?"} · free ${free?.let { "%.1f".format(it / 1073741824.0) } ?: "?"} GB")
                }
                val det = st["detection"]?.jsonObject
                det?.get("detection_start")?.let {
                    StatCard(stringResource(R.string.sys_det_fps),
                        (det["inference_speed"] as? JsonPrimitive)?.content?.let { ms -> "$ms мс" } ?: "—", "")
                }
                (st["cameras"] as? JsonObject)?.forEach { (cam, v) ->
                    val c = v as? JsonObject ?: return@forEach
                    StatCard(
                        cam,
                        "cam ${(c["camera_fps"] as? JsonPrimitive)?.content ?: "—"} fps",
                        "proc ${(c["process_fps"] as? JsonPrimitive)?.content ?: "—"} · det ${st["cpu"]?.jsonObject?.get("intel_gpu")?.toString()?.take(10) ?: ""}"
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, sub: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** §3.8 Логи frigate/go2rtc — текстовый просмотр с поиском. */
@Composable
fun LogsScreen(nav: NavHostController) {
    var source by remember { mutableStateOf("frigate") }
    var text by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(source) {
        try { text = appContainer.client.logs(source) } catch (e: Exception) {
            err = errText((e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu ?: "timeout")
        }
    }

    Scaffold(topBar = { ScreenHeader(stringResource(R.string.settings_logs), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(modifier = Modifier.height(40.dp), selected = source == "frigate", onClick = { source = "frigate" }, label = { Text(stringResource(R.string.log_frigate)) })
                FilterChip(modifier = Modifier.height(40.dp), selected = source == "go2rtc", onClick = { source = "go2rtc" }, label = { Text(stringResource(R.string.log_go2rtc)) })
                TextButton(onClick = {
                    scope.launch {
                        try { text = appContainer.client.logs(source) } catch (_: Exception) {
                        }
                    }
                }) { Text(stringResource(R.string.retry)) }
            }
            OutlinedTextField(query, { query = it }, placeholder = { Text("🔍") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            val filtered = if (query.isBlank()) text else text.lines().filter { it.contains(query, true) }.joinToString("\n")
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                val lines = filtered.lines()
                items(lines.size) { i ->
                    Text(lines[i], style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        }
    }
}
