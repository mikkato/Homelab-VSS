package ru.mytolm.frgate.frigate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FrigateApiException(
    val code: Int,               // 0 = сетевая ошибка без HTTP-кода
    val messageRu: String,
    cause: Throwable? = null
) : IOException(messageRu, cause)

/**
 * Двухслойная авторизация (§4): preemptive Basic для nginx + JWT-Frigate через cookie
 * frigate_token (после POST /api/login). Ре-логин по 401 — ровно один ретрай.
 */
class FrigateClient(private val settings: ru.mytolm.frgate.data.SettingsStore) {

    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    @Volatile private var jwt: String? = null
    private val relogining = AtomicBoolean(false)

    private fun basicHeader(): String {
        val s = settings.current
        val raw = "${s.login}:${s.password}"
        return "Basic " + android.util.Base64.encodeToString(raw.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private val authInterceptor = Interceptor { chain ->
        val b = chain.request().newBuilder()
            .header("Authorization", basicHeader())
        jwt?.let { b.header("Cookie", "frigate_token=$it") }
        var resp = chain.proceed(b.build())
        // Frigate-слой отбил JWT (истёк) — один ре-логин и повтор
        if (resp.code == 401 && jwt != null && relogining.compareAndSet(false, true)) {
            resp.close()
            try {
                loginBlocking()
                val b2 = chain.request().newBuilder()
                    .header("Authorization", basicHeader())
                    .header("Cookie", "frigate_token=$jwt")
                resp = chain.proceed(b2.build())
            } finally {
                relogining.set(false)
            }
        }
        resp
    }

    /** Основной клиент: connect 10 с, read 30 с. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .build()
    }

    /** Клиент для видеопотоков: без read-timeout (live не обрывается). */
    val streamClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    private fun loginBlocking() {
        val s = settings.current
        val body = json.encodeToString(mapOf("user" to s.login, "password" to s.password))
        val req = Request.Builder()
            .url(settings.normalizedBase() + "/api/login")
            .header("Authorization", basicHeader())
            .post(body.toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { r ->
            if (r.isSuccessful) {
                val txt = r.body?.string() ?: ""
                try {
                    val o = json.parseToJsonElement(txt).jsonObject
                    jwt = (o["access_token"] as? JsonPrimitive)?.content
                } catch (_: Exception) {
                    // Frigate может вернуть JWT строкой
                    if (txt.isNotBlank() && !txt.startsWith("{")) jwt = txt.trim().trim('"')
                }
            }
        }
    }

    private fun url(path: String): String = settings.normalizedBase() + path

    private suspend fun exec(req: Request, allowLogin: Boolean = true): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { r ->
                    var code = r.code
                    var body = r.body?.string() ?: ""
                    if (code == 401 && allowLogin && jwt == null) {
                        // Возможно включён внутренний auth Frigate — пробуем /api/login один раз
                        try { loginBlocking() } catch (_: Exception) {}
                        if (jwt != null) {
                            client.newCall(req.newBuilder().build()).execute().use { r2 ->
                                code = r2.code
                                body = r2.body?.string() ?: ""
                            }
                        }
                    }
                    code to body
                }
            } catch (e: java.net.SocketTimeoutException) {
                throw FrigateApiException(0, "timeout", e)
            } catch (e: javax.net.ssl.SSLException) {
                throw FrigateApiException(0, "tls", e)
            }
        }

    /** HTTP-код → человекочитаемая причина (§4). Возвращает null, если код успешный. */
    fun mapError(code: Int, body: String): String? = when {
        code in 200..299 -> null
        code == 401 -> "auth"                 // вызывающий экран решает nginx/Frigate по телу
        code == 403 -> "forbidden"
        code == 502 || code == 503 || code == 504 -> "badgateway"
        else -> "http$code"
    }

    private suspend fun get(path: String, allowLogin: Boolean = true): JsonElement {
        val (code, body) = exec(Request.Builder().url(url(path)).build(), allowLogin)
        val err = mapError(code, body)
        if (err != null) {
            android.util.Log.d("FRGateHttp", "GET $path -> $code ${body.take(120)}")
            throw httpError(code, body)
        }
        return json.parseToJsonElement(body)
    }

    private fun httpError(code: Int, body: String): FrigateApiException = when {
        code == 401 -> {
            val nginxHtml = body.contains("<html", true) || body.contains("401 Authorization Required", true)
            FrigateApiException(401, if (nginxHtml) "nginx" else "frigate")
        }
        code == 403 -> FrigateApiException(403, "forbidden")
        code in listOf(502, 503, 504) -> FrigateApiException(code, "badgateway")
        else -> FrigateApiException(code, "http$code")
    }

    private suspend fun mutate(method: String, path: String, body: String? = null): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val rb = (body ?: "").toRequestBody(jsonMedia)
            val req = Request.Builder().url(url(path)).method(method, if (body == null && method != "DELETE") null else rb).build()
            try {
                client.newCall(req).execute().use { r -> r.code to (r.body?.string() ?: "") }
            } catch (e: java.net.SocketTimeoutException) {
                throw FrigateApiException(0, "timeout", e)
            } catch (e: javax.net.ssl.SSLException) {
                throw FrigateApiException(0, "tls", e)
            }
        }

