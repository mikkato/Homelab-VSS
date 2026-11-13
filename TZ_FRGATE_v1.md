# ТЗ: Android-приложение «FRGate» — клиент Frigate NVR (v1.0)

Документ для кодинг-агента (Hermes). Все требования обязательны, кроме явно помеченных «опционально (фаза 2+)».

---

## 1. Общая информация

**Цель.** Нативное Android-приложение — полноценный мобильный клиент self-hosted NVR **Frigate 0.17**: мониторинг live-потоков, просмотр событий/архива, поиск, скачивание роликов, push-уведомления и **администрирование сервера** (камеры, зоны, пользователи, PTZ, экспорты) — «всё как в web-интерфейсе Frigate», но под мобильный UX.

**Сервер (реальный, проверен 04.11.2026):**
- URL: `https://frgate.mytolm.ru` (единственная точка доступа; реверс-прокси на периметровом хосте домашней сети → защищённый туннель → Frigate на внутреннем медиасервере).
- Frigate **0.17.2** (docker `ghcr.io/blakeblackshear/frigate:0.17.2`), API проверено по тегу v0.17.2 исходников blakeblackshear/frigate.
- Доступ: **HTTP Basic (401 от nginx)** + возможная встроенная авторизация Frigate (JWT) — приложение обязано поддерживать оба слоя (см. §4).
- LAN-доступ напрямую к порту медиасервера (`<внутр-IP>:5000`) с телефона **недоступен** (Frigate слушает 127.0.0.1 на сервере) — в приложении только один базовый URL, ничего не «чиним» на сервере.
- Логины: `frigate_user` / `frigate_pass` — переменные из настройки приложения (не хардкод).

**UI — как в проекте AI-Dictaphone (обязательно):**
- Kotlin + Jetpack Compose + Material 3, MVVM + Coroutines/Flow; палитра «Plaud» из `MainActivity.kt` диктофона (светлая: фон `#F5F5F4`, белые карточки, чёрный CTA, зелёный secondary `#3E6B5A`; тёмная: фон `#121212`, surface `#1E1E20`, secondary `#7FBFA6`), выбор `system/light/dark` в настройках; скругления карточек 12–16 dp; BottomSheet для деталей; русские строки в `strings.xml`.
- Пакет: **`ru.mytolm.frgate`**. Структура проекта — как AI-Dictaphone-v2 (`app/src/main/java/ru/mytolm/frgate/{ui,data,frigate,service}`, gradle wrapper, Kotlin 2.x + compose plugin + ksp).
- Сборка и проверка на реальном устройстве — по скиллам `android-apk-build` / `android-emulator` (тот же Gradle/SDK, что у диктофона).

**Целевое устройство:** Google Pixel 10 Pro, GrapheneOS (Android 16 / API 36). **minSdk = 35, targetSdk = 36.** ABI: arm64-v8a (+ x86_64 для эмулятора).

---

## 2. Технологический стек (обязателен)

- Kotlin 2.x, Jetpack Compose + Material 3, один модуль приложения.
- Сеть: **OkHttp** (HTTP + WebSocket; кастомный Authenticator/interceptor под Basic + JWT, см. §4).
- Видео: **androidx.media3 ExoPlayer** — HLS/MP4 (VOD-записи, клипы событий, live через go2rtc fMP4). Для live-режима MSE/WebRTC — опционально (фаза 3), MVP — прогрессивный fMP4/ HLS от go2rtc.
- Изображения (thumbnail/snapshot live-обновления): **Coil 3** с тем же OkHttp (auth обязателен — без него 401 на всех картинках).
- БД кэша: **Room** (события, review, камеры — офлайн-просмотр последнего состояния). Настройки/ключи: **EncryptedSharedPreferences** (Android Keystore).
- Фоновые задачи: foreground-сервис (WebSocket-слушатель событий → нотификации) + **WorkManager** (периодический re-check при утере WS).
- Разрешения: `INTERNET`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (GrapheneOS: без исключения из оптимизации WS уснёт).
- UI-язык: **русский** (строки в `strings.xml`).

---

## 3. Объём функционала = web-интерфейс Frigate 0.17 (изучен по исходникам тега v0.17.2)

