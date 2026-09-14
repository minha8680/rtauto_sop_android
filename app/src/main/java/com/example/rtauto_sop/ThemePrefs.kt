package com.example.rtauto_sop

import android.content.Context

/**
 * 사용자가 홈 화면의 "다크모드" 스위치로 고른 값을 저장한다.
 * 기기 설정을 따라가지 않고, 앱 안에서 직접 켜고 끄는 수동 토글이다.
 */
object ThemePrefs {
    private const val PREFS = "rtauto_sop_prefs"
    private const val KEY_DARK_MODE = "dark_mode_enabled"

    fun isDarkMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE, false)
    }

    fun setDarkMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_MODE, enabled)
            .apply()
    }
}