    // ---------- API ----------

    suspend fun version(): String {
        val (code, body) = exec(Request.Builder().url(url("/api/version")).build(), allowLogin = false)
        if (code == 401) throw httpError(code, body)
        if (code !in 200..299) throw httpError(code, body)
        return body.trim().trim('"')
    }

    suspend fun profile(): JsonObject = get("/api/profile").jsonObject

    /** Проверка подключения (§3.1): версия + конфиг; определяет слой 401. */
    suspend fun checkConnection(): ConnectionProbe {
        val ver = try {
            version()
        } catch (e: FrigateApiException) {
            return ConnectionProbe(false, null, e.messageRu)
        }
        // config требует авторизации — проверяем второй слой
        return try {
            get("/api/config")
            ConnectionProbe(true, ver, null)
        } catch (e: FrigateApiException) {
            ConnectionProbe(false, ver, e.messageRu)
        }
    }

    data class ConnectionProbe(val ok: Boolean, val version: String?, val errorKey: String?)

    suspend fun config(): JsonObject = get("/api/config").jsonObject

    /** Frigate 0.17: PUT /api/config/set телом {"config_data":{...}} (проверено живым запросом; GET/POST дают 405/422). */
    suspend fun configSet(configData: JsonObject): JsonObject {
        val body = json.encodeToString(JsonObject.serializer(), buildJsonObject { put("config_data", configData) })
        return mutate("PUT", "/api/config/set", body)
            .let { (c, b) -> if (c in 200..299) json.parseToJsonElement(b).jsonObject else throw httpError(c, b) }
    }

    /** dot-path («cameras.имя.detect.enabled») → вложенный config_data. Значения вида "[...]" парсятся как JSON-массивы. */
    suspend fun configSet(paths: Map<String, String>) {
        var root = buildJsonObject { }
        fun nest(obj: JsonObject, keys: List<String>, leaf: JsonElement): JsonObject = buildJsonObject {
            obj.forEach { (k, v) -> put(k, v) }
            val k = keys.first()
            put(
                k,
                if (keys.size == 1) leaf
                else nest((obj[k] as? JsonObject) ?: buildJsonObject { }, keys.drop(1), leaf)
            )
        }
        paths.forEach { (path, value) ->
            val leaf: JsonElement = if (value.trimStart().startsWith("[")) {
                try { json.parseToJsonElement(value) } catch (_: Exception) { JsonPrimitive(value) }
            } else JsonPrimitive(value)
            root = nest(root, path.split("."), leaf)
        }
        configSet(root)
    }

    suspend fun configSave(): JsonObject = mutate("POST", "/api/config/save")
        .let { (c, b) -> if (c in 200..299) json.parseToJsonElement(b).jsonObject else throw httpError(c, b) }

    suspend fun restart(): JsonObject = mutate("POST", "/api/restart")
        .let { (c, b) -> if (c in 200..299) json.parseToJsonElement(b).jsonObject else throw httpError(c, b) }

    suspend fun stats(): JsonObject = get("/api/stats").jsonObject

    suspend fun labels(): List<String> =
        get("/api/labels").jsonArray.mapNotNull { (it as? JsonPrimitive)?.content }