Фронтенд Frigate 0.17 — React-приложение `web/src` в docker-образе. Страницы (`web/src/pages`): `Live`, `Review` (Events), `Explore`, `Exports`, `Records`, `Settings`, `System`, `Logs`, `Users/Roles`, `FaceLibrary`, `ClassificationModel`, `LoginPage`. Это карта экранов приложения (мобильная адаптация):

### 3.1 Подключение (первый запуск)
- Поля: базовый URL (default `https://frgate.mytolm.ru`), логин, пароль. Кнопка «Проверить»: `GET /api/version` (публичный) + `GET /api/config` (требует авторизацию). Понятные ошибки: 401 → «неверный логин/пароль или не пройден Basic nginx», TLS → текст ошибки, timeout → «сервер недоступен».
- Хранение учётки — EncryptedSharedPreferences; пароль не светить (показ по кнопке 👁), не логировать.

### 3.2 Live (главная вкладка)
- Дашборд сеткой камер: превью `GET /api/{camera}/grid.jpg` (или latest.jpg), автообновление; выбор конфигурации сетки (1/2/4 камер) как Live Dashboard.
- Раскрытая камера: живой поток + управление (как `LiveCameraView`):
  - провайдер live — go2rtc: список `GET /api/go2rtc/streams` и `GET /api/go2rtc/streams/{camera}`; способ трансляции выбирается пользователем (сегмент-контрол в шапке камеры + настройка по умолчанию в §3.8):
    - **HTTP (по умолчанию)**: для ExoPlayer `GET /api/go2rtc/api/stream.mp4?src={camera}` (прогрессивный fMP4) или `stream.m3u8?src={camera}` (HLS, если поток пригоден);
    - **WebRTC (экспериментально)**: signaling `POST /api/go2rtc/api/webrtc?src={camera}` (SDP offer/answer через существующий HTTPS-канал с авторизацией), медиа по ICE — **требует проброшенного порта 8555 (UDP, опц. TCP) на роутере на медиасервер**. При выборе режима — предупреждение: «WebRTC требует открытого порта 8555 (UDP/TCP) на роутере к медиасерверу. Пока порт не открыт, соединение не установится (соединяется только SDP по HTTPS, поток медиа идёт напрямую). Использовать HTTP-режим, если видите чёрный экран >10 с». Реализация — легковесный WebRTC-клиент с WebSocket/HTTP-signaling поверх OkHttp (напр. `dev.discabaca:libwebrtc`-совместимый подход или org.webrtc) **без внешних STUN** (host-candidate достаточно для прямого проброса порта; публичный STUN — опционально);
    - автофолбэк: если WebRTC не показал первый кадр за 10 с — toast-уведомление «WebRTC недоступен, переключаюсь на HTTP» и автоматический переход на HTTP-поток (запомнить выбор режима за пользователем, не переключать настройку навсегда);
    аудио по умолчанию выключено, кнопка вкл;
  - качество high/sub (если у камеры есть `-sub`-поток в go2rtc), полноэкранный режим, удержание ориентации;
  - оверлей статистики (FPS камеры, detection fps) по кнопке Debug — из `GET /api/stats`;
  - PTZ-панель (кнопки направления, стоп, зум, пресеты), если `GET /api/{camera}/ptz/info` вернул features (см. §6).
- Birdseye — опционально (фаза 2), если на сервере включён.

### 3.3 Review (вкладка)
- Лента review-предметов: `GET /api/review` (+ `?categories=alarm,detection,motion`, фильтр камеры, даты). Группировка по времени («сегодня», «вчера», дата) как в web.
- Типы с цветовыми метками: alarm (красный), detection (жёлтый), motion (синий). Не прочитанные — индикатор; отметить прочитанным `POST /api/reviews/viewed` (`{reviewIds:[...]}`), снять — `DELETE /api/review/{id}/viewed`.
- Карточка review: предпросмотр `GET /api/review/{event_id}/preview`; вложенные события с переходом в карточку события.

