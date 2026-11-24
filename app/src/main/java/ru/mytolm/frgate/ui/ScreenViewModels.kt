package ru.mytolm.frgate.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.mytolm.frgate.appContainer
import ru.mytolm.frgate.data.CameraEntity
import ru.mytolm.frgate.data.EventEntity
import ru.mytolm.frgate.data.ReviewEntity
import ru.mytolm.frgate.data.toCameraInfo
import ru.mytolm.frgate.data.toFrigateEvent
import ru.mytolm.frgate.data.toReviewSummary
import ru.mytolm.frgate.frigate.CameraInfo
import ru.mytolm.frgate.frigate.ConfigParse
import ru.mytolm.frgate.frigate.FrigateApiException
import ru.mytolm.frgate.frigate.FrigateEvent
import ru.mytolm.frgate.frigate.ReviewSummary

/** top10-10: единый человекочитаемый текст ошибки для error/retry-состояний экранов. */
fun userError(e: Throwable): String =
    (e as? FrigateApiException)?.messageRu ?: (e.message ?: e.javaClass.simpleName)

/**
 * top10-10: данные экрана камер вынесены из composition в ViewModel + StateFlow.
 * Vanilla (без Hilt): конструктор без аргументов, дефолтный factory.
 */
class LiveViewModel : ViewModel() {
    data class UiState(
        val cameras: List<CameraInfo> = emptyList(),
        val offline: Boolean = false,
        val fromCache: Boolean = false,
        val loading: Boolean = false,
        val error: String? = null,
        /** camera_fps по камерам из /api/stats; 0 = Frigate не получает кадры (захват завис). */
        val fps: Map<String, Double> = emptyMap(),
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val cams = ConfigParse.cameras(appContainer.client.config())
                appContainer.db.cameraDao().upsert(
                    cams.map { CameraEntity(it.name, it.enabled, it.detectEnabled, it.recordEnabled, false) }
                )
                // fps камер: camera_fps=0 при живой камере = захват Frigate завис
                // (go2rtc живёт отдельно — плеер по тапу работает, а latest.jpg — заглушка).
                val fps = try {
                    val stats = appContainer.client.stats()
                    (stats["cameras"] as? kotlinx.serialization.json.JsonObject)
                        ?.mapNotNull { (name, v) ->
                            val fpsv = (v as? kotlinx.serialization.json.JsonObject)
                                ?.get("camera_fps") as? kotlinx.serialization.json.JsonPrimitive
                            fpsv?.content?.toDoubleOrNull()?.let { name to it }
                        }?.toMap() ?: emptyMap()
                } catch (_: Exception) { _state.value.fps }
                _state.update { it.copy(cameras = cams, fps = fps, offline = false, fromCache = false, error = null, loading = false) }
            } catch (e: Exception) {
                // top10-1: сеть легла — читаем кэш камер из Room
                var cached: List<CameraInfo> = emptyList()
                try { cached = appContainer.db.cameraDao().allList().map { it.toCameraInfo() } } catch (_: Exception) {}
                _state.update {
                    it.copy(
                        cameras = if (cached.isNotEmpty()) cached else it.cameras,
                        offline = true,
                        fromCache = cached.isNotEmpty(),
                        error = userError(e),
                        loading = false
                    )
                }
            }
        }
    }
}

/** top10-10: Review-лента во ViewModel; отметки прочитанного и retry — тоже там. */
class ReviewViewModel : ViewModel() {
    data class UiState(
        val items: List<ReviewSummary> = emptyList(),
        val cameras: List<String> = emptyList(),
        val camFilter: String? = null,
        val offline: Boolean = false,
        val fromCache: Boolean = false,
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    fun setCamFilter(cam: String?) {
        if (_state.value.camFilter == cam) return
        _state.update { it.copy(camFilter = cam) }
        refresh()
    }

    fun refresh() {
        val camFilter = _state.value.camFilter
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val cams = ConfigParse.cameras(appContainer.client.config()).map { it.name }
                val items = appContainer.client.review(
                    cameras = camFilter?.let { listOf(it) } ?: emptyList()
                ).filter { !it.viewed }
                appContainer.db.reviewDao().upsert(
                    items.map {
                        ReviewEntity(
                            id = it.id, camera = it.camera, thumbTime = it.thumbTime,
                            severity = it.severity, viewed = it.viewed,
                            mainEventId = it.mainEventId ?: "",
                            labelsCsv = it.labels.distinct().joinToString(","),
                            json = ""
                        )
                    }
                )
                _state.update {
                    it.copy(items = items, cameras = cams, offline = false, fromCache = false, error = null, loading = false)
                }
                // есть непросмотренные -> рамки показать; все приняты -> снять (переживает перезапуск)
                appContainer.boxesHidden.value = items.none { !it.viewed }
            } catch (e: Exception) {
                var cachedItems: List<ReviewSummary> = emptyList()
                var cachedCams: List<String> = emptyList()
                try {
                    cachedItems = appContainer.db.reviewDao().recentList(100)
                        .map { it.toReviewSummary() }
                        .filter { r -> !r.viewed && (camFilter == null || r.camera == camFilter) }
                    cachedCams = appContainer.db.cameraDao().allList().map { it.name }
                } catch (_: Exception) { }
                _state.update {
                    it.copy(
                        items = if (cachedItems.isNotEmpty()) cachedItems else it.items,
                        cameras = if (cachedCams.isNotEmpty()) cachedCams else it.cameras,
                        offline = true,
                        fromCache = cachedItems.isNotEmpty(),
                        error = userError(e),
                        loading = false
                    )
                }
            }
        }
    }

    fun markAllViewed() {
        val ids = _state.value.items.map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { appContainer.client.markViewed(ids) }
                .onSuccess { appContainer.boxesHidden.value = true }
                .onFailure { _state.update { s -> s.copy(error = userError(it)) } }
            refresh()
        }
    }

    fun toggleViewed(rev: ReviewSummary) {
        viewModelScope.launch {
            runCatching {
                if (rev.viewed) appContainer.client.unmarkViewed(rev.id)
                else appContainer.client.markViewed(listOf(rev.id))
            }.onFailure { _state.update { s -> s.copy(error = userError(it)) } }
            refresh()
        }
    }
}

