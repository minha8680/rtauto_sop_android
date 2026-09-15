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

    fun addEvent(context: Context, level: String, title: String, body: String, key: String? = null): AlertEvent {
        val event = AlertEvent(
            id = System.currentTimeMillis(),
            level = level,
            title = title,
            body = body,
            acknowledged = false,
            autoResolved = false,
            key = key,
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

    /**
     * 엣지 PC가 FCM으로 "해제"를 보고했을 때 호출한다(기획안 5.6절 — 사람의 확인이 아니라
     * 동일 검출 경로 재확인으로만 해제되어야 함). [key]와 일치하면서 아직 확인 안 된 이벤트를
     * 전부 acknowledged=true, autoResolved=true로 갱신한다. 하나라도 갱신했으면 true 반환.
     */
    fun resolveByKey(context: Context, key: String): Boolean {
        if (key.isEmpty()) return false
        var matched = false
        val events = readAll(context).map {
            if (it.key == key && !it.acknowledged) {
                matched = true
                it.copy(acknowledged = true, autoResolved = true)
            } else {
                it
            }
        }
        if (matched) writeAll(context, events)
        return matched
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

    /** 캘린더 그리드용 — [year]/[month](Calendar.MONTH 기준, 0=1월)에 이벤트가
     *  하나라도 있는 날짜(day-of-month)의 집합. 점 표시 여부를 정할 때 쓴다. */
    fun datesWithEventsInMonth(context: Context, year: Int, month: Int): Set<Int> {
        val cal = Calendar.getInstance()
        val days = mutableSetOf<Int>()
        readAll(context).forEach { event ->
            cal.timeInMillis = event.id
            if (cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) == month) {
                days.add(cal.get(Calendar.DAY_OF_MONTH))
            }
        }
        return days
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
                // optBoolean/optString — 이 두 필드가 없던 구버전 저장 데이터도 깨지지 않게
                autoResolved = o.optBoolean("autoResolved", false),
                key = if (o.has("key") && !o.isNull("key")) o.getString("key") else null,
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
                    put("autoResolved", e.autoResolved)
                    // e.key가 null이면 org.json이 이 key 자체를 안 씀(값을 null로 넣으면
                    // 프로퍼티가 제거됨) — 그래서 읽는 쪽에서 getString이 아니라 has()로 확인한다.
                    put("key", e.key)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EVENTS, array.toString())
            .apply()
    }
}