### 3.4 Событие (карточка, ядро просмотра)
- Видео клипа: `GET /api/events/{id}/clip.mp4` (ExoPlayer, с auth — см. §4.4).
- Snapshot с боксами: `GET /api/events/{id}/snapshot.jpg`; preview-GIF: `GET /api/events/{id}/preview.gif`; thumbnail: `GET /api/events/{id}/thumbnail.jpg`.
- Метаданные: камера, класс(ы) объектов, sub_labels, confidence, зоны, скорость/размер (для авто), время начала/конца, длительность, ID.
- Действия: **Скачать клип** (в «Загрузки» приложения + Share Sheet), в избранное → retain (`POST /api/events/{id}/retain`, удалить — `DELETE .../retain`), отметить false positive (`PUT /api/events/{id}/false_positive`), отправить в Frigate+ (опц.).
- Мини-таймлайн вокруг события: переход в Records на время события.

### 3.5 Explore / Поиск
- Поиск событий: `GET /api/events/search?query=...` (семантический/текстовый, если включён; иначе fallback `GET /api/events?query=`), фильтры: камера, класс (`GET /api/labels`), sub_label, зоны, диапазон дат (быстрые пресеты: час/сегодня/7 дней), только retained.
- Галерея-грид с thumbnail + бесконечная прокрутка (`limit`/`offset`).
- Страница объектов/лиц (`GET /api/faces`) и LPR (`GET /api/recognized_license_plates`) — опционально (фаза 2).

### 3.6 Records (архив)
- Календарь-грид наличия записей: `GET /api/recordings/summary?cameras=<c>&since=<ts>` (почасовые ячейки с длительностью) + `GET /api/recordings/unavailable`.
- Выбор сутки→час → плеер архива: `GET /api/vod/{camera}/start/{start_ts}/end/{end_ts}/master.m3u8` (HLS через go2rtc VOD) в ExoPlayer; перемотка, скорость 1/2/4×, фуллскрин.
- Скачивание отрезка → экспорт: `POST /api/export/{camera}/start/{ts}/end/{ts}` (`{playback_requested:true,...}`), список `GET /api/exports`, прогресс/скачивание `GET /api/exports/{id}`, удаление `DELETE /api/exports/{id}` (admin).

### 3.7 Уведомления (push без внешних сервисов)
**Штатными средствами Android: foreground-сервис + WebSocket + NotificationManager. Никаких FCM/MQTT/облаков** (согласовано с заказчиком).
- Сервис `EventWatcherService` держит WS `wss://frgate.mytolm.ru/ws` (Basic-auth в заголовках handshake OkHttp), слушает сообщения событий (топики MQTT-форвардинга: `frigate/events`, camera state, review). Реконнект с экспоненциальной задержкой (1→60 с), keepalive-ping каждые 30 с.
- Нотификация: thumbnail события (скачать `thumbnail.jpg`, в LargeIcon), текст «{camera}: {label} · {HH:mm}», клик → карточка события. Каналы: alarm/detection/motion — раздельные, настраиваемые (звук/вибро/выкл).
- Фильтры в настройках: по камере, по классу (default: человек/машина/кошка/собака…), по времени суток (тихие часы).
- Индикатор статуса соединения («онлайн/офлайн N мин») в шапке Live.
- WorkManager: раз в 15 мин — проверка живости сервиса.

