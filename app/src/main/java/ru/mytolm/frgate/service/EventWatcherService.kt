package ru.mytolm.frgate.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Response
import ru.mytolm.frgate.AppContainer
import ru.mytolm.frgate.MainActivity
import ru.mytolm.frgate.R
import ru.mytolm.frgate.WsStatus
import ru.mytolm.frgate.appContainer
import ru.mytolm.frgate.data.EventEntity
import ru.mytolm.frgate.frigate.FrigateEvent
import ru.mytolm.frgate.frigate.WsParser
import ru.mytolm.frgate.util.TimeFmt
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

class EventWatcherService : android.app.Service() {

    private lateinit var app: AppContainer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectDelaySec = 1.0
    @Volatile private var stopped = false
    @Volatile private var offlineSince = 0L
    private var lastNotifyIds = mutableSetOf<String>() // дедуп within session

    override fun onCreate() {
        super.onCreate()
        app = appContainer
        createChannels()
        registerNetworkCallback()
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // сначала startForeground (5 с лимит), сетевой логин — уже в фоне
        startForegroundInternal()
        connect()
        return START_STICKY
    }

    private fun startForegroundInternal() {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setContentTitle(getString(R.string.notif_service_title))
            .setSmallIcon(R.drawable.ic_stat_cam)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(tapIntent)
            .build()
        ServiceCompatStartForeground(notif)
    }

    private fun ServiceCompatStartForeground(n: Notification) {
        try {
            // remoteMessaging: dataSync на Android 16 живёт 6 ч/сутки и роняет сервис
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } catch (e: Exception) {
            android.util.Log.e("FRGateSvc", "startForeground failed", e)
            // лимит/нет типа — не падаем: WorkManager-сторож поднимет позже
            try { startForeground(NOTIF_ID, n) } catch (e2: Exception) {
                android.util.Log.e("FRGateSvc", "plain startForeground failed too", e2)
                stopSelf()
            }
        }
    }

    // ---------- Адаптивный polling (top10-2) ----------
    // Frigate 0.17 не отдаёт WS за Basic-auth + JWT; polling проверен боем.
    // 5 с на экране / 60+ с в фоне; lastPollSec персистится; review — раз в 60 с.

    private val pollHandler by lazy { Handler(mainLooper) }
    private var lastPollSec = 0L
    private var lastReviewMs = 0L
    @Volatile private var screenOn = true
    @Volatile private var pollingScheduled = false

    private val runtimePrefs by lazy { getSharedPreferences("runtime", Context.MODE_PRIVATE) }

