package ru.mytolm.frgate.frigate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
data class FrigateEvent(
    val id: String,
    val camera: String = "",
    val label: String = "",
    val sub_label: String? = null,
    val top_label: String? = null,
    val start_time: Double = 0.0,
    val end_time: Double? = null,
    val thumbnail: String? = null,
    val has_clip: Boolean = false,
    val has_snapshot: Boolean = false,
    @SerialName("retain_indefinitely") val retained: Boolean = false,
    val false_positive: Boolean? = null,
    val zones: List<String> = emptyList(),
    val data: EventData = EventData()
) {
    val confidence: Int get() = (data.score * 100).toInt()
    val box: List<Double> get() = data.box
    val since: Double get() = start_time
    val duration: Double? get() = end_time?.let { it - start_time }
}

@Serializable
data class EventData(
    val box: List<Double> = emptyList(),
    val score: Float = 0f,
    val top_score: Float = 0f,
    val attributes: List<String> = emptyList(),
    val zones: List<String> = emptyList(),
    @SerialName("max_severity") val maxSeverity: String? = null,
    @SerialName("average_estimated_speed") val speed: Double = 0.0
)

@Serializable
data class ReviewSummary(
    val id: String,
    val camera: String = "",
    @SerialName("start_time") val startTime: Double = 0.0,
    @SerialName("end_time") val endTime: Double? = null,
    val severity: String = "detection",
    @SerialName("thumb_path") val thumbPath: String? = null,
    val data: ReviewData = ReviewData(),
    @SerialName("has_been_reviewed") val viewed: Boolean = false
) {
    val thumbTime: Double get() = data.thumbTime
    val labels: List<String> get() = data.objects
    val mainEventId: String? get() = data.detections.firstOrNull()
}

@Serializable
data class ReviewData(
    val objects: List<String> = emptyList(),
    @SerialName("sub_labels") val subLabels: List<String> = emptyList(),
    val zones: List<String> = emptyList(),
    val detections: List<String> = emptyList(),
    @SerialName("thumb_time") val thumbTime: Double = 0.0
)

@Serializable
data class Go2rtcStream(val producers: List<Go2rtcProducer> = emptyList(), val consumers: List<Go2rtcProducer> = emptyList())
@Serializable
data class Go2rtcProducer(val url: String = "")

@Serializable
data class UserInfo(val username: String, val role: String)

@Serializable
data class ProfileInfo(val username: String = "", val role: String = "", val authenticated: Boolean = false)

@Serializable
data class LoginResponse(val access_token: String = "")

@Serializable
data class ExportItem(
    val id: String,
    val name: String = "",
    val camera: String = "",
    val date: Double = 0.0,
    val duration: Double? = null,
    val size: Long? = null,
    val state: String = "",
    val type: String = ""
)

/** Единый разбор ответа /api/config → список камер (только нужные поля). */
data class CameraInfo(
    val name: String,
    val enabled: Boolean,
    val detectEnabled: Boolean,
    val recordEnabled: Boolean,
    val restreamEnabled: Boolean,
    val width: Int,
    val height: Int
)

object ConfigParse {
    fun cameras(configJson: kotlinx.serialization.json.JsonObject): List<CameraInfo> {
        val cameras = configJson["cameras"] as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        return cameras.mapNotNull { (name, v) ->
            val o = v as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun bool(vararg path: String): Boolean {
                var cur: kotlinx.serialization.json.JsonElement = o
                for (p in path) {
                    cur = (cur as? kotlinx.serialization.json.JsonObject)?.get(p)
                        ?: return false
                }
                return (cur as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false
            }
            fun int(path1: String, path2: String): Int {
                val o1 = o[path1] as? kotlinx.serialization.json.JsonObject ?: return 0
                return (o1[path2] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0
            }
            CameraInfo(
                name = name,
                enabled = o["enabled"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull } ?: true,
                detectEnabled = bool("detect", "enabled"),
                recordEnabled = bool("record", "enabled"),
                restreamEnabled = o["use_shared_decoder"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull } ?: true,
                width = int("detect", "width"),
                height = int("detect", "height")
            )
        }.sortedBy { it.name }
    }

    fun objectTrack(configJson: kotlinx.serialization.json.JsonObject): List<String> {
        val objs = configJson["objects"] as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        val track = objs["track"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return track.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe() }
    }

    fun authEnabled(configJson: kotlinx.serialization.json.JsonObject): Boolean {
        val auth = configJson["auth"] as? kotlinx.serialization.json.JsonObject ?: return false
        return (auth["enabled"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false
    }

    fun cookieName(configJson: kotlinx.serialization.json.JsonObject): String {
        val auth = configJson["auth"] as? kotlinx.serialization.json.JsonObject ?: return "frigate_token"
        return (auth["cookie_name"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe() ?: "frigate_token"
    }

    /**
     * Frigate 0.17: review.alerts / review.detections — объекты {enabled, labels, ...}
     * (не массивы). Motion-review = камерный motion.enabled (review.motion не существует).
     */
    fun reviewStates(configJson: kotlinx.serialization.json.JsonObject): List<CameraReviewState> {
        val out = mutableListOf<CameraReviewState>()
        val cams = configJson["cameras"] as? kotlinx.serialization.json.JsonObject ?: return out
        for ((name, v) in cams) {
            val o = v as? kotlinx.serialization.json.JsonObject ?: continue
            val rev = o["review"] as? kotlinx.serialization.json.JsonObject
            fun sectionEnabled(section: String): Boolean {
                val s = rev?.get(section) as? kotlinx.serialization.json.JsonObject ?: return false
                return (s["enabled"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false
            }
            fun bool(path1: String): Boolean {
                val s = o[path1] as? kotlinx.serialization.json.JsonObject ?: return false
                return (s["enabled"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false
            }
            out += CameraReviewState(
                name = name,
                alertsEnabled = sectionEnabled("alerts"),
                detectionsEnabled = sectionEnabled("detections"),
                motionEnabled = bool("motion"),
                detectEnabled = bool("detect")
            )
        }
        return out.sortedBy { it.name }
    }
}

/** Состояние review-категорий камеры (0.17: alerts/detections + motion.enabled). */
data class CameraReviewState(
    val name: String,
    val alertsEnabled: Boolean,
    val detectionsEnabled: Boolean,
    val motionEnabled: Boolean,
    val detectEnabled: Boolean
)

/**
 * Категория уведомления = серверная severity события (Frigate: alert|detection|null).
 * null = Frigate не отнёс объект ни к alerts, ни к detections (например, alerts выключены
 * на камере) — уведомления не шлём. Локальная эвристика «зоны+клип» больше не используется:
 * она расходилась с review-конфигом сервера и давала ложные «alarm».
 */
object EventCategory {
    fun of(ev: FrigateEvent): String? = when (ev.data.maxSeverity) {
        "alert" -> "alarm"
        "detection" -> "detection"
        else -> null
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (isString) content else null
