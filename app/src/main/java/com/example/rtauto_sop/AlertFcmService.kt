package com.example.rtauto_sop

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * 엣지 PC가 FCM으로 보낸 편차 경보 · 해제 신호를 수신한다.
 *
 * 엣지 PC 쪽에서는 반드시 "data" 페이로드로만 보내야 한다(notification 키를 넣지 않는다).
 * data 메시지여야 앱이 포그라운드/백그라운드 상관없이 이 메서드를 직접 호출받아
 * 경고음 · 진동 · TTS를 우리가 원하는 그대로 재생할 수 있다.
 *
 * 기대하는 data 필드:
 *   kind  -> "alert"(새 위반, 생략 시 기본값) 또는 "resolved"(위반 해제)
 *   key   -> 엣지 PC의 (규칙, 대상) 식별자, 예: "helmet:7". alert/resolved를 서로 짝짓는 용도.
 *            생략 시 빈 문자열로 처리 — 그러면 resolved 메시지는 아무것도 못 찾아 조용히 무시됨
 *   title -> 예: "2인 1조 위반" (kind=alert일 때만 사용)
 *   body  -> 예: "세정기 구역, 2인 1조 위반, 09시 10분 발생" (kind=alert일 때만 사용)
 *   level -> "중대" · "일반" · "주의" (생략 시 "중대", kind=alert일 때만 사용)
 *
 * kind=resolved는 새 알림을 띄우지 않는다 — 기획안 5.6절 "해제 조건"대로, 사람의 확인이
 * 아니라 엣지 PC가 동일 검출 경로로 정상 복귀를 재확인했을 때만 오는 조용한 신호이기
 * 때문에, 지금 울리는 경보가 이 key와 일치하면 그냥 멈추기만 한다(EventStore/AlertPlayer
 * 양쪽 다 key로 매칭 — 다른 위반이 마침 울리는 중이면 안 건드림).
 */
class AlertFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val kind = message.data["kind"] ?: "alert"
        val key = message.data["key"] ?: ""

        if (kind == "resolved") {
            Log.i(TAG, "resolved received: key=$key")
            EventStore.resolveByKey(applicationContext, key)
            AlertPlayer.resolveIfMatches(applicationContext, key)
            return
        }

        val title = message.data["title"] ?: "SOP 편차 발생"
        val body = message.data["body"] ?: "현장 상태를 확인하세요."
        val level = message.data["level"] ?: "중대"

        Log.i(TAG, "alert received: level=$level title=$title body=$body key=$key")
        EventStore.addEvent(applicationContext, level, title, body, key)
        AlertPlayer.trigger(applicationContext, title, body, key)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "FCM token refreshed: $token")
        TokenStore.save(applicationContext, token)
    }

    companion object {
        private const val TAG = "AlertFcmService"
    }
}
