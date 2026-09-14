package com.example.rtauto_sop

/**
 * 경보 1건을 나타낸다. 기획안 5.5절의 "경보 상세 화면" · "오늘 이벤트 목록"이 이 데이터를 그대로 그린다.
 *
 * @param id            발생 시각(밀리초) — 이벤트 고유 식별자로 그대로 쓴다.
 * @param level         "중대" · "일반" · "주의" (기획안 표 1 편차 등급)
 * @param title         편차명 (예: "2인 1조 위반")
 * @param body          상세 문구 (예: "세정기 구역, 2인 1조 위반, 09시 10분 발생")
 * @param acknowledged  "확인" 버튼을 눌렀는지 여부
 */
data class AlertEvent(
    val id: Long,
    val level: String,
    val title: String,
    val body: String,
    val acknowledged: Boolean,
)