### 3.8 Settings — администрирование (как в web, разделённый Settings 0.17)
Все эндпоинты правки требуют роли admin (`GET /api/profile` → роль). Реализуем разделы:
- **Камеры** (`CameraManagementView`): список камер (`GET /api/config`), ключевые поля (название, enabled, детекция, restream); добавление камеры через онбординг-мастер (`GET /api/onvif/probe`, `GET /api/reolink/detect`, ffprobe-проверка `GET /api/ffprobe`) — опционально (фаза 3), MVP: редактирование существующих и сохранение `PUT /api/config/set` + `POST /api/config/save` + `POST /api/restart`.
- **Маски и зоны** (`MasksAndZonesView`): рисование полигонов поверх background-кадра камеры (`GET /api/{camera}/latest.jpg` — кадр для редактирования; сохранение через config) — опционально (фаза 3, сложная часть).
- **Объекты** (`ObjectSettingsView`): включение/выключение классов детекции (config.set, без рестарта для многих настроек).
- **Настройки review камеры** (`CameraReviewSettingsView`): категории alarm/detection/motion по камере.
- **Пользователи и роли** (`UsersView`, `RolesView`): `GET/POST /api/users`, `PUT /api/users/{u}/role`, `DELETE /api/users/{u}`, смена пароля `PUT /api/users/{u}/password`; показ ролей.
- **Аутентификация** (`AuthenticationView`): показ режима `auth.mode` (config), сброс пароля.
- **Motion tuner / Triggers / Face library / LPR / Classification** — опционально (фаза 3).
- **UI/общие** (`UiSettingsView`): тема приложения, часовой пояс отображения (серверное время — epoch UTC, показывать в локальном TZ устройства), фильтры live по умолчанию, **способ live-трансляции по умолчанию: HTTP / WebRTC (с тем же предупреждением §3.2 и кнопкой «Проверить WebRTC»: пробный signaling-запрос + замер первого кадра)**, настройки уведомлений (§3.7).
- **Система** (`SystemView`): `GET /api/stats` (CPU/GPU/RAM/detection fps по камерам, storage), `GET /api/stats/history` (графики — упрощённые, Phase 2), `GET /api/metrics`; карточки как в web.
- **Логи** (`LogsView`): `GET /api/logs/frigate`, `GET /api/logs/go2rtc` — текстовый просмотр с прокруткой/поиском.

### 3.9 Что явно НЕ входит в v1 (НЕ ИСПОЛНЯТЬ кодинг-агенту)
Загрузка/сохранение YAML-конфигатора «как есть» (raw-редактор — фаза 3, пока только через config.set), Frigate+ тренировки моделей, Classifications целиком, birdseye-раскладка, MSE-плеер (достаточно связки HTTP + WebRTC), несколько серверов (один базовый URL), виджеты, WearOS, офлайн-просмотр скачанных записей вне «Загрузок» (закачка в Room — фаза 3), мультиязычность.

**Настройка сервера под WebRTC — делает заказчик вручную, агенту НЕ ИСПОЛНЯТЬ (никаких изменений на медиасервере/роутере из приложения или скриптов сборки):**
Дано: Frigate+go2rtc в docker на внутреннем медиасервере (`<внутр-IP>`), наружу сейчас published только `127.0.0.1:5000`. Чтобы WebRTC (опция §3.2) заработал с внешней сети, заказчик самостоятельно:
1. Роутер: проброс `UDP 8555 → <внутр-IP>:8555` и `TCP 8555 → <внутр-IP>:8555` (можно один порт на оба протокола).
2. Медиасервер, `/data/frigate/docker-compose.yml`: в `ports:` контейнера frigate добавить `8555:8555/udp` и `8555:8555/tcp` (сейчас там только 127.0.0.1:5000; конфиг go2rtc/Frigate править не нужно — 8555 является дефолтным WebRTC-портом go2rtc).
3. медиасервер, ufw: `sudo ufw allow from any to any port 8555 proto udp` и `... proto tcp` (или ограничить нужными подсетями, если нужно строже).
4. Применить: `cd /data/frigate && docker compose up -d --force-recreate frigate` (помнить: рестарт без `--force-recreate` не применит новые порты — см. pitfall про `unless-stopped`).
5. Проверка извне: мобильный интернет (LTE, не та же сеть) → в приложении FRGate выбрать режим WebRTC → картинка ≤ 10 с. До этих шагов приложение штатно показывает предупреждение и уходит на HTTP — это ожидаемое поведение, не баг.
Приложение НИЧЕГО из этого не проверяет активными тестами портов и не пытается чинить: только предупреждение + автофолбэк (§3.2).

---

## 4. Авторизация — критический раздел (обязательно к точной реализации)

Два независимых слоя 401:
1. **nginx HTTP Basic** (проверено: `401 Authorization Required` от nginx/1.30.5). OkHttp-интерцептор **преemptively** добавляет `Authorization: Basic <b64>` к каждому запросу (не ждать 401 — экономим RTT; nginx не отдаёт WWW-Authenticate стабильно для WS).
2. **Встроенная авторизация Frigate 0.17** (`auth.mode: enabled|http|required`): REST через JWT (`POST /api/login {username,password}` → `access_token`; далее `Authorization: Bearer <jwt>`). Сессия живёт время действия JWT; истёк — ре-логин по 401 + refresh (один ретрай, не бесконечный цикл).

