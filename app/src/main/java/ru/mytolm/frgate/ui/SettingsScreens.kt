package ru.mytolm.frgate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
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
import ru.mytolm.frgate.frigate.CameraReviewState
import ru.mytolm.frgate.frigate.ConfigParse
import ru.mytolm.frgate.frigate.UserInfo

/** §3.8 Настройки. */
@Composable
fun SettingsScreen(nav: NavHostController) {
    val s = appContainer.settings.state.collectAsState().value
    val role by appContainer.userRole.collectAsState()
    var version by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var crashNote by remember { mutableStateOf<String?>(null) }
    var confirmRestart by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        version = try { appContainer.client.version() } catch (e: Exception) { null }
        appContainer.refreshRole() // top10-5 (F6)
        // Если приложение падало — показать дамп прямо на экране: с телефона стек
        // иначе недоступен (release не debuggable), а без стека падение не починить.
        crashNote = run {
            val files = ru.mytolm.frgate.util.Diag.pendingCrashFiles()
            if (files.isEmpty()) null
            else files.last().let { f ->
                val head = runCatching {
                    f.readLines().let { ls ->
                        val i = ls.indexOfFirst { it.startsWith("=== exception ===") }
                        ls.drop(i + 1).take(8).joinToString("\n")
                    }
                }.getOrDefault(f.name)
                "Приложение падало (${f.name.removePrefix("diag-crash-").removeSuffix(".log")}). Зайди: Настройки → Логи → «Приложение» — там полный стек. Отправь этот текст.\n\n$head"
            }
        }
    }

    Scaffold(topBar = { ScreenHeader(stringResource(R.string.tab_settings)) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            crashNote?.let {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { crashNote = null }) { Text("Скрыть") }
                    }
                }
            }

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

            var paletteExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { paletteExpanded = true }) {
                    Text(stringResource(R.string.settings_palette) + ": " + when (s.palette) {
                        ru.mytolm.frgate.data.ThemePalette.PLAUD -> stringResource(R.string.palette_plaud)
                        ru.mytolm.frgate.data.ThemePalette.DYNAMIC -> stringResource(R.string.palette_dynamic)
                    }, maxLines = 1, softWrap = false)
                }
                DropdownMenu(paletteExpanded, onDismissRequest = { paletteExpanded = false }) {
                    listOf(ru.mytolm.frgate.data.ThemePalette.PLAUD to R.string.palette_plaud,
                           ru.mytolm.frgate.data.ThemePalette.DYNAMIC to R.string.palette_dynamic).forEach { (p, res) ->
                        DropdownMenuItem(text = { Text(stringResource(res)) }, onClick = {
                            appContainer.settings.update { it.copy(palette = p) }
                            paletteExpanded = false
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
                // «Движение» убрано: notifications строятся из объектов (alert/detection),
                // motion-объектных событий нет — чип был ловушкой («только движение» = всё равно дёрет).
                listOf("alarm" to R.string.cat_alarm, "detection" to R.string.cat_detection)
                    .forEach { (cat, res) ->
                        FilterChip(modifier = Modifier.minimumInteractiveComponentSize(), selected = s.notifCategories.contains(cat), onClick = {
                                // от `it`, а не от снимка `s` — без потери быстрых тапов
                                appContainer.settings.update { st ->
                                    val cur = st.notifCategories.toMutableSet()
                                    if (cur.contains(cat)) cur.remove(cat) else cur.add(cat)
                                    st.copy(notifCategories = cur)
                                }
                            }, label = { Text(stringResource(res)) })
                    }
            }
            if (cameras.isNotEmpty()) {
                Text(stringResource(R.string.notif_filter_cameras), style = MaterialTheme.typography.titleSmall)
                FlowRowWrap {
                    cameras.forEach { c ->
                        FilterChip(modifier = Modifier.minimumInteractiveComponentSize(), selected = s.notifCameras.contains(c) || s.notifCameras.isEmpty(), onClick = {
                            appContainer.settings.update { st ->
                                val cur = if (st.notifCameras.isEmpty()) cameras.toMutableSet() else st.notifCameras.toMutableSet()
                                if (cur.contains(c)) cur.remove(c) else cur.add(c)
                                // «пусто = все» — снимать последнюю камеру нельзя (иначе фильтр
                                // отключится и шлёт все); полное множество нормализуем в «все»
                                when {
                                    cur.isEmpty() -> st
                                    cur.size == cameras.size -> st.copy(notifCameras = emptySet())
                                    else -> st.copy(notifCameras = cur)
                                }
                            }
                        }, label = { Text(ru.mytolm.frgate.util.camName(c)) })
                    }
                }
            }
            if (labels.isNotEmpty()) {
                Text(stringResource(R.string.notif_filter_labels), style = MaterialTheme.typography.titleSmall)
                FlowRowWrap {
                    labels.forEach { l ->
                        FilterChip(modifier = Modifier.minimumInteractiveComponentSize(), selected = s.notifLabels.contains(l) || s.notifLabels.isEmpty(), onClick = {
                            appContainer.settings.update { st ->
                                val cur = if (st.notifLabels.isEmpty()) labels.toMutableSet() else st.notifLabels.toMutableSet()
                                if (cur.contains(l)) cur.remove(l) else cur.add(l)
                                // «пусто = все» — снятие последнего класса не должно включать все
                                when {
                                    cur.isEmpty() -> st
                                    cur.size == labels.size -> st.copy(notifLabels = emptySet())
                                    else -> st.copy(notifLabels = cur)
                                }
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
                // top10-7: нативный M3 TimePicker вместо ручного «чч:мм» поля
                var editingFrom by remember { mutableStateOf(true) }
                var fromH by remember { mutableIntStateOf(if (s.quietFrom >= 0) s.quietFrom / 60 else 22) }
                var fromM by remember { mutableIntStateOf(if (s.quietFrom >= 0) s.quietFrom % 60 else 0) }
                var toH by remember { mutableIntStateOf(if (s.quietTo >= 0) s.quietTo / 60 else 7) }
                var toM by remember { mutableIntStateOf(if (s.quietTo >= 0) s.quietTo % 60 else 0) }
                key(editingFrom) {
                    val st = rememberTimePickerState(if (editingFrom) fromH else toH, if (editingFrom) fromM else toM, is24Hour = true)
                    fun stash() {
                        if (editingFrom) { fromH = st.hour; fromM = st.minute } else { toH = st.hour; toM = st.minute }
                    }
                    AlertDialog(
                        onDismissRequest = { quietDlg = false },
                        title = { Text(stringResource(R.string.notif_quiet)) },
                        text = {
                            Column {
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    SegmentedButton(editingFrom, { stash(); editingFrom = true },
                                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)) {
                                        Text(stringResource(R.string.notif_quiet_from))
                                    }
                                    SegmentedButton(!editingFrom, { stash(); editingFrom = false },
                                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)) {
                                        Text(stringResource(R.string.notif_quiet_to))
                                    }
                                }
                                TimePicker(state = st)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                stash()
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
                            }) { Text(stringResource(R.string.notif_quiet_off)) }
                        }
                    )
                }
            }
        }
    }
}

/** Настоящий FlowRow (top10-7) вместо Column-заглушки: чипы переносятся, а не влезают в одну строку. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowRowWrap(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) { content() }
}

/** §3.8 Камеры: вкл/выкл, детекция, restream.
 *  В 0.17 config/set сам пишет config.yml — отдельная «Сохранить» (config/save) не нужна;
 *  она к тому же сломана (0.17 требует save_option + YAML-тело) и давала «Не сохранено: ?».
 *  Смена detect/record применяется Frigate после перезапуска — говорим о нём прямо. */
@Composable
fun AdminCamerasScreen(nav: NavHostController) {
    var cams by remember { mutableStateOf<List<CameraInfo>>(emptyList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    // псевдоним камеры: (техническое имя, черновик); null = диалог закрыт
    var aliasDraft by remember { mutableStateOf<Pair<String, String>?>(null) }
    val settings = appContainer.settings.state.collectAsState().value
    val scope = rememberCoroutineScope()

    // Применение через live-топик + READ-BACK: перечитываем /api/config (после
    // update_topic Frigate отдаёт в нём уже НОВОЕ runtime-значение) и говорим
    // «Применено» только когда сервер подтвердил. Никаких «нажмите перезапуск».
    val applyAndVerify: suspend (String, Boolean?, Boolean?) -> Unit = { name, detect, record ->
        cams = cams.toMutableList().also { l ->
            val idx = l.indexOfFirst { it.name == name }
            if (idx >= 0) l[idx] = l[idx].copy(
                detectEnabled = detect ?: l[idx].detectEnabled,
                recordEnabled = record ?: l[idx].recordEnabled
            )
        }
        msg = "Применено."
        try {
            val cfg = appContainer.client.config()
            val fresh = ConfigParse.cameras(cfg).firstOrNull { it.name == name }
            val mismatch = fresh != null &&
                ((detect != null && fresh.detectEnabled != detect) ||
                    (record != null && fresh.recordEnabled != record))
            if (mismatch) {
                msg = "Сервер не подтвердил новое значение — обновите экран; если повторится, перезапустите Frigate в начале «Настроек»."
            } else if (detect == true && ConfigParse.objectTrack(cfg).isEmpty()) {
                // Ловушка 0.17: при пустом objects.track детекция включена,
                // но объекты не ищутся вовсе — детекций нет.
                msg = "Применено. Внимание: пуст список отслеживаемых объектов — " +
                    "детекция будет молчать. Добавьте объекты в «Объекты»."
            }
        } catch (_: Exception) { }
    }

    LaunchedEffect(Unit) {
        try { cams = ConfigParse.cameras(appContainer.client.config()) } catch (_: Exception) {
        }
    }

    Scaffold(topBar = {
        ScreenHeader(stringResource(R.string.settings_cameras), onBack = { nav.popBackStack() })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            msg?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cams.size) { i ->
                    val cam = cams[i]
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(ru.mytolm.frgate.util.camName(cam.name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = { aliasDraft = cam.name to (settings.cameraAliases[cam.name] ?: "") }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.alias_edit))
                                }
                            }
                            ToggleRow(stringResource(R.string.live_debug) + " detect", cam.detectEnabled) { on ->
                                scope.launch {
                                    try {
                                        // Frigate 0.17 (frigate/video.py): процесс детекции
                                        // подписан на топик config/cameras/<cam>/detect и
                                        // перечитывает detect.enabled на каждом кадре —
                                        // применяется НАЛЕТУ, рестарт не нужен. Без
                                        // update_topic публикация не идёт, и значение
                                        // «не применялось» до перезапуска (баг 2.1.6-).
                                        appContainer.client.configSetLive(
                                            "cameras.${cam.name}.detect.enabled", on,
                                            "config/cameras/${cam.name}/detect"
                                        )
                                        applyAndVerify(cam.name, on, null)
                                    } catch (e: Exception) {
                                        msg = errText((e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu)
                                    }
                                }
                            }
                            ToggleRow("record", cam.recordEnabled) { on ->
                                scope.launch {
                                    try {
                                        // video_output.py подписан на record+enabled — тоже налету
                                        appContainer.client.configSetLive(
                                            "cameras.${cam.name}.record.enabled", on,
                                            "config/cameras/${cam.name}/record"
                                        )
                                        applyAndVerify(cam.name, null, on)
                                    } catch (e: Exception) {
                                        msg = errText((e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu)
                                    }
                                }
                            }
                            ToggleRow("enabled", cam.enabled) { on ->
                                scope.launch {
                                    try {
                                        // enabled применяется на лету — у review-процессов
                                        // есть подписка на топик enabled камеры
                                        appContainer.client.configSetLive(
                                            "cameras.${cam.name}.enabled", on,
                                            "config/cameras/${cam.name}/enabled"
                                        )
                                        cams = cams.toMutableList().also { it[i] = cam.copy(enabled = on) }
                                        msg = "Сохранено."
                                    } catch (e: Exception) {
                                        msg = errText((e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // Диалог псевдонима: только в приложении, конфиг Frigate не трогаем
    aliasDraft?.let { (cam, draft) ->
        var text by remember(draft) { mutableStateOf(draft) }
        AlertDialog(
            onDismissRequest = { aliasDraft = null },
            title = { Text(stringResource(R.string.alias_title, cam)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        label = { Text(stringResource(R.string.alias_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.alias_note, cam),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    appContainer.settings.update { s ->
                        val m = s.cameraAliases.toMutableMap()
                        if (text.isBlank()) m.remove(cam) else m[cam] = text.trim()
                        s.copy(cameraAliases = m)
                    }
                    ru.mytolm.frgate.util.Diag.log("alias.set", "camera=$cam alias=${text.trim()}")
                    aliasDraft = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { aliasDraft = null }) { Text(stringResource(R.string.cancel)) } }
        )
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
        }
    }
}

/**
 * §3.8 Категории обзора по камерам (Frigate 0.17).
 * alerts/detections — объекты review.alerts.enabled / review.detections.enabled (bool);
 * motion — камерный motion.enabled (review.motion в 0.17 не существует).
 * Пишется через config/set + update_topic (применяется на лету, без рестарта;
 * config/save не нужен — Frigate сам пишет config.yml).
 * Внимание: motion off + detect on = невалидный конфиг (Frigate 400) — блокируем.
 */
@Composable
fun AdminReviewScreen(nav: NavHostController) {
    var cams by remember { mutableStateOf<List<CameraReviewState>>(emptyList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try { cams = ConfigParse.reviewStates(appContainer.client.config()) } catch (_: Exception) {
        }
    }

    fun setCategory(cam: CameraReviewState, cat: String, on: Boolean) {
        scope.launch {
            msg = null
            if (cat == "motion" && !on && cam.detectEnabled) {
                msg = "Движение нельзя выключить при включённой детекции (Frigate: детекция требует движение). Сначала выключите detect у камеры."
                return@launch
            }
            val (path, topic) = when (cat) {
                "alarm" -> "cameras.${cam.name}.review.alerts.enabled" to "config/cameras/${cam.name}/review"
                "motion" -> "cameras.${cam.name}.motion.enabled" to "config/cameras/${cam.name}/motion"
                else -> "cameras.${cam.name}.review.detections.enabled" to "config/cameras/${cam.name}/review"
            }
            try {
                appContainer.client.configSetLive(path, on, topic)
                cams = cams.map {
                    if (it.name != cam.name) it else when (cat) {
                        "alarm" -> it.copy(alertsEnabled = on)
                        "motion" -> it.copy(motionEnabled = on)
                        else -> it.copy(detectionsEnabled = on)
                    }
                }
            } catch (e: Exception) {
                msg = errText((e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu)
            }
        }
    }

    Scaffold(topBar = { ScreenHeader(stringResource(R.string.settings_review_settings), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            cams.forEach { cam ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(ru.mytolm.frgate.util.camName(cam.name), style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("alarm" to cam.alertsEnabled,
                                   "detection" to cam.detectionsEnabled,
                                   "motion" to cam.motionEnabled).forEach { (cat, on) ->
                                FilterChip(modifier = Modifier.minimumInteractiveComponentSize(), selected = on, onClick = {
                                    setCategory(cam, cat, !on)
                                }, label = {
                                    Text(stringResource(when (cat) {
                                        "alarm" -> R.string.cat_alarm
                                        "motion" -> R.string.cat_motion
                                        else -> R.string.cat_detection
                                    }))
                                })
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
            IconButton(onClick = { addDlg = true }) { Icon(Icons.Outlined.PersonAdd, contentDescription = stringResource(R.string.cd_add_user)) }
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
                            }) { Text(stringResource(R.string.settings_role)) }
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
                            FilterChip(modifier = Modifier.minimumInteractiveComponentSize(), selected = role == r, onClick = { role = r }, label = { Text(r) })
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
    var savedMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(source) {
        // «Приложение»: локальный diag-журнал + crash-дампы (readable even when
        // сервер недоступен; переживает native-крах — см. Diag.marker)
        if (source == "app") {
            text = buildString {
                ru.mytolm.frgate.util.Diag.pendingCrashFiles().forEach { f ->
                    append("### ").append(f.name).append('\n')
                    append(runCatching { f.readText() }.getOrDefault("")).append('\n')
                }
                val trace = java.io.File(ctx.filesDir, "diag/diag-trace.log")
                append("### diag-trace (последние шаги)\n")
                append(runCatching {
                    trace.readLines().takeLast(400).joinToString("\n")
                }.getOrDefault(""))
            }
            return@LaunchedEffect
        }
        try { text = appContainer.client.logs(source) } catch (e: Exception) {
            err = errText((e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu ?: "timeout")
        }
    }

    Scaffold(topBar = { ScreenHeader(stringResource(R.string.settings_logs), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(modifier = Modifier.minimumInteractiveComponentSize(), selected = source == "frigate", onClick = { source = "frigate" }, label = { Text(stringResource(R.string.log_frigate)) })
                FilterChip(modifier = Modifier.minimumInteractiveComponentSize(), selected = source == "go2rtc", onClick = { source = "go2rtc" }, label = { Text(stringResource(R.string.log_go2rtc)) })
                FilterChip(modifier = Modifier.minimumInteractiveComponentSize(), selected = source == "app", onClick = { source = "app" }, label = { Text("Приложение") })
                TextButton(onClick = {
                    scope.launch {
                        if (source == "app") {
                            text = ru.mytolm.frgate.util.Diag.pendingCrashFiles().joinToString("\n") { f ->
                                "### ${f.name}\n" + runCatching { f.readText() }.getOrDefault("")
                            }
                        } else try { text = appContainer.client.logs(source) } catch (_: Exception) {
                        }
                    }
                }) { Text(stringResource(R.string.retry)) }
            }
            // Экспорт: скопировать в буфер / сохранить .txt в Загрузки (MediaStore — без разрешений)
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("frigate-logs", text))
                    savedMsg = "Скопировано в буфер обмена"
                }, enabled = text.isNotBlank()) { Text("Копировать") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val name = "frigate-$source-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}.txt"
                        val uri = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                        }.let { ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, it) }
                        if (uri == null) { savedMsg = "Не удалось сохранить"; return@launch }
                        val io = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                                name
                            }
                        }
                        savedMsg = io.getOrNull()?.let { "Сохранено в Загрузки: $it" } ?: "Ошибка записи файла"
                    }
                }, enabled = text.isNotBlank()) { Text("Сохранить в Загрузки") }
            }
            savedMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(query, { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.cd_search)) },
                placeholder = { Text(stringResource(R.string.log_search_hint)) },
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