/** top10-10: поиск/фильтры/пагинация Explore во ViewModel; выход на край грида зовёт loadMore(). */
class ExploreViewModel : ViewModel() {
    data class UiState(
        val events: List<FrigateEvent> = emptyList(),
        val cameras: List<String> = emptyList(),
        val labels: List<String> = emptyList(),
        val query: String = "",
        val camFilter: String? = null,
        val labelFilter: String? = null,
        val preset: Int = 2, // 0=час,1=сегодня,2=7дней,3=всё
        val loading: Boolean = false,
        val exhausted: Boolean = false,
        val offline: Boolean = false,
        val fromCache: Boolean = false,
        val semanticOff: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var offset = 0

    fun init() {
        viewModelScope.launch {
            if (_state.value.cameras.isEmpty()) {
                try {
                    _state.update {
                        it.copy(
                            cameras = ConfigParse.cameras(appContainer.client.config()).map { c -> c.name },
                            labels = appContainer.client.labels()
                        )
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(error = userError(e), offline = true) }
                }
            }
            if (_state.value.events.isEmpty()) fetchPage(true)
        }
    }

    private fun sinceOf(preset: Int): Long? = when (preset) {
        0 -> System.currentTimeMillis() / 1000 - 3600
        1 -> java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }.timeInMillis / 1000
        2 -> ru.mytolm.frgate.util.TimeFmt.daysAgo(7)
        else -> null
    }

    fun setQuery(q: String) { _state.update { it.copy(query = q) } }
    fun setCamFilter(c: String?) { _state.update { it.copy(camFilter = c) }; search() }
    fun setLabelFilter(l: String?) { _state.update { it.copy(labelFilter = l) }; search() }
    fun setPreset(p: Int) { _state.update { it.copy(preset = p) }; search() }
    fun search() { viewModelScope.launch { fetchPage(true) } }
    fun loadMore() { viewModelScope.launch { fetchPage(false) } }

    private suspend fun fetchPage(reset: Boolean) {
        val st = _state.value
        if (st.loading || (st.exhausted && !reset)) return
        _state.update { it.copy(loading = true) }
        val since = sinceOf(st.preset)
        try {
            val off = if (reset) 0 else offset
            // top10-6 (F8): семантическая ветка пагинируется тем же offset; срабатывает только
            // без фильтров камера/класс (у /events/search их нет) — иначе честный events(query=)
            val filtersActive = st.camFilter != null || st.labelFilter != null
            val page: List<FrigateEvent>? =
                if (st.query.isNotBlank() && !filtersActive) appContainer.client.search(st.query, limit = 60, offset = off)
                else null
            val list = if (page != null && page.isNotEmpty()) {
                _state.update { it.copy(semanticOff = false) }
                page
            } else {
                if (st.query.isNotBlank() && page != null && page.isEmpty() && off == 0)
                    _state.update { it.copy(semanticOff = true) }
                appContainer.client.events(
                    cameras = st.camFilter?.let { listOf(it) } ?: emptyList(),
                    labels = st.labelFilter?.let { listOf(it) } ?: emptyList(),
                    since = since,
                    limit = 60,
                    offset = off,
                    query = st.query.ifBlank { null }
                )
            }
            offset = off + 60
            _state.update {
                val merged = if (reset) list else (it.events + list).distinctBy { e -> e.id }
                it.copy(
                    events = merged,
                    exhausted = list.size < 60,
                    offline = false,
                    fromCache = false,
                    error = null
                )
            }
            // кэш в Room
            appContainer.db.eventDao().upsert(
                list.map {
                    EventEntity(
                        it.id, it.camera, it.label, it.sub_label, it.since, it.end_time, it.duration,
                        it.has_clip, it.has_snapshot, it.zones.joinToString(","), it.confidence,
                        it.retained, it.false_positive ?: false
                    )
                }
            )
        } catch (e: Exception) {
            // top10-1: при сетевой ошибке отдаём последние закэшированные события
            var cached: List<FrigateEvent> = emptyList()
            try { cached = appContainer.db.eventDao().recentList(60).map { it.toFrigateEvent() } } catch (_: Exception) {}
            _state.update {
                it.copy(
                    offline = true,
                    events = if (cached.isNotEmpty()) cached else it.events,
                    fromCache = cached.isNotEmpty(),
                    error = userError(e),
                    loading = false
                )
            }
            return
        }
        _state.update { it.copy(loading = false) }
    }
}