**Порядок для сервера заказчика:** сначала пробуем Basic (nginx пропускает Basic от своего файла htpasswd); если Frigate internal auth включён — дополнительно логин в JWT и слать **оба** заголовка (Basic для nginx + Bearer для Frigate). Логика: `GET /api/profile` → если `authenticated:true` — всё ок; если 401 с `{"detail":...}` —Frigate-слой, делать `/api/login`; если 401 без JSON (HTML nginx) —Basic неверный.

**WebSocket:** OkHttp WS handshake принимает кастомные заголовки — слать Basic (+ Bearer при наличии) в upgrade-запросе.

**Изображения:** Coil настраивается на тот же OkHttpClient (interceptor §4) — иначе все thumbnail 401.

**Ошибки:** единый маппинг 401→«Проверьте учётные данные», 403→«Недостаточно прав (роль)», 502→«Сервер Frigate недоступен (туннель/контейнер)» (502 — известный режим отказа связки «медиасервер → периметровый хост»), timeout→«Нет связи». Ни один экран не должен падать при offline — показывать кэш Room + баннер.

---

## 5. API-справочник (Frigate 0.17.2, из исходников `frigate/api/`)

База: `https://frgate.mytolm.ru/api`. Все ответы JSON, если не указано image/video. `ts` — epoch **seconds** (float, UTC).

| Эндпоинт | Метод | Назначение |
|---|---|---|
| `/version` | GET (public) | версия; health-check |
| `/profile` | GET | роль текущего пользователя (admin/...) |
| `/login` | POST {username,password} | JWT (внутр. auth) |
| `/config` | GET | конфиг (без raw) |
| `/config/set`, `/config/save`, `/restart` | PUT/POST/POST (admin) | изменять/сохранять конфиг, рестарт |
| `/config/raw`, `/config/schema.json` | GET (raw — admin) | редактор конфига (фаза 3) |
| `/stats`, `/stats/history`, `/metrics` | GET | метрики System |
| `/labels`, `/sub_labels` | GET | классы объектов |
| `/events` | GET | события: `cameras,labels,since,until,limit,offset,has_clip,has_snapshot,include_thumbnails,sub_label,zones` |
| `/events/search?query=` | GET | семантический поиск |
| `/events/explore` | GET | галерея объектов |
| `/events/{id}` | GET/DELETE | карточка / удалить (admin) |
| `/events/{id}/clip.mp4` `/snapshot.jpg` `/preview.gif` `/thumbnail.jpg` | GET | медиа события (binary) |
| `/events/{id}/retain` `/false_positive` `/sub_label` `/end` | POST/PUT (admin) | действия над событием |
| `/review` `/review/{id}` `/review_ids` | GET | review-предметы |
| `/reviews/viewed` POST, `/review/{id}/viewed` DELETE | | отметить прочитанным/снять |
| `/review/{event_id}/preview` | GET | превью review |
| `/recordings/summary?cameras=&since=` | GET | почасовое наличие архива |
| `/recordings/unavailable` | GET | дыры архива |
| `/vod/{camera}/start/{s}/end/{e}/master.m3u8` | GET | HLS-плейлист архива (go2rtc VOD) |
| `/vod/clip/{camera}/start/{s}/end/{e}` | GET | mp4 отрезок |
| `/export/{camera}/start/{s}/end/{e}` | POST (admin) | экспорт отрезка |
| `/exports`, `/exports/{id}`, `/export/{id}` (DELETE), rename PATCH | | управление экспортами |
| `/{camera}/grid.jpg`, `/{camera}/latest.jpg` | GET | кадр камеры |
| `/go2rtc/streams`, `/go2rtc/streams/{name}` | GET/PUT | стримы go2rtc (live) |
| go2rtc api: `/go2rtc/api/stream.mp4?src=`, `.../stream.m3u8?src=`, `.../webrtc?src=` | GET/POST | живой поток (прокси go2rtc под `/api/go2rtc`) |
| `/{camera}/ptz/info` | GET | PTZ capabilities + presets |
| WS `/ws` | WS | события/состояния/review; PTZ-команды: сообщения `{camera}/ptz` |
| `/onvif/probe?format=onvif`, `/reolink/detect`, `/ffprobe` | GET (admin) | онбординг камер |
| `/users` `/users/{u}/role` `/users/{u}/password` | GET/POST/PUT (admin) | пользователи |
| `/faces` `/faces/{name}/create|delete|register`, rename | | библиотека лиц (фаза 2+) |
| `/recognized_license_plates` | GET | LPR (фаза 2+) |
| `/logs/frigate`, `/logs/go2rtc` | GET | логи текстом |
| `/notifications/register`, `/notifications/pubkey` | | web-push (не используем) |

