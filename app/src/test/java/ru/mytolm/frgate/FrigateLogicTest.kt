package ru.mytolm.frgate

import org.junit.Assert.*
import org.junit.Test
import org.junit.Assert.assertEquals
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.builtins.serializer
import ru.mytolm.frgate.frigate.FrigateClientMaps
import ru.mytolm.frgate.frigate.WsParser
import ru.mytolm.frgate.util.TimeFmt

/** §7 unit-тесты: парсер WS-сообщений, маппинг 401→слой, epoch→локальное время, go2rtc JSON. */
class FrigateLogicTest {

    // ---------- WsParser ----------
    @Test fun `parses event topic payload`() {
        // боевой формат Frigate 0.17 (проверен curl на /api/events/<id>)
        val raw = """{"topic": "frigate/events", "payload": "{\"id\":\"abc123\",\"camera\":\"tapo_FYR\",\"label\":\"person\",\"start_time\":1762260000.5,\"has_clip\":true,\"has_snapshot\":true,\"retain_indefinitely\":false,\"false_positive\":null,\"zones\":[\"driveway\"],\"data\":{\"box\":[0.36,0.45,0.10,0.44],\"score\":0.87,\"max_severity\":\"alert\"}}"}"""
        val msg = WsParser.classify(raw)
        assertTrue(msg is WsParser.WsMsg.Event)
        val ev = (msg as WsParser.WsMsg.Event).event
        assertEquals("abc123", ev.id)
        assertEquals("tapo_FYR", ev.camera)
        assertEquals(87, ev.confidence)
        assertEquals(listOf("driveway"), ev.zones)
        assertEquals(1762260000.5, ev.since, 0.001)
        assertEquals(4, ev.box.size)
        assertFalse(ev.retained)
    }

    @Test fun `parses camera status topic`() {
        val raw = """{"topic": "tapo_FY/status/detect", "payload": "online"}"""
        val msg = WsParser.classify(raw)
        assertTrue(msg is WsParser.WsMsg.CameraState)
        val cs = msg as WsParser.WsMsg.CameraState
        assertEquals("tapo_FY", cs.camera)
        assertEquals("detect", cs.kind)
        assertTrue(cs.online)
    }

    @Test fun `unknown topic does not crash`() {
        val raw = """{"topic": "some/new/thing", "payload": "{not json"}"""
        // классификация не должна бросать; неизвестное — Raw или null
        WsParser.classify(raw)
    }

    @Test fun `malformed json returns null`() {
        assertNull(WsParser.parse("{oops"))
        assertNull(WsParser.classify("garbage"))
    }

    @Test fun `ptz message format matches web`() {
        val m = WsParser.ptzMessage("tapo_FYR", "UP")
        assertTrue(m.contains("\"topic\":\"tapo_FYR/ptz\"") || m.contains("\"topic\": \"tapo_FYR/ptz\""))
        assertTrue(m.contains("UP"))
    }

    // ---------- Маппинг 401→слой ----------
    @Test fun `nginx 401 html maps to nginx layer`() {
        val body = "<html><head><title>401 Authorization Required</title></head></html>"
        assertEquals("nginx", FrigateClientMaps.errorKey(401, body))
    }

    @Test fun `frigate 401 json maps to frigate layer`() {
        assertEquals("frigate", FrigateClientMaps.errorKey(401, """{"detail":"Invalid authentication credentials"}"""))
    }

    @Test fun `403 and bad gateway mapping`() {
        assertEquals("forbidden", FrigateClientMaps.errorKey(403, "{}"))
        assertEquals("badgateway", FrigateClientMaps.errorKey(502, "<html>502</html>"))
        assertEquals("ok", FrigateClientMaps.errorKey(200, "{}"))
    }

    // ---------- epoch → локальное время ----------
    @Test fun `group label today and yesterday`() {
        val now = System.currentTimeMillis() / 1000.0
        assertEquals("Сегодня", TimeFmt.groupLabel(now))
        val yesterday = now - 86400
        assertEquals("Вчера", TimeFmt.groupLabel(yesterday))
    }

    @Test fun `duration formatting`() {
        assertEquals("45 с", TimeFmt.duration(45.0))
        assertEquals("2 мин 05 с", TimeFmt.duration(125.0))
        assertEquals("—", TimeFmt.duration(null))
    }

    @Test fun `quiet hours across midnight`() {
        assertTrue(TimeFmt.inQuietHours(23 * 60 + 30, 22 * 60, 7 * 60))
        assertTrue(TimeFmt.inQuietHours(3 * 60, 22 * 60, 7 * 60))
        assertFalse(TimeFmt.inQuietHours(12 * 60, 22 * 60, 7 * 60))
        assertFalse(TimeFmt.inQuietHours(12 * 60, -1, -1))
    }

    // ---------- go2rtc streams JSON (реальный снимок сервера) ----------
    @Test fun `go2rtc streams sample parses`() {
        val sample = """{"tapo_BY":{"producers":[{"url":"rtsp://*:*@192.168.1.26:554/stream1"},{"url":"rtsp://*:*@192.168.1.26:554/stream2"}],"consumers":[]},"tapo_FY":{"producers":[{"url":"rtsp://*:*@192.168.1.27:554/stream1"}],"consumers":[]}}"""
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val map = json.decodeFromString(
            kotlinx.serialization.builtins.MapSerializer(
                String.serializer(),
                ru.mytolm.frgate.frigate.Go2rtcStream.serializer()
            ), sample
        )
        assertEquals(2, map.size)
        assertEquals(2, map["tapo_BY"]!!.producers.size)
        assertTrue(map["tapo_FY"]!!.consumers.isEmpty())
    }

    @Test
    fun `dot-path config map nests into config_data`() {
        // top10-6: тестируем саму ConfigNest (что использует FrigateClient.configSetTyped), а не копию
        var root = kotlinx.serialization.json.buildJsonObject { }
        root = ru.mytolm.frgate.frigate.ConfigNest.nestPath(
            root, listOf("cameras", "tapo_FYR", "detect", "enabled"),
            ru.mytolm.frgate.frigate.ConfigNest.leafOf(true)
        )
        root = ru.mytolm.frgate.frigate.ConfigNest.nestPath(
            root, listOf("cameras", "tapo_FYR", "review", "alerts"),
            ru.mytolm.frgate.frigate.ConfigNest.leafOf(listOf("motion"))
        )
        val cam = root["cameras"]!!.jsonObject["tapo_FYR"]!!.jsonObject
        // F7: boolean остаётся boolean, а не строкой "true"
        assertEquals(
            kotlinx.serialization.json.JsonPrimitive(true),
            cam["detect"]!!.jsonObject["enabled"]
        )
        assertEquals("motion", cam["review"]!!.jsonObject["alerts"]!!.jsonArray[0].jsonPrimitive.content)
        // кавычка/слэш в метке не ломают payload — экранирует kotlinx-сериализатор
        val tricky = ru.mytolm.frgate.frigate.ConfigNest.leafOf(listOf("a\"b", "c\\d"))
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(tricky.toString()).jsonArray
        assertEquals("a\"b", parsed[0].jsonPrimitive.content)
        assertEquals("c\\d", parsed[1].jsonPrimitive.content)
    }

}
