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

    /**
     * 등급 우선순위 — 숫자가 클수록 더 급함. 기획안의 critical/major/minor(중대/주의/일반)
     * 등급 체계를 그대로 반영: 여러 위반이 동시에 활성 상태여도 **중대가 항상 최우선으로
     * 떠야 한다**(2026-09-16, 사용자가 명시적으로 지정 — 최신순이 아니라 심각도순).
     * `levelBgRes()`/`levelColorRes()`(MainActivity)와 같은 등급 문자열 기준을 쓴다 —
     * 새 등급을 추가하면 여기도 같이 맞출 것.
     */
    private fun levelRank(level: String): Int = when (level) {
        "중대" -> 3
        "주의" -> 2
        else -> 1   // "일반" 등 그 외
    }

    /** 아직 "확인"을 누르지 않은 것 중 지금 가장 먼저 보여줘야 할 경보. 없으면 null. */
    fun latestUnacknowledged(context: Context): AlertEvent? {
        return allUnacknowledged(context).firstOrNull()
    }

    /**
     * 아직 "확인" 안 된 것 전부, **등급 우선(중대>주의>일반) → 그 안에서는 최신순**으로 정렬 —
     * 그래서 `allUnacknowledged().firstOrNull() == latestUnacknowledged()`가 항상 성립한다.
     * 서로 다른 위반(예: 2인1조 위반 중에 헬멧 미착용까지 겹침)이 동시에 활성 상태일 때
     * 경보상세 카드가 최신 1건으로 조용히 대체되면서 더 급한 위반을 뒷전으로 밀지 않도록,
     * "다른 활성 위반 N건 ▼" 펼치기 목록에서도 쓴다(2026-09-16, 실사용 중 발견 + 등급 우선
     * 정렬 요청). `readAll()` 자체의 저장 순서(최신순)는 오늘 이벤트/캘린더용이라 그대로 두고,
     * 이 함수에서만 재정렬한다.
     */
    fun allUnacknowledged(context: Context): List<AlertEvent> {
        return readAll(context)
            .filter { !it.acknowledged }
            .sortedWith(compareByDescending<AlertEvent> { levelRank(it.level) }.thenByDescending { it.id })
    }

    /** 설정 화면의 "오늘 이벤트 전체 삭제"에서 호출한다. */
    fun clearAll(context: Context) {
        writeAll(context, emptyList())
    }

    /** 오늘(자정 이후) 발생한 이벤트만, 최신순. */
    fun todayEvents(context: Context): List<AlertEvent> {
        return eventsForDate(context, System.currentTimeMillis())
    }

    /**
     * 오늘 자동 해제(autoResolved)된 이벤트 건수 — 경보상세의 "활성 경보 없음" 빈 화면에서
     * "오늘 자동 해제된 위반 N건"으로 보여줄 때 쓴다. 경보음은 재감지로 조용히 멈추더라도
     * (기획안 5.6절), 관리자가 오늘 뭔가 있었는지는 앱을 열어보면 바로 알 수 있어야 한다 —
     * 새 알림을 또 보내는 게 아니라 이미 기기에 있는 이력을 세기만 하므로 알림 피로를
     * 늘리지 않는다.
     */
    fun autoResolvedCountToday(context: Context): Int {
        return todayEvents(context).count { it.autoResolved }
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