Прочитать ответ `/ws` парсером с защитой: неизвестные топики игнорировать, malformed JSON — лог и продолжать (не падать).

---

## 6. PTZ
- `GET /api/{camera}/ptz/info` → `{features: [...], presets: [...]}` (панель показывать только при непустом ответе).
- Команды — **через тот же WS `/ws`**: сообщение `{camera}/ptz` (values `UP|DOWN|LEFT|RIGHT|UPLEFT|...|ZOOM_IN|ZOOM_OUT|STOP`), как `usePtzCommand` в web (`web/src/api/ws.tsx`); авто-`STOP` при отпускании кнопки; повтор-зажатие — интервал ≤200 мс.
- Пресеты: `PRESET_{N}` по индексу (1-based как в web). Автотрекер (если есть): state/set топики `{camera}/ptz_autotracker/*`, тумблер в панели.
- Отправка без подписки на события не требуется; но сервис должен уметь держать отдельное WS-соединение для PTZ, чтобы не блокировать поток событий.

---

## 7. Нефункциональные требования

- **Сеть по LTE через TLS**: ничего не кэшировать агрессивно (nginx отдаёт 401 — кэш не помогает); connect timeout 10 с, read 30 с (видео-стрим — без read-timeout).
- **Live-задержка**: fMP4/go2rtc до ~5–10 с — приемлемо (через туннель). В UI — маркировка «live» + время.
- **Экономия трафика**: сетка Live — grid.jpg c TTL 10 с на камеру; thumbnail в списках — ленивая загрузка; автокачество ≤720p для LTE (переключатель).
- **Производительность**: скролл ленты событий/галереи без фризов (Paging 3 + Coil пул); переключение камеры < 3 с до первого кадра live.
- **Надёжность**: killing сервиса системой → `START_STICKY` + восстановление WS; смена сети — реконнект по network callback.
- **Приватность (GrapheneOS-этика)**: данные ходят только на хост из настроек; учётка в Keystore; логи не содержат паролей.
- **Тесты**: unit — парсер WS-сообщений, маппинг 401→слой (nginx/Frigate), конвертация epoch→локальное время, разбор go2rtc streams JSON; instrumentation — нет (сервер внешний); чек-лист §9 обязателен на реальном устройстве.

---

## 8. Результаты анализа Frigate 0.17.2 (docker `ghcr.io/blakeblackshear/frigate:0.17.2`, тег v0.17.2, 04.11.2026) — контекст исполнителю

1. Фронтенд образа — **React/TS + Tailwind/shadcn** (`web/`, маршруты: `/`, `/review`, `/explore`, `/exports`, `/records`, `/settings/*`, `/system`, `/logs`), обслуживается Flask/FastAPI-бэкендом (`frigate/api/`, 139 маршрутов; `/api/*` + go2rtc-прокси `/api/go2rtc/api/*`). Не Svelte — не искать Svelte-роуты.
2. Live — **только go2rtc**; Frigate не раздаёт H.264 сам. Стримы именуются именем камеры; у камер с dual-stream есть `{camera}-sub`. В туннеле периметрового хоста (реверс-прокси + туннель-клиент) **WebRTC UDP 8555 не проброшен** → базовый live-режим HTTP (fMP4/HLS через HTTPS-туннель); **WebRTC — встроенная экспериментальная опция (фаза 1)**, готова к работе сразу после проброса портов 8555 UDP/TCP на роутере, без изменений в приложении.
3. Архив — HLS VOD go2rtc (`/api/vod/...` master.m3u8), пути сегментированы по часам `{year_month}/{day}/{hour}` — для интервалов через несколько часов ExoPlayer сам подтянет сегменты из master.
4. Все события/review/состояния дублируются в **WS `/ws`** (MQTT-подобные топики) — это источник realtime; поллинг REST — только fallback.
5. Управление конфигом (камеры/зоны/объекты) в 0.17 идёт через `PUT /api/config/set` + `POST /api/config/save` (часть применяется без рестарта) — raw-YAML-редактор не обязателен.
6. Нотификации Frigate — web-push (VAPID) и MQTT; для нативного клиента бесполезны напрямую → наше решение WS + NotificationManager (§3.7) корректно и не требует изменений на сервере.
7. Auth: `auth.mode` по умолчанию может быть выключен (тогда достаточно Basic nginx) — приложение обязано определять режим по поведению `/api/profile`, а не по догадкам.

