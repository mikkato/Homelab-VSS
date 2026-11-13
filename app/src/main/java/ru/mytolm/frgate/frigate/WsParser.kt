package ru.mytolm.frgate.frigate

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Разбор сообщений WebSocket Frigate 0.17 (проверено на живом сервере 04.11.2026):
 * каждый кадр — {"topic": "<topic>", "payload": "<json-строка>"}.
 * Неизвестные топики игнорируются, malformed JSON — null (не падаем).
 */
object WsParser {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    sealed class WsMsg {
        data class Event(val event: FrigateEvent) : WsMsg()
        data class CameraState(val camera: String, val kind: String, val online: Boolean) : WsMsg()
        data class Raw(val topic: String, val payload: String) : WsMsg()
    }

    /** Базовый разбор: возвращает пару topic/payload или null при malformed. */
    fun parse(raw: String): Pair<String, String>? = try {
        val o = json.parseToJsonElement(raw).jsonObject
        val topic = (o["topic"] as? JsonPrimitive)?.content ?: return null
        val payload = when (val p = o["payload"]) {
            is JsonPrimitive -> if (p.isString) p.content else p.content
            else -> p.toString()
        }
        topic to payload
    } catch (_: Exception) {
        null
    }

    /** Полный разбор в доменные сообщения; неизвестные топики → null (игнор). */
    fun classify(raw: String): WsMsg? {
        val (topic, payload) = parse(raw) ?: return null
        return try {
            when {
                topic == "frigate/events" -> {
                    val ev = json.decodeFromString(FrigateEvent.serializer(), payload)
                    WsMsg.Event(ev)
                }
                topic.endsWith("/status/detect") || topic.endsWith("/status/record") ||
                    topic.endsWith("/status/improved") -> {
                    val camera = topic.substringBefore("/status/")
                    val kind = topic.substringAfterLast('/')
                    val online = payload.equals("online", true) || payload == "ON"
                    WsMsg.CameraState(camera, kind, online)
                }
                else -> WsMsg.Raw(topic, payload)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** PTZ-команду формируем так же, как web (ws.tsx): {topic:"{camera}/ptz", payload:cmd}. */
    fun ptzMessage(camera: String, cmd: String): String =
        json.encodeToString(
            JsonObject(mapOf("topic" to JsonPrimitive("$camera/ptz"), "payload" to JsonPrimitive(cmd)))
        )

    fun autotrackerToggle(camera: String, on: Boolean): String =
        json.encodeToString(
            JsonObject(mapOf("topic" to JsonPrimitive("$camera/ptz_autotracker/set"), "payload" to JsonPrimitive(if (on) "ON" else "OFF")))
        )

    /** Топики, на которые подписываемся (пустая подписка = всё; фильтруем на клиенте). */
    const val EVENT_TOPIC = "frigate/events"
}
