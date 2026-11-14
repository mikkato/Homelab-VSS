package ru.mytolm.frgate

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.fetch.Fetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.mytolm.frgate.data.FrigateDb
import ru.mytolm.frgate.data.SettingsStore
import ru.mytolm.frgate.frigate.FrigateClient
import ru.mytolm.frgate.service.EventWatcherService

class AppContainer(val app: Application) {
    val context: Application get() = app
    // EncryptedSharedPreferences тянет генерацию master key — медленно на главной, грузим лениво
    val settings by lazy { SettingsStore(app) }
    val db: FrigateDb by lazy { FrigateDb.build(app) }
    val client by lazy { FrigateClient(settings) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Статус WS-соединения для индикатора в шапке Live (companion пишет сервис). */
    private val _wsState = MutableStateFlow(WsStatus.DISCONNECTED)
    val wsState: StateFlow<WsStatus> = _wsState.asStateFlow()

    /** После «Принять» на Обзоре — снимать зелёные рамки со снапшотов до новых непросмотренных событий. */
    val boxesHidden = MutableStateFlow(false)

    /** top10-5 (F6): роль из /api/profile (admin/viewer); null = неизвестно/сервер без auth. */
    val userRole = MutableStateFlow<String?>(null)
    suspend fun refreshRole() {
        userRole.value = try {
            val p = client.profile()
            (p["role"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafeStr()
        } catch (_: Exception) {
            userRole.value // 403/недоступно — не чиним UI догадками, оставляем как есть
        }
    }
    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafeStr(): String? =
        if (isString) content else null

    fun setWsState(s: WsStatus) { _wsState.value = s }

    val imageLoader by lazy {
        ImageLoader.Builder(app)
            .components {
                HttpClientHolder.client = client.streamClient
                add(AuthHttpFetcher.Factory)
            }
            .build()
    }

    fun ensureService() = EventWatcherService.ensure(context)
}

enum class WsStatus { CONNECTED, CONNECTING, DISCONNECTED }

lateinit var appContainer: AppContainer
    private set

class FRGateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        // top10-4 (P1/P3): прогрев Keystore/EncryptedSharedPreferences, БД и Coil-ImageLoader
        // стартует до первого composition — main-поток не платит за генерацию master key.
        // Lazy потокобезопасен: кто первый тронул — тот прогрет, но это уже фоновый поток.
        appContainer.scope.launch(Dispatchers.IO) {
            runCatching { appContainer.settings } // touch lazy: EncryptedSharedPreferences + master key
        }
        appContainer.scope.launch(Dispatchers.IO) {
            runCatching { appContainer.db.openHelper.writableDatabase }
            runCatching { appContainer.imageLoader }
        }
        // настройки/БД/сервис — вне пути отрисовки первого кадра
        appContainer.scope.launch {
            if (appContainer.settings.current.configured) {
                appContainer.ensureService()
                ru.mytolm.frgate.service.EventWatcherService.scheduleWatchdog(this@FRGateApp)
                // top10-1 (L5): ежедневная ротация Room-кэша (deleteOlder)
                ru.mytolm.frgate.data.CacheCleanup.maybeRun(this@FRGateApp, appContainer.db)
            }
        }
    }
}

val Context.container: AppContainer get() = appContainer