## 9. Критерии приёмки (проверяются на Pixel 10 Pro / GrapheneOS, сервер frgate.mytolm.ru)

1. Первый запуск: ввод URL+учётки → «Проверить» показывает версию Frigate (0.17.x); неверный пароль даёт человекочитаемую ошибку, не краш.
2. Live: сетка камер обновляет превью; раскрытие камеры — живой поток с задержкой < 15 с, звук включается; переключение камер < 3 с. Режим WebRTC: показывает предупреждение про порт 8555; **при незакрытом порте — автофолбэк на HTTP ≤ 12 с с toast-сообщением**; (приёмка WebRTC-потока выполняется только после проброса портов на роутере — до этого проверяется только корректность отказа/фолбэка).
3. Review: лента соответствует web (те же предметы/фильтры); отметка «прочитано» исчезает из «новых» и синхронизируется с web-интерфейсом.
4. Событие: клип играет, snapshot с боксами, скачивание клипа → файл открывается в плеере телефона и отправляется через Share.
5. Records: summary-грид показывает почасовую наличие; выбор часа → HLS-плеер перемотает; экспорт отрезка появляется в `/exports` и скачивается.
6. Поиск: запрос «car» / семантический запрос возвращает события, фильтры камера/дата работают, скролл бесконечный.
7. Уведомления: при детекции на сервере — нотификация с thumbnail ≤ 10 с; при убийстве приложения сервис поднимается, WS переподключается (проверка `adb shell dumpsys activity services` + flight mode toggle); FCM/MQTT в зависимостях APK отсутствуют.
8. PTZ: если на сервере есть PTZ-камера — джойстик/кнопки поворачивают камеру, пресеты работают; для не-PTZ камеры панель скрыта.
9. Админ: смена имени камеры/вкл-выкл класса объектов сохраняет конфиг (`/api/config` изменился, web отражает); пользователи — список/смена роли работают.
10. Offline: airplane mode → приложение показывает кэш + баннер «нет связи», не падает; после восстановления — всё синхронизируется само.
11. UI-соответствие диктофону: палитра Plaud светлая/тёмная/системная, Compose-компоненты того же стиля, русский интерфейс, пакет `ru.mytolm.frgate`, APK собирается gradlew (v2 signed), устанавливается на GrapheneOS.

## 10. Этапы сдачи

- **Фаза 1 (MVP)**: §3.1 подключение+auth (§4 полностью), Live (сетка + одна камера: HTTP fMP4 **+ выбор WebRTC с предупреждением и автофолбэком §3.2**) + latest.jpg fallback, Review-лента + карточка события (клип/snapshot/thumbnail), скачивание клипа, базовые настройки (URL/учётка/тема/способ live), кэш Room. APK.
- **Фаза 2**: WS-сервис + уведомления (§3.7), Records + HLS + экспорт/exports, Explore/поиск, PTZ, сетка Live с конфигурацией, Settings: камеры (список+правка+save+restart), объекты, пользователи. APK.
- **Фаза 3 (опц.)**: System/Logs, маски-зоны (рисование), камерный мастер (ONVIF/Reolink), faces/LPR, birdseye, raw-конфиг. APK.

Каждая фаза — рабочий APK + прогон §9 (соответствующая часть) + отчёт с задержкой live и скриншотами.