    private fun currentIntervalMs(): Long = if (screenOn) POLL_INTERVAL_MS else POLL_INTERVAL_BG_MS

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val on = intent?.action == Intent.ACTION_SCREEN_ON
            if (on == screenOn) return
            screenOn = on
            if (on) {
                // экран включили — мгновенно подтянуть свежее, дальше быстрый шаг
                pollHandler.removeCallbacksAndMessages(null)
                pollOnce()
            } else {
                reschedule(currentIntervalMs())
            }
        }
    }

    private fun registerScreenReceiver() {
        try {
            screenOn = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
            ContextCompat.registerReceiver(
                this, screenReceiver,
                android.content.IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {
        }
    }

    private fun persistLastPoll() {
        runtimePrefs.edit().putLong(KEY_LAST_POLL, lastPollSec).apply()
    }

    private fun connect() {
        if (stopped) return
        if (lastPollSec == 0L) {
            // top10-2 (F3): восстановление точки опроса после рестарта сервиса — без повторов нотификаций
            val saved = runtimePrefs.getLong(KEY_LAST_POLL, 0L)
            lastPollSec = if (saved > 0) saved else System.currentTimeMillis() / 1000 - 60
        }
        app.setWsState(WsStatus.CONNECTING)
        if (pollingScheduled) return // F4: поллинг уже идёт — не пложим циклы
        pollOnce()
    }

    private fun pollOnce() {
        if (stopped) return
        pollingScheduled = true
        scope.launch {
            try {
                val list = app.client.events(since = lastPollSec, limit = 50)
                reconnectDelaySec = 1.0
                offlineSince = 0L
                app.setWsState(WsStatus.CONNECTED)
                val nowSec = System.currentTimeMillis() / 1000
                val toNotify = mutableListOf<FrigateEvent>()
                list.sortedBy { it.start_time }.forEach { ev ->
                    if (ev.start_time.toLong() > lastPollSec) lastPollSec = ev.start_time.toLong()
                    try { onEvent(ev, nowSec, toNotify) } catch (_: Exception) { }
                }
                persistLastPoll()
                dispatchNotifications(toNotify)
                // зелёные рамки на плитках: снимаем раз в 60 с (F2 — не каждый цикл)
                if (System.currentTimeMillis() - lastReviewMs > REVIEW_INTERVAL_MS) {
                    lastReviewMs = System.currentTimeMillis()
                    try {
                        val reviews = app.client.review()
                        app.boxesHidden.value = reviews.none { !it.viewed }
                    } catch (_: Exception) { }
                }
                reschedule(currentIntervalMs())
            } catch (_: Exception) {
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        offlineSince = System.currentTimeMillis()
        app.setWsState(WsStatus.DISCONNECTED)
        val delay = maxOf(currentIntervalMs() / 1000.0, min(60.0, reconnectDelaySec))
        reconnectDelaySec = min(60.0, reconnectDelaySec * 2)
        reschedule((delay * 1000).toLong().coerceAtLeast(5_000L))
    }

    private fun reschedule(ms: Long) {
        if (stopped) return
        pollHandler.removeCallbacksAndMessages(null)
        pollHandler.postDelayed({ pollOnce() }, ms)
    }

    private fun handleMessage(raw: String) {
        when (val msg = WsParser.classify(raw)) {
            null -> { /* malformed / unknown topic — игнор, не падаем */ }
            is WsParser.WsMsg.Event -> {
                val l = mutableListOf<FrigateEvent>()
                onEvent(msg.event, System.currentTimeMillis() / 1000, l)
                dispatchNotifications(l)
            }
            is WsParser.WsMsg.CameraState -> scope.launch {
                try {
                    app.db.cameraDao().upsert(
                        listOf(
                            ru.mytolm.frgate.data.CameraEntity(
                                name = msg.camera, enabled = true,
                                detectEnabled = msg.kind == "detect" && msg.online,
                                recordEnabled = msg.kind == "record" && msg.online,
                                hasSub = false
                            )
                        )
                    )
                } catch (_: Exception) {
                }
            }
            is WsParser.WsMsg.Raw -> { /* noop */ }
        }
    }

    private fun onEvent(ev: FrigateEvent, nowSec: Long, toNotify: MutableList<FrigateEvent>) {
        // Кэш события в Room (офлайн-просмотр последнего состояния)
        scope.launch {
            try {
                app.db.eventDao().upsert(
                    listOf(
                        EventEntity(
                            id = ev.id, camera = ev.camera, label = ev.label,
                            subLabel = ev.sub_label, since = ev.since, endTime = ev.end_time,
                            duration = ev.duration, hasClip = ev.has_clip, hasSnapshot = ev.has_snapshot,
                            zones = ev.zones.joinToString(","), confidence = ev.confidence,
                            retained = ev.retained, falsePositive = ev.false_positive ?: false
                        )
                    )
                )
            } catch (_: Exception) {
            }
        }
        if (!passesFilter(ev)) return
        // Анти-шторм (F3): не будить уведомлениями о том, что произошло давно (после офлайна/рестарта)
        if (nowSec - ev.start_time.toLong() > NOTIFY_FRESH_SEC) return
        if (!lastNotifyIds.add(ev.id)) return
        if (lastNotifyIds.size > 500) lastNotifyIds = mutableSetOf(ev.id)
        toNotify.add(ev)
    }

    /** Кап залпа (F3): до 3 — individual, больше — одна сводная. */
    private fun dispatchNotifications(list: List<FrigateEvent>) {
        if (list.isEmpty()) return
        if (list.size <= NOTIFY_CAP) {
            list.forEach { showNotification(it) }
        } else {
            val tapIntent = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // NB: у InboxStyle есть метод-член apply(...) — он затеняет stdlib apply, поэтому без DSL
            val style = NotificationCompat.InboxStyle()
            list.take(5).forEach { ev ->
                style.addLine("${ev.camera}: ${ev.label} · ${TimeFmt.hm(ev.since)}")
            }
            if (list.size > 5) style.addLine("…")
            val n = NotificationCompat.Builder(this, CHANNEL_DETECTION)
                .setSmallIcon(R.drawable.ic_stat_cam)
                .setContentTitle(getString(R.string.notif_burst_title, list.size))
                .setStyle(style)
                .setAutoCancel(true)
                .setContentIntent(tapIntent)
                .build()
            try { NotificationManagerCompat.from(this).notify(NOTIF_ID + 7, n) } catch (_: Exception) { }
        }
    }

    private fun passesFilter(ev: FrigateEvent): Boolean {
        val s = app.settings.current
        if (!s.notifEnabled) return false
        val cat = if (ev.zones.isNotEmpty() && ev.has_clip) "alarm" else "detection"
        // Категории: событие проходит, если его категория включена (motion-события в 0.17
        // приходят отдельным review-топиком; для event-WS классифицируем по наличию клипа/зоны)
        if (s.notifCategories.isNotEmpty() && !s.notifCategories.contains(cat)) {
            if (!(s.notifCategories.contains("motion") && cat == "detection")) return false
        }
        if (s.notifLabels.isNotEmpty() && !s.notifLabels.contains(ev.label)) return false
        if (s.notifCameras.isNotEmpty() && !s.notifCameras.contains(ev.camera)) return false
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        if (TimeFmt.inQuietHours(nowMin, s.quietFrom, s.quietTo)) return false
        return true
    }

    private fun showNotification(ev: FrigateEvent) {
        scope.launch {
            try {
                var bmp: android.graphics.Bitmap? = null
                try {
                    val f = File(cacheDir, "thumb_${ev.id}.jpg")
                    app.client.download(app.client.absoluteUrl("/api/events/${ev.id}/thumbnail.jpg"), f) { }
                    bmp = BitmapFactory.decodeFile(f.absolutePath)
                    f.delete()
                } catch (_: Exception) {
                }
                val cat = if (ev.zones.isNotEmpty() && ev.has_clip) "alarm" else "detection"
                val channel = when {
                    cat == "alarm" -> CHANNEL_ALARM
                    cat == "detection" -> CHANNEL_DETECTION
                    else -> CHANNEL_MOTION
                }
                val time = TimeFmt.hm(ev.since)
                val tapIntent = PendingIntent.getActivity(
                    this@EventWatcherService, ev.id.hashCode(),
                    Intent(this@EventWatcherService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(MainActivity.EXTRA_EVENT_ID, ev.id)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val n = NotificationCompat.Builder(this@EventWatcherService, channel)
                    .setSmallIcon(R.drawable.ic_stat_cam)
                    .setContentTitle("${ev.camera}: ${ev.label} · $time")
                    .setContentText(ev.sub_label ?: "")
                    .setAutoCancel(true)
                    .setContentIntent(tapIntent)
                    .apply { if (bmp != null) setLargeIcon(bmp) }
                    .build()
                NotificationManagerCompat.from(this@EventWatcherService)
                    .notify(ev.id.hashCode(), n)
            } catch (_: Exception) {
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // смена сети — мгновенный реконнект
                    connect()
                }

                override fun onLost(network: Network) {
                                scheduleReconnect()
                }
            })
        } catch (_: Exception) {
        }
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chans = listOf(
            NotificationChannel(CHANNEL_ALARM, getString(R.string.notif_channel_alarm), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_DETECTION, getString(R.string.notif_channel_detection), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_MOTION, getString(R.string.notif_channel_motion), NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_STATUS, getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannels(chans)
    }

    override fun onDestroy() {
        stopped = true
        pollingScheduled = false
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        Handler(mainLooper).removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ALARM = "frigate_alarm"
        const val CHANNEL_DETECTION = "frigate_detection"
        const val CHANNEL_MOTION = "frigate_motion"
        const val POLL_INTERVAL_MS = 5_000L      // экран включён (top10-2)
        const val POLL_INTERVAL_BG_MS = 60_000L  // экран погашен
        const val REVIEW_INTERVAL_MS = 60_000L
        const val NOTIFY_FRESH_SEC = 120L        // анти-шторм: старше 2 мин — только в кэш
        const val NOTIFY_CAP = 3                 // залп > 3 — одна сводная нотификация
        const val KEY_LAST_POLL = "last_poll_sec"
        const val CHANNEL_STATUS = "frigate_status"
        const val NOTIF_ID = 1001

        fun ensure(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, EventWatcherService::class.java)
                )
            } catch (_: Exception) {
            }
        }

        fun scheduleWatchdog(context: Context) {
            val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("ws_watchdog", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** Раз в 15 мин — проверка живости сервиса (§3.7). F4: один ensure, только когда упало. */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (appContainer.settings.current.configured &&
            appContainer.wsState.value != WsStatus.CONNECTED
        ) {
            EventWatcherService.ensure(applicationContext)
        }
        return Result.success()
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    // top10-4 (P2): goAsync — зашифрованные prefs/Keystore читаются вне main-окна broadcaster
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val app = appContainer
        app.scope.launch {
            try {
                if (app.settings.current.configured) {
                    EventWatcherService.ensure(context)
                    EventWatcherService.scheduleWatchdog(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
