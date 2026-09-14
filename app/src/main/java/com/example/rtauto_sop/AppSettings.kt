package com.example.rtauto_sop

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

/**
 * "설정" 탭에서 관리하는 값들.
 * - 경보음(알림음): 사용자가 직접 고른 소리. 안 골랐으면(null) 기기의 기본 알람음을 쓴다.
 * - TTS/진동: 각각 켜고 끌 수 있다 (AlertPlayer가 트리거할 때 이 값을 확인한다).
 */
object AppSettings {
    private const val PREFS = "rtauto_sop_prefs"
    private const val KEY_ALARM_SOUND_URI = "alarm_sound_uri"
    private const val KEY_TTS_ENABLED = "tts_enabled"
    private const val KEY_VIBRATION_ENABLED = "vibration_enabled"

    fun getAlarmSoundUri(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ALARM_SOUND_URI, null) ?: return null
        return Uri.parse(raw)
    }

    /** null을 넘기면 "기본음 사용"으로 되돌린다. */
    fun setAlarmSoundUri(context: Context, uri: Uri?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ALARM_SOUND_URI, uri?.toString())
            .apply()
    }

    /** 실제 재생에 쓸 URI. 사용자가 고른 게 없으면 기기 기본 알람음(그것도 없으면 알림음)으로 대체한다. */
    fun resolveAlarmSoundUri(context: Context): Uri {
        return getAlarmSoundUri(context)
            ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    fun isTtsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_TTS_ENABLED, true)
    }

    fun setTtsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TTS_ENABLED, enabled)
            .apply()
    }

    fun isVibrationEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VIBRATION_ENABLED, true)
    }

    fun setVibrationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VIBRATION_ENABLED, enabled)
            .apply()
    }
}
