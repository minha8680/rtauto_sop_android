package com.example.rtauto_sop

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * 엣지 PC가 FCM으로 보낸 편차 경보를 수신한다.
 *
 * 엣지 PC 쪽에서는 반드시 "data" 페이로드로만 보내야 한다(notification 키를 넣지 않는다).
 * data 메시지여야 앱이 포그라운드/백그라운드 상관없이 이 메서드를 직접 호출받아
 * 경고음 · 진동 · TTS를 우리가 원하는 그대로 재생할 수 있다.
 *
 * 기대하는 data 필드:
 *   title -> 예: "2인 1조 위반"
 *   body  -> 예: "세정기 구역, 2인 1조 위반, 09시 10분 발생"
 */
class AlertFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.data["title"] ?: "SOP 편차 발생"
        val body = message.data["body"] ?: "현장 상태를 확인하세요."

        Log.i(TAG, "alert received: title=$title body=$body")
        AlertPlayer.trigger(applicationContext, title, body)
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
