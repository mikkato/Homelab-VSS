package ru.mytolm.frgate.data

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class LiveMode { HTTP, WEBRTC }

data class AppSettings(
    val baseUrl: String = "https://frgate.mytolm.ru",
    val login: String = "",
    val password: String = "",
    val configured: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val liveMode: LiveMode = LiveMode.HTTP,
    val liveGridCols: Int = 2,
    val lteAutoQuality: Boolean = true,
    val notifEnabled: Boolean = true,
    val notifCategories: Set<String> = setOf("alarm", "detection"),
    val notifLabels: Set<String> = emptySet(),          // пусто = все классы
    val notifCameras: Set<String> = emptySet(),         // пусто = все камеры
    val quietFrom: Int = -1,                            // минуты от полуночи; -1 = выкл
    val quietTo: Int = -1
)

/** EncryptedSharedPreferences (Android Keystore) — пароль не светим и не логируем. */
class SettingsStore(context: Context) {
    private val master = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val prefs = EncryptedSharedPreferences.create(
        context, "frgate_prefs", master,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val current: AppSettings get() = _state.value

    private fun load(): AppSettings {
        val s = AppSettings(
            baseUrl = prefs.getString("base_url", "https://frgate.mytolm.ru")!!,
            login = prefs.getString("login", "")!!,
            password = prefs.getString("password", "")!!,
            configured = prefs.getBoolean("configured", false),
            theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
            liveMode = runCatching { LiveMode.valueOf(prefs.getString("live_mode", "HTTP")!!) }.getOrDefault(LiveMode.HTTP),
            liveGridCols = prefs.getInt("grid_cols", 2),
            lteAutoQuality = prefs.getBoolean("lte_quality", true),
            notifEnabled = prefs.getBoolean("notif_enabled", true),
            notifCategories = prefs.getStringSet("notif_cats", setOf("alarm", "detection"))!!.toSet(),
            notifLabels = prefs.getStringSet("notif_labels", emptySet())!!.toSet(),
            notifCameras = prefs.getStringSet("notif_cams", emptySet())!!.toSet(),
            quietFrom = prefs.getInt("quiet_from", -1),
            quietTo = prefs.getInt("quiet_to", -1)
        )
        return s
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        prefs.edit {
            putString("base_url", next.baseUrl)
            putString("login", next.login)
            putString("password", next.password)
            putBoolean("configured", next.configured)
            putString("theme", next.theme.name)
            putString("live_mode", next.liveMode.name)
            putInt("grid_cols", next.liveGridCols)
            putBoolean("lte_quality", next.lteAutoQuality)
            putBoolean("notif_enabled", next.notifEnabled)
            putStringSet("notif_cats", next.notifCategories)
            putStringSet("notif_labels", next.notifLabels)
            putStringSet("notif_cams", next.notifCameras)
            putInt("quiet_from", next.quietFrom)
            putInt("quiet_to", next.quietTo)
        }
        _state.value = next
    }

    /** Нормализация: без слэша на конце. */
    fun normalizedBase(): String = current.baseUrl.trim().trimEnd('/')
}
