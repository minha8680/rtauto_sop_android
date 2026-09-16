package com.example.rtauto_sop

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * 앱 프로세스가 새로 시작될 때 딱 한 번 호출된다 — Activity.onCreate()와 달리, 프로세스가
 * 살아있는 채로 액티비티만 재생성되는 경우(다크모드 토글, 화면 회전 등)엔 다시 안 불린다.
 * "완전히 새로 켜질 때만 라이트모드로 리셋"하려면 이 지점이 맞다.
 *
 * MainActivity.onCreate()의 savedInstanceState == null 체크만으로는 부족했다(2026-09-16
 * 실기기 테스트에서 발견) — 프로세스가 죽었다가 최근 앱 목록에서 복귀할 때도 안드로이드가
 * 저장해둔 인스턴스 상태를 그대로 복원하면서 savedInstanceState가 non-null로 들어오기
 * 때문에, "같은 세션 안의 재생성"과 구분이 안 됐다. 반면 Application.onCreate()는 그 경우도
 * 프로세스 자체는 새로 뜨는 것이므로 다시 호출되어, 원하는 시점을 정확히 잡아낸다.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePrefs.setDarkMode(this, false)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }
}
