package com.example.rtauto_sop

import android.content.Context

/**
 * 경보음 · TTS 재생 볼륨(사용자가 직접 조절)을 로컬에 보관한다.
 * 기기의 시스템 알람 볼륨은 건드리지 않고, 우리 앱이 재생할 때만 적용되는
 * 소프트웨어 볼륨 배율(0.0~1.0)로 다룬다.
 */
object AlertPrefs {
    private const val PREFS = "rtauto_sop_prefs"
    private const val KEY_VOLUME = "alert_volume"

    /** 기본값 60% — 너무 크지도 작지도 않게 시작. */
    const val DEFAULT_VOLUME = 0.6f

    fun getVolume(context: Context): Float {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_VOLUME, DEFAULT_VOLUME)
    }

    fun setVolume(context: Context, volume: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_VOLUME, volume.coerceIn(0f, 1f))
            .apply()
    }
}
