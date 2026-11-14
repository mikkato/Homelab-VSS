# Homelab VSS

Android-клиент self-hosted NVR **Frigate**: живые камеры (HTTP/WebRTC), события,
архив, поиск, push-уведомления и администрирование сервера — всё на Kotlin +
Jetpack Compose (Material 3, Material You).

- Live: прогрессивный fMP4 через ExoPlayer + WebRTC (go2rtc) с автофолбэком, PTZ, полноэкранный режим
- События/обзор/поиск с офлайн-кэшем (Room), грид камер со снапшотами
- Push-уведомления о детекциях (WebSocket-канал + фоновый сервис)
- Админка: камеры, зоны, пользователи, обзор, логи — как в web-интерфейсе Frigate
- ID приложения: `ru.mytolm.frgate` (не менять: ключ БД, настройки, уведомлённые deep-link'и)

Ранее проект назывался **FRGate**; с версии 2.0.0 — **Homelab VSS**. Пакет и
история git не переименовывались. Историческое ТЗ — `TZ_FRGATE_v1.md`
(имя файла сохранено как исторический артефакт).

## Релизы

[Releases](../../releases) — только arm64-v8a APK, прод-подпись
(CN=Homelab VSS). Сборки x86_64 существуют для эмуляции и не публикуются.
Журнал изменений — [CHANGELOG.md](CHANGELOG.md).

## Сборка

Требования: JDK 21, Android SDK (compileSdk 36).

```bash
./gradlew :app:assembleDebug        # debug APK по ABI (arm64-v8a, x86_64)
./gradlew :app:assembleRelease      # release: R8 + shrinkResources
```

Release-подпись подхватывается из `keystore.properties` в корне проекта
(файл gitignored; см. `tools/.secrets/`). Без него release собирается
debug-ключом — для публикации так нельзя.

`versionCode` = число коммитов в main, `versionName` = `2.0.0+<git-tag>.<sha>`
(генерируются в build.gradle.kts, внешних плагинов нет).
