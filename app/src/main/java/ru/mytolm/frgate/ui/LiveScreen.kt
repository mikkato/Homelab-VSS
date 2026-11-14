package ru.mytolm.frgate.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth


import ru.mytolm.frgate.*
import ru.mytolm.frgate.R
import ru.mytolm.frgate.data.LiveMode
import ru.mytolm.frgate.data.toCameraInfo
import ru.mytolm.frgate.frigate.CameraInfo
import ru.mytolm.frgate.util.TimeFmt

/** §3.2 Live: сетка камер grid.jpg с автообновлением; выбор раскладки 1/2/4. */
@Composable
fun LiveScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val ws by appContainer.wsState.collectAsState()
    val settings by appContainer.settings.state.collectAsState()
    val boxesHidden by appContainer.boxesHidden.collectAsState()
    var cameras by remember { mutableStateOf<List<CameraInfo>>(emptyList()) }
    var offline by remember { mutableStateOf(false) }
    var fromCache by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // автомасштаб: портрет — из настройки liveGridCols (F9), ландшафт 4 — всегда 8 слотов
    val config = LocalConfiguration.current
    val gridCols = if (config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 4
    else settings.liveGridCols.coerceIn(1, 4)
    val totalSlots = 8

    // предпросмотр автообновляется каждые 10 с (TTL сетки по ТЗ)
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            tick++
        }
    }

    suspend fun loadCams() {
        try {
            val cfg = appContainer.client.config()
            cameras = ru.mytolm.frgate.frigate.ConfigParse.cameras(cfg)
            offline = false
            fromCache = false
            appContainer.db.cameraDao().upsert(
                cameras.map {
                    ru.mytolm.frgate.data.CameraEntity(it.name, it.enabled, it.detectEnabled, it.recordEnabled, false)
                }
            )
        } catch (e: Exception) {
            // top10-1: сеть легла — читаем кэш камер из Room
            offline = true
            try {
                val cached = appContainer.db.cameraDao().allList().map { it.toCameraInfo() }
                if (cached.isNotEmpty()) { cameras = cached; fromCache = true }
            } catch (_: Exception) { }
        }
    }

    LaunchedEffect(Unit) { loadCams() }

    // обновление миниатюр и списка при активации приложения / возврате на экран
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                tick++
                scope.launch { loadCams() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Scaffold(topBar = {
        ScreenHeader(
            stringResource(R.string.tab_live),
            actions = {
                // Индикатор статуса WS (§3.7)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                    val dot = when (ws) {
                        WsStatus.CONNECTED -> Color(0xFF3E6B5A)
                        WsStatus.CONNECTING -> Color(0xFFE0A82E)
                        WsStatus.DISCONNECTED -> MaterialTheme.colorScheme.error
                    }
                    Surface(shape = RoundedCornerShape(50), color = dot, modifier = Modifier.size(8.dp)) {}
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (ws == WsStatus.CONNECTED) stringResource(R.string.live_online)
                        else stringResource(R.string.live_offline, "—"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OfflineBanner(offline)
            CachedBadge(fromCache)
            if (cameras.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (offline) Text(stringResource(R.string.offline_banner))
                    else CircularProgressIndicator()
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridCols),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(totalSlots, key = { it }) { i ->
                        if (i < cameras.size) {
                            val cam = cameras[i]
                            Column(
                                Modifier
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                                    .clickable { nav.navigate(Routes.camera(cam.name)) }
                            ) {
                                CameraSnapshot(cam.name, tick, boxesHidden)
                                Text(
                                    cam.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        } else {
                            // пустой слот: контрастная надпись; структура как у живой карточки
                            // (кадр 16:9 + строка подписи), иначе ряды гуляют по высоте
                            Column(Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "НЕТ ВИДЕО",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        maxLines = 2
                                    )
                                }
                                Text(
                                    " ",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Раскрытая камера: live-поток (HTTP/WebRTC с автофолбэком), звук, Debug-статистика, PTZ. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraDetailScreen(nav: NavHostController, camera: String) {
    var mode by remember { mutableStateOf(appContainer.settings.current.liveMode) }
    var showWebRtcWarn by remember { mutableStateOf(mode == LiveMode.WEBRTC) }
    var debugStats by remember { mutableStateOf<String?>(null) }
    var ptzFeatures by remember { mutableStateOf<List<String>>(emptyList()) }
    var ptzPresets by remember { mutableStateOf<List<String>>(emptyList()) }
    var hasSub by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf("high") }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val kb = LocalSoftwareKeyboardController.current

    LaunchedEffect(camera) {
        try {
            val streams = appContainer.client.go2rtcStreams()
            hasSub = streams.keys.any { it == "$camera-sub" }
        } catch (_: Exception) {
        }
        try {
            val info = appContainer.client.ptzInfo(camera)
            if (info != null) {
                ptzFeatures = (info["features"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: emptyList()
                ptzPresets = (info["presets"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: emptyList()
            }
        } catch (_: Exception) {
        }
    }

    BackHandler { nav.popBackStack() }

    Scaffold(topBar = {
        ScreenHeader(camera, onBack = { nav.popBackStack() }, actions = {
            // переключатель способа трансляции в шапке камеры (§3.2)
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = mode == LiveMode.HTTP,
                    onClick = { mode = LiveMode.HTTP },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text(stringResource(R.string.live_mode_http), fontSize = MaterialTheme.typography.labelMedium.fontSize) }
                SegmentedButton(
                    selected = mode == LiveMode.WEBRTC,
                    onClick = { mode = LiveMode.WEBRTC; showWebRtcWarn = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text(stringResource(R.string.live_mode_webrtc), fontSize = MaterialTheme.typography.labelMedium.fontSize) }
            }
            IconButton(onClick = {
                scope.launch {
                    debugStats = try {
                        val st = appContainer.client.stats()
                        val cams = st["cameras"]?.let { it as? kotlinx.serialization.json.JsonObject }?.get(camera)
                            as? kotlinx.serialization.json.JsonObject
                        fun f(k: String) = (cams?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "—"
                        "cam fps ${f("camera_fps")} · det fps ${f("process_fps")}"
                    } catch (e: Exception) { errText((e as? ru.mytolm.frgate.frigate.FrigateApiException)?.messageRu ?: "timeout") }
                }
            }) { Text("📊") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // предупреждение WebRTC (§3.2) — показывается при выборе режима
            if (mode == LiveMode.WEBRTC) {
                Text(
                    stringResource(R.string.webrtc_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            when (mode) {
                LiveMode.HTTP -> HttpLivePlayer(camera = camera, quality = if (quality == "sub" && hasSub) "sub" else "high", hasSub = hasSub, onToggleQuality = { quality = if (quality == "high") "sub" else "high" })
                LiveMode.WEBRTC -> WebRtcLivePlayer(
                    camera = camera,
                    onFallbackToHttp = {
                        mode = LiveMode.HTTP // не переписываем настройку пользователя навсегда
                    }
                )
            }
            debugStats?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (ptzFeatures.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                PtzPanel(camera, ptzFeatures, ptzPresets)
            }
        }
    }

    if (showWebRtcWarn && mode == LiveMode.WEBRTC) {
        AlertDialog(
            onDismissRequest = { showWebRtcWarn = false },
            title = { Text("WebRTC") },
            text = { Text(stringResource(R.string.webrtc_warning)) },
            confirmButton = { TextButton(onClick = { showWebRtcWarn = false }) { Text(stringResource(R.string.ok)) } }
        )
    }
}

/** HTTP live: прогрессивный fMP4 от go2rtc через ExoPlayer (аудио по умолчанию выкл). */
@Composable
fun HttpLivePlayer(camera: String, quality: String, hasSub: Boolean, onToggleQuality: () -> Unit) {
    val ctx = LocalContext.current
    var audioOn by remember { mutableStateOf(false) }
    val player = remember {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    ru.mytolm.frgate.MediaSources.okHttpDataSourceFactory()
                )
            )
            .build().apply { playWhenReady = true; volume = 0f }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    var playError by remember { mutableStateOf<String?>(null) }
    var retries by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                playError = e.errorCodeName
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    LaunchedEffect(camera, quality) {
        playError = null; retries = 0; playing = false
        val src = if (quality == "sub") "$camera-sub" else camera
        val url = appContainer.client.liveMp4Url(src)
        player.setMediaItem(
            MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_MP4)
                .build()
        )
        player.prepare()
        // нет кадра 12 с — переподготавливаем (эмулятор/слабая сеть: декодер или буфер не поднялись)
        var startMs = android.os.SystemClock.elapsedRealtime()
        while (!playing && retries < 5) {
            delay(1500)
            playing = player.isPlaying
            if (!playing && android.os.SystemClock.elapsedRealtime() - startMs > 12_000) {
                retries++
                startMs = android.os.SystemClock.elapsedRealtime()
                player.prepare()
            }
        }
    }

    Column {
        Box {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { it.player = player },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            )
            if (!playing) {
                Column(
                    Modifier.matchParentSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (playError == null) CircularProgressIndicator(
                        Modifier.size(26.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        playError?.let { stringResource(R.string.live_stream_error, it) }
                            ?: stringResource(R.string.live_buffering, retries + 1),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val label = if (audioOn) R.string.live_audio_off else R.string.live_audio_on
            OutlinedButton(modifier = Modifier.weight(1f).height(44.dp), onClick = {
                audioOn = !audioOn
                player.volume = if (audioOn) 1f else 0f
            }) { Text(stringResource(label), maxLines = 1, softWrap = false, fontSize = 13.sp) }
            if (hasSub) {
                OutlinedButton(modifier = Modifier.weight(1f).height(44.dp), onClick = onToggleQuality) {
                    Text(if (quality == "high") "Высокое" else "Пониженное", maxLines = 1, softWrap = false, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * WebRTC (экспериментально, §3.2): SDP-offer → POST /api/go2rtc/api/webrtc, ICE без STUN
 * (host-candidate достаточно при прямом пробросе 8555). Нет первого кадра за 10 с → фолбэк HTTP.
 */
@Composable
fun WebRtcLivePlayer(camera: String, onFallbackToHttp: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var firstFrame by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val eglBase = remember { EglBase.create() }
    val sinkProxy = remember {
        object : VideoSink {
            override fun onFrame(frame: VideoFrame) { firstFrame = true }
        }
    }
    val surfaceView = remember {
        SurfaceViewRenderer(ctx).apply {
            init(eglBase.eglBaseContext, null)
            setEnableHardwareScaler(true)
        }
    }

    val factory = remember {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions()
        )
        PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    var pc by remember { mutableStateOf<PeerConnection?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try { pc?.close() } catch (_: Exception) {}
            try { factory.dispose() } catch (_: Exception) {}
            surfaceView.release()
            eglBase.release()
        }
    }

    fun fallback() {
        android.widget.Toast.makeText(ctx, ctx.getString(R.string.webrtc_fallback), android.widget.Toast.LENGTH_LONG).show()
        onFallbackToHttp()
    }

    LaunchedEffect(camera) {
        try {
            val observer = object : PeerConnection.Observer {
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(c: IceCandidate?) {}
                override fun onIceCandidatesRemoved(a: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dc: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    (receiver?.track() as? VideoTrack)?.let {
                        it.addSink(surfaceView)
                        it.addSink(sinkProxy)
                    }
                }
            }
            val conf = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            val conn = factory.createPeerConnection(conf, observer) ?: error("pc null")
            pc = conn
            conn.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            conn.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            val offerObserver = object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    val d = desc ?: return
                    scope.launch {
                        try {
                            val body = org.json.JSONObject()
                                .put("type", "offer")
                                .put("sdp", d.description).toString()
                            val req = okhttp3.Request.Builder()
                                .url(appContainer.client.webrtcUrl(camera))
                                .post(body.toRequestBody("application/json".toMediaType()))
                                .build()
                            val text = withContext(Dispatchers.IO) {
                                appContainer.client.streamClient.newCall(req).execute().use { r ->
                                    if (!r.isSuccessful) error("signaling ${'$'}{r.code}")
                                    r.body?.string() ?: error("empty")
                                }
                            }
                            val answerSdp = org.json.JSONObject(text).getString("sdp")
                            conn.setLocalDescription(object : SdpObserver { override fun onCreateSuccess(sdp: SessionDescription?) {} override fun onCreateFailure(msg: String?) {} override fun onSetSuccess() {} override fun onSetFailure(msg: String?) {} }, d)
                            conn.setRemoteDescription(
                                object : SdpObserver {
                                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                                    override fun onCreateFailure(msg: String?) {}
                                    override fun onSetSuccess() {}
                                    override fun onSetFailure(msg: String?) { failed = true }
                                },
                                SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
                            )
                        } catch (e: Exception) {
                            failed = true
                        }
                    }
                }
                override fun onCreateFailure(msg: String?) { failed = true }
                override fun onSetSuccess() {}
                override fun onSetFailure(msg: String?) { if (msg != null) failed = true }
            }
            conn.createOffer(offerObserver, MediaConstraints())

            var waited = 0
            while (waited < 10 && !firstFrame && !failed) {
                delay(1000)
                waited++
            }
            if (!firstFrame) {
                failed = true
                fallback()
            }
        } catch (e: Exception) {
            failed = true
            fallback()
        }
    }

    AndroidView(
        factory = { surfaceView },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black)
    )
    if (!firstFrame && !failed) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** В libwebrtc-125 (webrtc-sdk) нет SimpleSdpObserver — объявляем свой. */
private open class SdpObs(
    private val onCreate: (SessionDescription?) -> Unit = {},
    private val onFail: (String?) -> Unit = {}
) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) = onCreate(sdp)
    override fun onCreateFailure(msg: String?) = onFail(msg)
    override fun onSetSuccess() {}
    override fun onSetFailure(msg: String?) = onFail(msg)
}

/** Снапшот плитки: grid.jpg → latest.jpg → плейсхолдер (grid отсутствует без детекции, §3.1). */
@Composable
fun CameraSnapshot(camera: String, tick: Int, hideBoxes: Boolean = false) {
    var stage by remember(camera, hideBoxes) { mutableStateOf(0) }
    // «Принять» -> latest.jpg?bbox=0: grid.jpg всегда сburned-рамками, bbox поддерживает latest
    val url = if (hideBoxes) when (stage) {
        0 -> appContainer.client.latestUrl(camera) + "?t=$tick&bbox=0"
        1 -> appContainer.client.gridUrl(camera) + "?t=$tick"
        else -> null
    } else when (stage) {
        0 -> appContainer.client.gridUrl(camera) + "?t=$tick"
        1 -> appContainer.client.latestUrl(camera) + "?t=$tick"
        else -> null
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (url != null) {
            AsyncImage(
                imageLoader = appContainer.imageLoader,
                model = url,
                contentDescription = camera,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
                onError = { stage += 1 }
            )
        } else {
            Icon(
                Icons.Outlined.VideocamOff, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