    suspend fun events(
        cameras: List<String> = emptyList(),
        labels: List<String> = emptyList(),
        since: Long? = null,
        until: Long? = null,
        limit: Int = 50,
        offset: Int = 0,
        hasClip: Boolean? = null,
        hasSnapshot: Boolean? = null,
        query: String? = null,
        includeThumbnail: Boolean = false
    ): List<FrigateEvent> {
        val parts = mutableListOf<String>()
        if (cameras.isNotEmpty()) parts += "cameras=${cameras.joinToString(",").encodeUrl()}"
        if (labels.isNotEmpty()) parts += "labels=${labels.joinToString(",").encodeUrl()}"
        since?.let { parts += "since=$it" }
        until?.let { parts += "until=$it" }
        parts += "limit=$limit"
        parts += "offset=$offset"
        hasClip?.let { parts += "has_clip=$it" }
        hasSnapshot?.let { parts += "has_snapshot=$it" }
        query?.let { parts += "query=${it.encodeUrl()}" }
        parts += "include_thumbnails=${if (includeThumbnail) 1 else 0}"
        val arr = get("/api/events?" + parts.joinToString("&")).jsonArray
        return arr.map { json.decodeFromJsonElement(FrigateEvent.serializer(), it) }
    }

    /** Семантический поиск; если выключен — null (вызывающий делает fallback на events(query=)). */
    suspend fun search(query: String, limit: Int = 50, offset: Int = 0): List<FrigateEvent>? {
        val path = "/api/events/search?query=${query.encodeUrl()}&limit=$limit&offset=$offset"
        val el = try { get(path) } catch (e: FrigateApiException) { return null }
        val o = el.jsonObject
        if ((o["success"] as? JsonPrimitive)?.booleanOrNullSafe == false) return null
        val arr = o["results"]?.jsonArray ?: o["events"]?.jsonArray ?: return null
        return arr.map { json.decodeFromJsonElement(FrigateEvent.serializer(), it) }
    }

    suspend fun event(id: String): FrigateEvent =
        json.decodeFromJsonElement(FrigateEvent.serializer(), get("/api/events/$id"))

    suspend fun setRetain(id: String, retain: Boolean) =
        mutate(if (retain) "POST" else "DELETE", "/api/events/$id/retain")

    suspend fun setFalsePositive(id: String, fp: Boolean) =
        mutate(if (fp) "PUT" else "DELETE", "/api/events/$id/false_positive")

    suspend fun deleteEvent(id: String) = mutate("DELETE", "/api/events/$id")

    suspend fun review(categories: List<String> = emptyList(), cameras: List<String> = emptyList(), reviewed: Boolean? = null): List<ReviewSummary> {
        val parts = mutableListOf<String>()
        if (categories.isNotEmpty()) parts += "categories=${categories.joinToString(",").encodeUrl()}"
        if (cameras.isNotEmpty()) parts += "cameras=${cameras.joinToString(",").encodeUrl()}"
        if (reviewed != null) parts += "reviewed=${if (reviewed) 1 else 0}"
        val q = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
        val arr = get("/api/review$q").jsonArray
        return arr.map { json.decodeFromJsonElement(ReviewSummary.serializer(), it) }
    }

