# Ревизия Homelab VSS — краш при тапе на миниатюре (16.11.2026)

Симптом: тап по миниатюре изображения с камеры на главном экране → падение приложения, логов ошибки нет.
Метод: рой субагентов — (1) аудит архитектуры/UI, (2) ревью кода, (3) внедрение диагностического логирования. Все находки ниже — из реального кода рабочего дерева D:\MyProjects\Homelab-VSS (commit 634d5c0 + незакоммиченные правки диагностики).

## 1. Вердикт: почему «без логов»

1. **Половина симптома — конфиг сборки.** R8 `-assumenosideeffects` вырезает `Log.v/d/i` в release (коммит 50af89e, app/proguard-rules.pro:38-42). Прикладных логов в release нет по построению.
2. Фатальные Java-исключения печатает система (`AndroidRuntime E/F`) — их бы нашли. Полная «тишина» указывает на **native-краш (SIGSEGV/SIGABRT) или LMK/OOM-kill** — ни того, ни другого Java-лог не показывает.
3. До сегодняшних правок краш-дампов в release не писалось вообще.

## 2. Кандидаты причины (ранжировано, два независимых аудита)

| # | Гипотеза | Место | Вероятность |
|---|----------|-------|-------------|
| 1 | **Исключение в фазе composition**: `remember { ExoPlayer.Builder(...) }` без try/catch; внутри — `MediaSources.okHttpDataSourceFactory` → ленивая цепочка `SettingsStore` → `EncryptedSharedPreferences.create` (Keystore). Прогрев в Application глотает первую ошибку, но `by lazy` при исключении не кэширует — при первом тапе повтор и бросок прямо в recomposition. Класс ошибки тот же, что уже чинили в 055b445. | ui/PlayerEngine.kt:25-43, data/SettingsStore.kt:37-43 | **высокая** |
| 2 | **Native-путь WebRTC**: `PeerConnectionFactory.initialize` / `EglBase.create` / `SurfaceViewRenderer.init` синхронно в remember-блоке; гонка колбэков libwebrtc (`onAddTrack`) против `surfaceView.release()` без try/catch. SIGSEGV = ноль Java-логов. Актуально если liveMode=WEBRTC. | ui/LiveScreen.kt:440-489, webrtc/WebRtcEngine.kt:26,49 | **высокая** (для тапа по плитке камеры) |
| 3 | **OOM/LMK**: live-сетка передекодирует 8 полноразмерных кадров каждые 10 с (cache-busting `?t=$tick` сбрасывает memory- и disk-кэш Coil), snapshot события в `verticalScroll` без ограничения размера; на тапе сверху создаётся плеер/декодеры. LMK-kill не оставляет стека. | ui/LiveScreen.kt:80-85, 587-592; ui/EventScreen.kt:89-97 | **средняя** |
| 4 | **Дубли ключей LazyVerticalGrid Explore**: offset-пагинация + `events += list` без `distinctBy` → «Key was already used» при подгрузке страницы (краш «вблизи тапа»). | ui/ExploreScreen.kt:119, ui/ScreenViewModels.kt:247-250 | **средняя** |
| 5 | **Имя камеры без URL-кодирования в route** `camera/$c` → IllegalArgumentException в NavHost ровно в clickable. | MainActivity.kt:141 | **средняя** (оставил бы стек) |
| 6 | Прочее: `catch (Exception)` вместо Throwable (OOM Error проходит насквозь) — EventScreen.kt:47-60; substring-парсинг ключа дня в тапе Records — RecordsScreen.kt:126-128; `BitmapFactory.decodeFile` без inSampleSize в EventWatcherService.kt:300-307; `resp.body!!` и тихий пустой успех при не-2xx в AuthHttpFetcher.kt:22-24. | — | низкая |

Проверено отрицательно: других validate-бросков в билдерах media3 после 055b445 не осталось (инварианты буферов live 1000≤2000≤2500≤6000 корректны).

## 3. Что уже сделано (внедрено, компиляция проверена)

Создан **util/Diag.kt**: TAG `DIAG-FRIGATE` через `Log.e` (R8 не режет), ring buffer 500 записей, native-safe маркеры с `fd.sync()` в `files/diag/diag-trace.log` (переживают SIGSEGV), глобальный UncaughtExceptionHandler с дампом стека+буфера в `files/crash/diag-crash-<ts>.log`, session-маркер RUNNING/SHUTDOWN/CRASHED → детекция `prevRunUnclean`, onTrimMemory/onLowMemory. URL-токены не логируются (`Diag.url()` режет query).

Трассировка добавлена в 10 файлов по всему тап-пути: тапы миниатюр (Review/Explore/Live-плитка/Records) с eventId/camera перед навигацией; `player.build` ДО построения ExoPlayer + traced вокруг Builder и `okHttpDataSourceFactory` (кандидат №1); warm-up Keystore/SettingsStore с логом успех/провал; WebRTC eglBase/getFactory/surfaceView init/release — runCatching+native-safe маркеры (кандидат №2); `onPlayerError`, snapshot `onError` с URL, все route-входы. Поведение не изменено — только лог + перевызов.

`./gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL (проверено повторно оркестратором). В git **не закоммичено**.

## 4. План подтверждения (что требуется от Михаила)

1. Собрать release с диаг-правками и поставить на устройство; повторить тап-сценарий.
2. После краша снять:
   ```
   adb exec-out "run-as ru.mytolm.frgate tar c files/diag files/crash" | tar x
   ```
3. Интерпретация:
   - есть `diag-crash-*.log` → Java-краш, стек + последние 500 трасс в дампе (кандидат №1);
   - crash-файла нет, лог следующего старта `prevRunUnclean=true`, в хвосте `diag-trace.log` обрыв на WebRTC-маркере → native-краш (кандидат №2);
   - обрыв после серии `onTrimMemory` → OOM/LMK (кандидат №3); проверить имена камер в конфиге Frigate (кандидат №5 — символы `/ % ? #` в имени ломают навигацию).
4. Быстрая дифференциальная проба уже сейчас: если в настройках liveMode=WEBRTC — переключить на HTTP и повторить тап.

## 5. Рекомендуемые исправления (после подтверждения логики; код пока не трогали)

- Guard (`runCatching` + UI-fallback) вокруг remember-построения плеера и ленивой цепочки Keystore с recover-путём (сброс prefs → ConnectionScreen) — снимает кандидата №1 даже без точного диагноза.
- `distinctBy { it.id }` при доклейке страниц Explore; `Uri.encode` для имени камеры в route.
- WebRTC: инициализация фабрики вне composition (LaunchedEffect + состояние ошибки, фолбэк на HTTP), `removeSink`/флаг closed перед release.
- Coil: лимит memory cache, `size()` на snapshot, убрать поплиточный `?t=` cache-busting.
- Закоммитить Diag и держать его в релизах (иначе следующий краш снова бесследный).

## Не проверено
- сам краш не воспроизводился (нужно устройство/эмулятор с доступом к Frigate);
- release-сборка с Diag не собиралась (только compileDebugKotlin);
- реальное liveMode и имена камер в конфиге Frigate пользователя.
