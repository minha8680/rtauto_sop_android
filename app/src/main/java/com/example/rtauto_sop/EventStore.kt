package com.example.rtauto_sop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 경보 이력을 기기 로컬에 보관한다 (기획안 5.5절 "오늘 이벤트 목록" 화면 + 날짜별 조회용).
 * 1차 MVP 범위라 서버 이력 저장 없이 SharedPreferences에 JSON 배열로만 쌓는다.
 * 최근 [MAX_EVENTS]건만 보관한다 — 날짜별로 거슬러 볼 수 있어야 하므로
 * "오늘 것만" 볼 때보다 넉넉하게 잡는다.
 */
object EventStore {
    private const val PREFS = "rtauto_sop_prefs"
    private const val KEY_EVENTS = "alert_events"
    private const val MAX_EVENTS = 500

    fun addEvent(context: Context, level: String, title: String, body: String): AlertEvent {
        val event = AlertEvent(
            id = System.currentTimeMillis(),
            level = level,
            title = title,
            body = body,
            acknowledged = false,
        )
        val events = readAll(context).toMutableList()
        events.add(0, event)
        writeAll(context, events.take(MAX_EVENTS))
        return event
    }

    fun acknowledge(context: Context, id: Long) {
        val events = readAll(context).map {
            if (it.id == id) it.copy(acknowledged = true) else it
        }
        writeAll(context, events)
    }

    /** 아직 "확인"을 누르지 않은 것 중 가장 최근 경보. 없으면 null. */
    fun latestUnacknowledged(context: Context): AlertEvent? {
        return readAll(context).firstOrNull { !it.acknowledged }
    }

    /** 설정 화면의 "오늘 이벤트 전체 삭제"에서 호출한다. */
    fun clearAll(context: Context) {
        writeAll(context, emptyList())
    }

    /** 오늘(자정 이후) 발생한 이벤트만, 최신순. */
    fun todayEvents(context: Context): List<AlertEvent> {
        return eventsForDate(context, System.currentTimeMillis())
    }

    /** [dateMillis]가 속한 하루(자정~다음날 자정 전) 동안 발생한 이벤트만, 최신순. */
    fun eventsForDate(context: Context, dateMillis: Long): List<AlertEvent> {
        val dayStart = Calendar.getInstance().apply {
            timeInMillis = dateMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 24 * 60 * 60 * 1000L
        return readAll(context).filter { it.id in dayStart until dayEnd }
    }

    private fun readAll(context: Context): List<AlertEvent> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EVENTS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            AlertEvent(
                id = o.getLong("id"),
                level = o.getString("level"),
                title = o.getString("title"),
                body = o.getString("body"),
                acknowledged = o.getBoolean("acknowledged"),
            )
        }
    }

    private fun writeAll(context: Context, events: List<AlertEvent>) {
        val array = JSONArray()
        events.forEach { e ->
            array.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("level", e.level)
                    put("title", e.title)
                    put("body", e.body)
                    put("acknowledged", e.acknowledged)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EVENTS, array.toString())
            .apply()
    }
}
