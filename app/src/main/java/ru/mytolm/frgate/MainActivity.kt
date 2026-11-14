package ru.mytolm.frgate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.mytolm.frgate.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ru.mytolm.frgate.data.ThemeMode
import ru.mytolm.frgate.ui.*

// Палитра «Plaud» — перенесена из AI-Dictaphone-v2 (MainActivity.kt), обязательна по ТЗ
private val PlaudLight = lightColorScheme(
    primary = Color(0xFF1C1B1F),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF3E6B5A),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F5F4),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFECECEC),
    onSurfaceVariant = Color(0xFF6E6E73),
    outline = Color(0xFFD9D9DC),
    error = Color(0xFFB3261E),
)

private val PlaudDark = darkColorScheme(
    primary = Color(0xFFE4E2E8),
    onPrimary = Color(0xFF1C1B1F),
    secondary = Color(0xFF7FBFA6),
    onSecondary = Color(0xFF0E241B),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1E1E20),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2A2A2E),
    onSurfaceVariant = Color(0xFFABAAB0),
    outline = Color(0xFF3F3F44),
    error = Color(0xFFF2B8B5),
)

@Composable
fun FRGateTheme(content: @Composable () -> Unit) {
    val mode = appContainer.settings.state.collectAsState().value.theme
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as ComponentActivity).window
            androidx.core.view.WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(
        colorScheme = if (dark) PlaudDark else PlaudLight,
        shapes = Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        ),
        content = content
    )
}

object Routes {
    const val CONNECTION = "connection"
    const val LIVE = "live"
    const val MAIN = "main"
    const val REVIEW = "review"
    const val EXPLORE = "explore"
    const val RECORDS = "records"
    const val SETTINGS = "settings"
    const val EVENT = "event/{eventId}"
    const val CAMERA = "camera/{camera}"
    const val RECORD_PLAY = "recordPlay/{camera}/{start}/{end}"
    const val S_CAMERAS = "settings/cameras"
    const val S_OBJECTS = "settings/objects"
    const val S_REVIEW = "settings/review"
    const val S_USERS = "settings/users"
    const val S_NOTIF = "settings/notifications"
    const val S_SYSTEM = "settings/system"
    const val S_LOGS = "settings/logs"
    fun event(id: String) = "event/$id"
    fun camera(c: String) = "camera/$c"
    fun recordPlay(c: String, s: Long, e: Long) = "recordPlay/$c/$s/$e"
}

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_EVENT_ID = "frgate_event_id"
        // F10: deep-link из onNewIntent живёт в StateFlow — Composable видит обновление без перемонтирования
        val pendingEventId = MutableStateFlow<String?>(null)
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* исход не критичен */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotifPermission()
        appContainer.ensureService()
        intent?.getStringExtra(EXTRA_EVENT_ID)?.let { pendingEventId.value = it }
        setContent {
            FRGateTheme {
                AppRoot(pendingEventId = pendingEventId.collectAsState().value)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_EVENT_ID)?.let { pendingEventId.value = it }
    }

    private fun requestNotifPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun AppRoot(pendingEventId: String?) {
    val nav: NavHostController = rememberNavController()
    val configured = appContainer.settings.state.collectAsState().value.configured
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = 0) { 5 }
    val pagerScope = rememberCoroutineScope()
    val start = if (configured) Routes.MAIN else Routes.CONNECTION

    LaunchedEffect(pendingEventId) {
        if (pendingEventId != null && configured) {
            nav.navigate(Routes.event(pendingEventId!!))
            MainActivity.pendingEventId.value = null // не перемонтировать при возврате назад
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = configured && route == Routes.MAIN

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) {
                data class TabItem(val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)
                val items = listOf(
                    TabItem(R.string.tab_live, Icons.Outlined.Videocam),
                    TabItem(R.string.tab_review, Icons.Outlined.FactCheck),
                    TabItem(R.string.tab_explore, Icons.Outlined.Search),
                    TabItem(R.string.tab_records, Icons.Outlined.History),
                    TabItem(R.string.tab_settings, Icons.Outlined.Settings)
                )
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    for ((i, tab) in items.withIndex()) {
                        NavigationBarItem(
                            selected = pager.currentPage == i,
                            onClick = { pagerScope.launch { pager.animateScrollToPage(i) } },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes), maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Visible, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = androidx.compose.ui.Modifier.padding(padding)
        ) {
            composable(Routes.CONNECTION) { ConnectionScreen(nav) }
            composable(Routes.MAIN) { MainPager(nav, pager) }
            composable(Routes.EVENT) { entry ->
                EventScreen(nav, entry.arguments?.getString("eventId") ?: "")
            }
            composable(Routes.CAMERA) { entry ->
                CameraDetailScreen(nav, entry.arguments?.getString("camera") ?: "")
            }
            composable(Routes.RECORD_PLAY) { entry ->
                RecordPlayScreen(
                    nav,
                    entry.arguments?.getString("camera") ?: "",
                    entry.arguments?.getString("start")?.toLongOrNull() ?: 0L,
                    entry.arguments?.getString("end")?.toLongOrNull() ?: 0L
                )
            }
            composable(Routes.S_CAMERAS) { AdminCamerasScreen(nav) }
            composable(Routes.S_OBJECTS) { AdminObjectsScreen(nav) }
            composable(Routes.S_REVIEW) { AdminReviewScreen(nav) }
            composable(Routes.S_USERS) { AdminUsersScreen(nav) }
            composable(Routes.S_NOTIF) { NotificationSettingsScreen(nav) }
            composable(Routes.S_SYSTEM) { SystemScreen(nav) }
            composable(Routes.S_LOGS) { LogsScreen(nav) }
        }
    }
}

/** Маппинг ключей ошибок FrigateClient → русские строки (§4). Non-composable: вызывается из корутин. */
fun errText(key: String?): String {
    val ctx = appContainer.app
    return when {
        key == null -> ""
        key == "auth" || key == "nginx" || key == "frigate" -> ctx.getString(R.string.err_auth)
        key == "forbidden" -> ctx.getString(R.string.err_forbidden)
        key == "badgateway" -> ctx.getString(R.string.err_badgateway)
        key == "timeout" -> ctx.getString(R.string.err_timeout)
        key == "tls" -> ctx.getString(R.string.err_tls)
        else -> ctx.getString(R.string.error_generic) + " $key"
    }
}
/** Главная: баннер-логотип + свайп-листы экранов + навигационное многоточие. */
@Composable
fun MainPager(nav: NavHostController, pager: androidx.compose.foundation.pager.PagerState) {
    val scope = rememberCoroutineScope()
    val tabRes = listOf(
        R.string.tab_live, R.string.tab_review, R.string.tab_explore,
        R.string.tab_records, R.string.tab_settings
    )
    Column(Modifier.fillMaxSize()) {
        // Бренд-строка (векторная иконка-щит + имя): PNG-баннер убран при rebrand
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = stringResource(R.string.app_name),
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(30.dp)
            )
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { p ->
            when (p) {
                0 -> LiveScreen(nav)
                1 -> ReviewScreen(nav)
                2 -> ExploreScreen(nav)
                3 -> RecordsScreen(nav)
                else -> SettingsScreen(nav)
            }
        }
        // навигационное многоточие
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until 5) {
                val active = pager.currentPage == i
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(width = if (active) 26.dp else 10.dp, height = 10.dp)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(50)
                        )
                        .clickable { scope.launch { pager.animateScrollToPage(i) } }
                ) {}
            }
        }
        Text(
            stringResource(tabRes[pager.currentPage]),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
