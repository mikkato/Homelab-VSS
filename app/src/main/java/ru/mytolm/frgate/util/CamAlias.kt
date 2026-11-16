package ru.mytolm.frgate.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import ru.mytolm.frgate.appContainer

/**
 * Отображаемое имя камеры: пользовательский псевдоним из настроек,
 * иначе — техническое имя Frigate. Псевдоним существует только в приложении,
 * конфигурация сервера не меняется.
 */
@Composable
fun camName(camera: String): String {
    val aliases = appContainer.settings.state.collectAsState().value.cameraAliases
    return aliases[camera]?.takeIf { it.isNotBlank() } ?: camera
}

/** Нон-комposable вариант (уведомления, загрузка вне composition). */
fun camNameSync(camera: String): String =
    runCatching {
        appContainer.settings.current.cameraAliases[camera]?.takeIf { it.isNotBlank() } ?: camera
    }.getOrDefault(camera)