    suspend fun markViewed(ids: List<String>) {
        val body = buildJsonObject {
            putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }
            put("reviewed", true)
        }.toString()
        mutate("POST", "/api/reviews/viewed", body)
    }

    suspend fun unmarkViewed(id: String) = mutate("DELETE", "/api/review/$id/viewed")

    // ---- Архив ----
    suspend fun recordingsSummary(camera: String, since: Long): JsonObject =
        get("/api/recordings/summary?cameras=${camera.encodeUrl()}&since=$since").jsonObject

    fun vodUrl(camera: String, start: Long, end: Long): String =
        // В 0.17 VOD go2rtc смонтирован на корневом пути без /api (проверено живым запросом)
        settings.normalizedBase() + "/vod/$camera/start/$start/end/$end/master.m3u8"

    suspend fun createExport(camera: String, start: Long, end: Long): JsonObject {
        val body = json.encodeToString(mapOf("playback_requested" to true, "timeline_requested" to false, "direct_retain" to false))
        val (c, b) = mutate("POST", "/api/export/$camera/start/$start/end/$end", body)
        if (c !in 200..299) throw httpError(c, b)
        return json.parseToJsonElement(b).jsonObject
    }

    suspend fun exports(): List<ExportItem> =
        get("/api/exports").jsonArray.map { json.decodeFromJsonElement(ExportItem.serializer(), it) }

    suspend fun deleteExport(id: String) = mutate("DELETE", "/api/exports/$id")

    fun exportDownloadUrl(id: String): String = url("/api/export/$id")

    // ---- Камеры/live ----
    fun absoluteUrl(path: String): String = url(path)
    fun gridUrl(camera: String): String = url("/api/$camera/grid.jpg")
    fun latestUrl(camera: String): String = url("/api/$camera/latest.jpg")

    suspend fun go2rtcStreams(): Map<String, Go2rtcStream> =
        json.decodeFromJsonElement(
            MapSerializer(String.serializer(), Go2rtcStream.serializer()),
            get("/api/go2rtc/streams")
        )

    fun liveMp4Url(camera: String): String = url("/api/go2rtc/api/stream.mp4?src=${camera.encodeUrl()}")
    fun liveHlsUrl(camera: String): String = url("/api/go2rtc/api/stream.m3u8?src=${camera.encodeUrl()}")
    fun webrtcUrl(camera: String): String = url("/api/go2rtc/api/webrtc?src=${camera.encodeUrl()}")

    suspend fun ptzInfo(camera: String): JsonObject? = try {
        get("/api/$camera/ptz/info", allowLogin = false).jsonObject
    } catch (e: FrigateApiException) {
        null
    }

    // ---- Пользователи ----
    suspend fun users(): List<UserInfo> =
        get("/api/users").jsonArray.map { json.decodeFromJsonElement(UserInfo.serializer(), it) }

    suspend fun createUser(username: String, password: String, role: String) {
        val body = json.encodeToString(mapOf("username" to username, "password" to password, "role" to role))
        val (c, b) = mutate("POST", "/api/users", body)
        if (c !in 200..299) throw httpError(c, b)
    }

    suspend fun setUserRole(username: String, role: String) {
        val body = json.encodeToString(mapOf("role" to role))
        val (c, b) = mutate("PUT", "/api/users/${username.encodeUrl()}/role", body)
        if (c !in 200..299) throw httpError(c, b)
    }

    suspend fun setUserPassword(username: String, password: String) {
        val body = json.encodeToString(mapOf("password" to password))
        val (c, b) = mutate("PUT", "/api/users/${username.encodeUrl()}/password", body)
        if (c !in 200..299) throw httpError(c, b)
    }

    suspend fun deleteUser(username: String) = mutate("DELETE", "/api/users/${username.encodeUrl()}")

    // ---- Логи ----
    suspend fun logs(source: String): String = withContext(Dispatchers.IO) {
        val (c, b) = exec(Request.Builder().url(url("/api/logs/$source")).build())
        if (c !in 200..299) throw httpError(c, b)
        b
    }

    // ---- WebSocket ----
    fun wsUrl(): String {
        val base = settings.normalizedBase()
        return base.replaceFirst("http", "ws") + "/ws"
    }

    fun newWebSocket(listener: WebSocketListener): WebSocket =
        client.newWebSocket(
            Request.Builder().url(wsUrl())
                .header("Authorization", basicHeader())
                .apply { jwt?.let { header("Cookie", "frigate_token=$it") } }
                .build(),
            listener
        )

    /** Прогрессивная закачка файла (клип события, экспорт). */
    suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).build()
            streamClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw httpError(r.code, "")
                dest.parentFile?.mkdirs()
                val total = r.body?.contentLength() ?: -1L
                r.body!!.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            dest
        }

    /** HTTP-заголовки для медиаплеера (media3 OkHttpDataSource). */
    fun mediaHeaders(): Map<String, String> =
        buildMap {
            put("Authorization", basicHeader())
            jwt?.let { put("Cookie", "frigate_token=$it") }
        }
}

private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")

private val JsonPrimitive.booleanOrNullSafe: Boolean? get() = booleanOrNull

/** Чистый маппинг HTTP→слой авторизации (JVM-тестируемый, §7). */
object FrigateClientMaps {
    fun errorKey(code: Int, body: String): String = when {
        code in 200..299 -> "ok"
        code == 401 -> if (body.contains("<html", true) || body.contains("401 Authorization Required", true)) "nginx" else "frigate"
        code == 403 -> "forbidden"
        code in listOf(502, 503, 504) -> "badgateway"
        else -> "http$code"
    }

    fun httpError(code: Int, body: String): FrigateApiException =
        FrigateApiException(if (code == 401) 401 else code, errorKey(code, body))
}
