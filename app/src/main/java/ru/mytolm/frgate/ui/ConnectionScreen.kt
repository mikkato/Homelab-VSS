package ru.mytolm.frgate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import ru.mytolm.frgate.*
import ru.mytolm.frgate.R
import ru.mytolm.frgate.data.ThemeMode

/** §3.1 Первый запуск: URL + учётка + «Проверить» с человекочитаемыми ошибками. */
@Composable
fun ConnectionScreen(nav: NavHostController) {
    val s = appContainer.settings.state.collectAsState().value
    var url by remember { mutableStateOf(s.baseUrl) }
    var login by remember { mutableStateOf(s.login) }
    var pass by remember { mutableStateOf(s.password) }
    var showPass by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var okVersion by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text(stringResource(R.string.conn_title), style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text(stringResource(R.string.conn_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        OutlinedTextField(
            value = login, onValueChange = { login = it },
            label = { Text(stringResource(R.string.conn_login)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pass, onValueChange = { pass = it },
            label = { Text(stringResource(R.string.conn_password)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showPass = !showPass }) {
                    if (showPass) Icon(Icons.Default.VisibilityOff, contentDescription = stringResource(R.string.cd_hide_password))
                    else Icon(Icons.Default.Visibility, contentDescription = stringResource(R.string.cd_show_password))
                }
            }
        )

        if (okVersion != null) {
            AssistChip(onClick = {}, label = {
                Text(stringResource(R.string.conn_version_ok, okVersion!!), maxLines = 1, softWrap = false)
            })
        }
        if (status != null) {
            Text(status!!, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = {
                busy = true
                status = null
                okVersion = null
                scope.launch {
                    // F12: http:// упрётся в непонятную TLS-ошибку (cleartext запрещён) — говорим прямо
                    if (!url.trim().startsWith("https://")) {
                        status = "Поддерживается только https:// (нужен сертификат на сервере)"
                        busy = false
                        return@launch
                    }
                    appContainer.settings.update {
                        it.copy(baseUrl = url.trim(), login = login.trim(), password = pass)
                    }
                    val probe = appContainer.client.checkConnection()
                    if (probe.ok) {
                        okVersion = probe.version
                        status = null
                    } else {
                        status = errText(probe.errorKey)
                    }
                    busy = false
                }
            },
            enabled = !busy && url.isNotBlank() && login.isNotBlank() && pass.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.conn_check))
        }

        FilledTonalButton(
            onClick = {
                appContainer.settings.update { it.copy(configured = true) }
                appContainer.ensureService()
                nav.navigate(Routes.MAIN) { popUpTo(Routes.CONNECTION) { inclusive = true } }
            },
            enabled = okVersion != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.conn_connect)) }
    }
}

/** Общий хедер с кнопкой «назад» и заголовком (top10-7: Material-иконка вместо «←», высота M3 64dp). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
        },
        actions = actions,
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
    )
}

@Composable
fun OfflineBanner(visible: Boolean) {
    if (visible) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.offline_banner),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** Бейдж «из кэша» (top10-1): данные показаны из Room, сеть недоступна. */
@Composable
fun CachedBadge(visible: Boolean) {
    if (visible) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        ) {
            Text(
                stringResource(R.string.cached_badge),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
