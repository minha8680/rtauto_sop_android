package com.example.rtauto_sop

import android.content.Context

/**
 * 이 기기의 FCM 토큰을 로컬에 보관한다.
 * 엣지 PC가 이 토큰을 알아야 이 기기로 메시지를 보낼 수 있으므로,
 * 1차 MVP에서는 화면에 토큰을 띄우고 관리자가 직접 복사해 엣지 PC 설정에 넣는 방식으로 처리한다.
 * (자동 등록 API를 붙이려면 엣지 PC에 토큰 수신용 엔드포인트를 하나 추가하면 된다.)
 */
object TokenStore {
    private const val PREFS = "rtauto_sop_prefs"
    private const val KEY_TOKEN = "fcm_token"

    fun save(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun get(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)
    }
}
