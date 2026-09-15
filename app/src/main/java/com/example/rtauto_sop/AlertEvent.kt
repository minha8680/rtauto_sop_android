package com.example.rtauto_sop

/**
 * 경보 1건을 나타낸다. 기획안 5.5절의 "경보 상세 화면" · "오늘 이벤트 목록"이 이 데이터를 그대로 그린다.
 *
 * @param id            발생 시각(밀리초) — 이벤트 고유 식별자로 그대로 쓴다.
 * @param level         "중대" · "일반" · "주의" (기획안 표 1 편차 등급)
 * @param title         편차명 (예: "2인 1조 위반")
 * @param body          상세 문구 (예: "세정기 구역, 2인 1조 위반, 09시 10분 발생")
 * @param acknowledged  "확인" 버튼을 눌렀거나 엣지 PC가 해제를 보고해서 더 이상 활성 상태가 아닌지
 * @param autoResolved  true면 사람이 "확인"을 누른 게 아니라 엣지 PC가 재감지로 자동 해제한 것
 *                      (기획안 5.6절 "해제 조건" — ACK이 아니라 동일 검출 경로 재확인으로만 해제).
 *                      acknowledged=true인데 이게 false면 사람이 직접 확인한 것.
 * @param key           엣지 PC의 (규칙, 대상) 식별자 — 예: "helmet:7". FCM이 나중에 보내는
 *                      "해제" 메시지가 어느 경보를 가리키는지 맞춰보는 용도. 구버전 이벤트나
 *                      로컬 테스트 경보(홈 화면 "테스트 경보 재생")는 null일 수 있음
 */
data class AlertEvent(
    val id: Long,
    val level: String,
    val title: String,
    val body: String,
    val acknowledged: Boolean,
    val autoResolved: Boolean = false,
    val key: String? = null,
)
