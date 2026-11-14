package ru.mytolm.frgate.data

import ru.mytolm.frgate.frigate.CameraInfo
import ru.mytolm.frgate.frigate.EventData
import ru.mytolm.frgate.frigate.FrigateEvent
import ru.mytolm.frgate.frigate.ReviewData
import ru.mytolm.frgate.frigate.ReviewSummary

/** top10-1: офлайн-чтение кэша — сущности Room обратно в доменные модели. */

fun EventEntity.toFrigateEvent() = FrigateEvent(
    id = id, camera = camera, label = label, sub_label = subLabel,
    start_time = since, end_time = endTime,
    has_clip = hasClip, has_snapshot = hasSnapshot,
    retained = retained, false_positive = falsePositive,
    zones = zones.split(",").filter { it.isNotBlank() },
    data = EventData(score = confidence / 100f)
)

fun ReviewEntity.toReviewSummary() = ReviewSummary(
    id = id, camera = camera, startTime = thumbTime, endTime = null,
    severity = severity, viewed = viewed,
    data = ReviewData(
        objects = labelsCsv.split(",").filter { it.isNotBlank() },
        detections = if (mainEventId.isNotBlank()) listOf(mainEventId) else emptyList(),
        thumbTime = thumbTime
    )
)

fun CameraEntity.toCameraInfo() = CameraInfo(
    name = name, enabled = enabled, detectEnabled = detectEnabled,
    recordEnabled = recordEnabled, restreamEnabled = true, width = 0, height = 0
)

/** Ротация кэша (L5): раз в сутки удаляем события/отзывы старше 30 дней. */
object CacheCleanup {
    private const val RETENTION_SEC = 30.0 * 86400.0
    private const val PREFS = "runtime"
    private const val KEY_LAST = "cleanup_ms"

    suspend fun maybeRun(context: android.content.Context, db: FrigateDb) {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST, 0L) < 86400_000L) return
        val before = now / 1000.0 - RETENTION_SEC
        db.eventDao().deleteOlder(before)
        db.reviewDao().deleteOlder(before)
        prefs.edit().putLong(KEY_LAST, now).apply()
    }
}
