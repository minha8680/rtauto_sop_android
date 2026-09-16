package com.example.rtauto_sop

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CombinedVibration
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.messaging.FirebaseMessaging
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var tokenText: TextView

    // 경보상세 · 이벤트목록 · 설정 화면 전환용
    private lateinit var homeContent: View
    private lateinit var alertDetailContent: View
    private lateinit var eventListContent: View
    private lateinit var settingsContent: View

    // "오늘 이벤트" 탭에서 캘린더로 고른 날짜. 기본값은 오늘.
    private var selectedDateMillis: Long = System.currentTimeMillis()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val soundPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            @Suppress("DEPRECATION")
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            AppSettings.setAlarmSoundUri(this, uri)
            updateSoundNameText()
        }

    companion object {
        private const val KEY_SELECTED_NAV_ID = "selected_nav_id"
        private const val KEY_SELECTED_DATE = "selected_date_millis"
        private const val ALERT_IMPACT_INTERVAL_MS = 4_000L

        /** AlertPlayer가 알림의 PendingIntent에 실어 보내는 extra — 이게 true면 홈 대신
         *  경보상세 탭으로 바로 연다(알림을 탭해서 들어온 거니까). */
        const val EXTRA_OPEN_ALERT_DETAIL = "open_alert_detail"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 저장된 다크모드 값을 화면이 그려지기 전에 먼저 적용한다. "완전히 새로 켜질 때는
        // 항상 라이트로 시작" 리셋은 여기가 아니라 App.onCreate()(프로세스 시작 시 1회)에서
        // 처리한다 — savedInstanceState만으로는 프로세스가 죽었다가 최근 앱 목록에서
        // 복귀하는 경우를 못 걸러낸다(2026-09-16 실기기 테스트에서 확인, App.kt 참고).
        AppCompatDelegate.setDefaultNightMode(
            if (ThemePrefs.isDarkMode(this)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        // installSplashScreen()은 반드시 super.onCreate() 이전에 호출해야 한다.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 액션바를 기본 한 줄 제목 대신, 시안대로 브랜드 벨 + "RT AUTOMATION" + 탭 이름
        // 2단 커스텀 뷰로 바꾼다. setTabTitle()이 안의 actionBarTabTitle만 갱신한다.
        supportActionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setDisplayShowCustomEnabled(true)
            setCustomView(R.layout.view_actionbar_title)
        }

        AlertPlayer.ensureChannel(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        tokenText = findViewById(R.id.tokenText)
        loadToken()

        findViewById<android.widget.Button>(R.id.copyButton).setOnClickListener {
            val token = tokenText.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("fcm_token", token))
            Toast.makeText(this, "토큰을 복사했습니다", Toast.LENGTH_SHORT).show()
        }

        findViewById<android.widget.Button>(R.id.testAlertButton).setOnClickListener {
            val title = "테스트 경보"
            val body = "이것은 테스트 경보입니다. 세정기 구역, 이인 일조 위반, 지금 발생."
            EventStore.addEvent(this, "중대", title, body)
            AlertPlayer.trigger(this, title, body)
            refreshAlertDetail()
            refreshEventList()
        }

        if (savedInstanceState != null) {
            // 캘린더에서 고른 날짜도 recreate 전에 보던 값 그대로 복원한다.
            selectedDateMillis = savedInstanceState.getLong(KEY_SELECTED_DATE, selectedDateMillis)
        }

        setupVolumeControl()
        setupSettingsScreen()
        setupEventCalendar()
        setupBottomNav()

        val openAlertDetail = intent?.getBooleanExtra(EXTRA_OPEN_ALERT_DETAIL, false) == true
        when {
            openAlertDetail -> {
                // 알림을 탭해서 들어온 경우 — 홈이 아니라 경보상세로 바로 이동. 급한 경보를
                // 보러 온 건데 2.3초짜리 인트로 스플래시로 지연시키지 않는다.
                findViewById<View>(R.id.introOverlay).visibility = View.GONE
                findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_alert_detail
            }
            savedInstanceState == null -> {
                // 프로그램적으로 selectedItemId를 바꿀 때와 달리, 최초 진입 시엔 리스너가 안
                // 불려서 기본 탭(홈)의 액션바 제목을 따로 한 번 맞춰준다.
                setTabTitle("홈")
                runIntroAnimation()
            }
            else -> {
                findViewById<View>(R.id.introOverlay).visibility = View.GONE
                // 다크모드 전환 등으로 recreate될 때 보고 있던 탭 그대로 복원한다.
                // (홈/경보상세/이벤트목록 컨테이너의 visibility는 기본 View 상태 저장에
                // 포함되지 않아, 복원해주지 않으면 매번 홈 화면으로 되돌아간다.)
                val savedNavId = savedInstanceState.getInt(KEY_SELECTED_NAV_ID, R.id.nav_home)
                findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = savedNavId
            }
        }
    }

    /**
     * launchMode="singleTask"라 앱이 이미 떠 있는 상태에서 알림을 또 탭하면 onCreate()가
     * 아니라 여기로 들어온다. getIntent()가 여전히 예전 인텐트를 가리키지 않도록
     * setIntent()로 갱신한 뒤, 이번에도 알림을 탭해서 들어온 거면 경보상세로 전환한다.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_ALERT_DETAIL, false)) {
            findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_alert_detail
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_NAV_ID, findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId)
        outState.putLong(KEY_SELECTED_DATE, selectedDateMillis)
    }

    // ---------------------------------------------------------------------
    // 다크모드 토글 — 액션바 우측 상단 아이콘 (기기 설정과 무관하게 앱 안에서 직접 켜고 끈다)
    // ---------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        updateThemeMenuIcon(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_toggle_theme) {
            val nowDark = !ThemePrefs.isDarkMode(this)
            ThemePrefs.setDarkMode(this, nowDark)
            AppCompatDelegate.setDefaultNightMode(
                if (nowDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun updateThemeMenuIcon(menu: Menu) {
        val item = menu.findItem(R.id.action_toggle_theme) ?: return
        item.setIcon(if (ThemePrefs.isDarkMode(this)) R.drawable.ic_theme_dark else R.drawable.ic_theme_light)
    }

    // ---------------------------------------------------------------------
    // 인트로 스플래시 — 로고 · 문구가 서서히 나타났다 사라진다
    // ---------------------------------------------------------------------

    private fun runIntroAnimation() {
        // introOverlay는 콘텐츠 영역만 덮는다 — 액션바는 윈도우 데코 쪽이라 별도로 그려져서,
        // 안 숨기면 스플래시 위로 액션바(벨 · RT AUTOMATION · 탭 이름 · 다크모드 아이콘)가
        // 그대로 비쳐 보인다. 인트로가 끝날 때(overlay가 GONE될 때) 다시 보여준다.
        supportActionBar?.hide()

        val overlay = findViewById<View>(R.id.introOverlay)
        val logo = findViewById<View>(R.id.introLogo)
        val divider = findViewById<View>(R.id.introDivider)
        val title = findViewById<View>(R.id.introTitle)
        val subtitle = findViewById<View>(R.id.introSubtitle)

        fun fadeIn(view: View, delay: Long) {
            view.translationY = 16f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(650)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        fadeIn(logo, 150)
        fadeIn(divider, 500)
        fadeIn(title, 600)
        fadeIn(subtitle, 720)

        overlay.postDelayed({
            // 액션바를 페이드아웃이 다 끝난 뒤 따로 튀어나오게 하지 않고, 오버레이가
            // 투명해지는 것과 동시에 보여준다 — ActionBar.show()도 자체 등장 애니메이션이
            // 있어서, 두 애니메이션이 겹치며 하나의 자연스러운 전환처럼 보인다.
            supportActionBar?.show()
            overlay.animate()
                .alpha(0f)
                .setDuration(400)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { overlay.visibility = View.GONE }
                .start()
        }, 1900)
    }

    override fun onResume() {
        super.onResume()
        // 다른 화면(알림 탭 등)에서 돌아왔을 때도 최신 상태를 보여준다.
        refreshAlertDetail()
        refreshEventList()
    }

    override fun onStart() {
        super.onStart()
        // 화면이 실제로 보이는 동안에만 새 경보 콜백을 받는다 — 앱이 백그라운드일 때
        // 도착한 경보는 여기서 처리하지 않고, 다음 onResume()의 refreshAlertDetail()이
        // 최신 상태를 보여주는 것으로 충분하다(자동 탭 전환·애니메이션은 필요 없음).
        AlertPlayer.setUiListener { onNewAlertArrived() }
        AlertPlayer.setUiResolvedListener { onAlertResolvedFromEdge() }
    }

    override fun onStop() {
        super.onStop()
        AlertPlayer.setUiListener(null)
        AlertPlayer.setUiResolvedListener(null)
        // 화면이 안 보이는 동안 배경에서 계속 흔들림/진동 연출이 도는 걸 막는다 — 활성 경보가
        // 여전히 있으면 다음 onResume()의 refreshAlertDetail()이 다시 켠다.
        stopAlertImpactLoop()
    }

    /**
     * 화면을 보고 있는 도중(예: 홈 화면) 새 경보가 울리기 시작하면 호출된다.
     * 경보상세 탭으로 자동 전환한다 — 기존 BottomNavigationView 리스너가 새로고침까지
     * 처리하고, 그 안의 refreshAlertDetail()이 카드 테두리의 "무한궤도" 표시도 켠다.
     */
    private fun onNewAlertArrived() {
        findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_alert_detail
    }

    /**
     * 엣지 PC가 위반 해제(FCM kind="resolved")를 보고했을 때 호출된다 — 알람 자체는
     * AlertPlayer가 이미 멈췄거나(해당 경보였을 때) 그대로 뒀지만(다른 경보가 울리는
     * 중이었을 때), 화면은 지금 보고 있는 탭과 무관하게 항상 최신 상태로 새로고침해야
     * 한다: 경보상세를 보고 있었으면 배너가 사라지고, 오늘 이벤트를 보고 있었으면 방금
     * 해제된 항목의 상태 배지가 "자동 해제"로 바뀐다. onNewAlertArrived()와 달리 탭을
     * 강제로 옮기지는 않는다 — 사람이 보던 화면을 그대로 두고 내용만 갱신한다.
     */
    private fun onAlertResolvedFromEdge() {
        refreshAlertDetail()
        refreshEventList()
    }

    // ---------------------------------------------------------------------
    // 하단 네비게이션 (홈 / 경보상세 / 오늘 이벤트) — 기획안 5.5절 화면 3종
    // ---------------------------------------------------------------------

    private fun setupBottomNav() {
        homeContent = findViewById(R.id.homeContent)
        alertDetailContent = findViewById(R.id.alertDetailContent)
        eventListContent = findViewById(R.id.eventListContent)
        settingsContent = findViewById(R.id.settingsContent)

        findViewById<BottomNavigationView>(R.id.bottomNav).setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    showOnly(homeContent)
                    setTabTitle("홈")
                    true
                }
                R.id.nav_alert_detail -> {
                    showOnly(alertDetailContent)
                    refreshAlertDetail()
                    setTabTitle("경보상세")
                    true
                }
                R.id.nav_event_list -> {
                    showOnly(eventListContent)
                    refreshEventList()
                    setTabTitle("오늘 이벤트")
                    true
                }
                R.id.nav_settings -> {
                    showOnly(settingsContent)
                    refreshSettings()
                    setTabTitle("설정")
                    true
                }
                else -> false
            }
        }
    }

    /** 커스텀 액션바 뷰(view_actionbar_title) 안의 탭 이름 텍스트만 갱신한다. */
    private fun setTabTitle(title: String) {
        supportActionBar?.customView?.findViewById<TextView>(R.id.actionBarTabTitle)?.text = title
    }

    private fun showOnly(target: View) {
        homeContent.visibility = if (target === homeContent) View.VISIBLE else View.GONE
        alertDetailContent.visibility = if (target === alertDetailContent) View.VISIBLE else View.GONE
        eventListContent.visibility = if (target === eventListContent) View.VISIBLE else View.GONE
        settingsContent.visibility = if (target === settingsContent) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------------
    // ② 경보 상세 화면
    // ---------------------------------------------------------------------

    private fun refreshAlertDetail() {
        val levelBar = findViewById<TextView>(R.id.alertLevelBar)
        val timeText = findViewById<TextView>(R.id.alertTime)
        val titleText = findViewById<TextView>(R.id.alertTitle)
        val bodyText = findViewById<TextView>(R.id.alertBody)
        val emptyText = findViewById<View>(R.id.alertEmptyText)
        val ackButton = findViewById<android.widget.Button>(R.id.acknowledgeButton)
        val otherToggle = findViewById<LinearLayout>(R.id.otherAlertsToggle)

        val unacknowledged = EventStore.allUnacknowledged(this)
        val event = unacknowledged.firstOrNull()
        if (event == null) {
            // 활성 경보가 없을 때는 배너 자체를 숨긴다 — 굳이 회색 "없음" 바를 보여주지 않는다.
            levelBar.visibility = View.GONE
            timeText.visibility = View.GONE
            titleText.visibility = View.GONE
            bodyText.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
            ackButton.isEnabled = false
            ackButton.alpha = 0.5f
            otherToggle.visibility = View.GONE
            findViewById<LinearLayout>(R.id.otherAlertsContainer).visibility = View.GONE
            stopAlertImpactLoop()
            refreshAutoResolvedSummary()
            return
        }
        // 활성 경보가 있는 동안 카드 흔들림+진동 연출을 반복한다 — 이미 돌고 있으면
        // start가 아무것도 안 하므로, 탭을 오갈 때마다 호출돼도 매번 처음부터 다시 튀지 않는다.
        startAlertImpactLoop()

        levelBar.visibility = View.VISIBLE
        levelBar.text = "${event.level} 편차 발생"
        levelBar.setBackgroundColor(getColorRes(levelBgRes(event.level)))
        val fgColor = getColorRes(levelColorRes(event.level))
        levelBar.setTextColor(fgColor)
        levelBar.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(fgColor)
        timeText.visibility = View.VISIBLE
        titleText.visibility = View.VISIBLE
        bodyText.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        timeText.text = formatTime(event.id)
        titleText.text = event.title
        bodyText.text = event.body
        ackButton.isEnabled = true
        ackButton.alpha = 1f
        // 심각도와 무관하게 "확인" 버튼은 항상 브랜드 레드 하나로 통일한다 — 배너만
        // 심각도별로 색이 바뀌고, 사용자가 눌러야 할 유일한 동작은 항상 같은 색으로 눈에 띈다.
        ackButton.backgroundTintList = android.content.res.ColorStateList.valueOf(getColorRes(R.color.brand_red))
        ackButton.setOnClickListener {
            AlertPlayer.stop(this)
            EventStore.acknowledge(this, event.id)
            // 이 경보 말고도 아직 확인 안 된 다른 위반이 있으면 그걸 위해 알람을 다시 켠다
            // (2026-09-16 — resolveIfMatches와 동일한 이유, "확인"으로 지웠을 때도 같아야 함).
            AlertPlayer.promoteNextIfAny(this)
            Toast.makeText(this, "경보를 종료했습니다", Toast.LENGTH_SHORT).show()
            refreshAlertDetail()
            refreshEventList()
        }

        refreshOtherActiveAlerts(unacknowledged.drop(1))
    }

    // 경보상세의 "다른 활성 위반 N건" 펼치기 상태 — refreshAlertDetail()이 자동해제/새 경보로
    // 계속 다시 불려도(예: 실시간 새로고침) 사용자가 펼쳐본 상태가 매번 접히지 않게 기억한다.
    private var otherAlertsExpanded = false

    /**
     * 최신 1건 외에 아직 "확인" 안 된 위반이 더 있으면 "다른 활성 위반 N건 ▼" 토글을 보여준다
     * — 경보상세 카드가 최신 위반으로 조용히 대체되면서 그 전 위반(예: 2인1조 위반 도중
     * 헬멧 미착용까지 확정)을 놓치지 않도록 한다(2026-09-16, 실사용 중 발견). 펼치면 각
     * 항목을 오늘 이벤트와 같은 카드 행(buildEventRow)으로 보여준다 — 여기서 개별 "확인"은
     * 받지 않는다(동시에 여러 알람을 관리하는 건 범위 밖 — 오늘 이벤트 탭에서 처리).
     */
    private fun refreshOtherActiveAlerts(others: List<AlertEvent>) {
        val toggle = findViewById<LinearLayout>(R.id.otherAlertsToggle)
        val toggleText = findViewById<TextView>(R.id.otherAlertsToggleText)
        val chevron = findViewById<View>(R.id.otherAlertsChevron)
        val container = findViewById<LinearLayout>(R.id.otherAlertsContainer)

        if (others.isEmpty()) {
            toggle.visibility = View.GONE
            container.visibility = View.GONE
            otherAlertsExpanded = false
            return
        }

        toggle.visibility = View.VISIBLE
        toggleText.text = "다른 활성 위반 ${others.size}건"
        chevron.rotation = if (otherAlertsExpanded) 90f else 0f
        container.visibility = if (otherAlertsExpanded) View.VISIBLE else View.GONE
        container.removeAllViews()
        if (otherAlertsExpanded) {
            others.forEach { container.addView(buildEventRow(it)) }
        }
        toggle.setOnClickListener {
            otherAlertsExpanded = !otherAlertsExpanded
            refreshOtherActiveAlerts(others)
        }
    }

    /**
     * 경보상세가 "활성 경보 없음" 빈 화면일 때, 오늘 자동 해제(재감지로 조용히 해제)된
     * 위반이 있으면 그 건수를 알약(pill) 배지로 보여준다. 경보음은 이미 안 울리지만
     * ("자동해제=위험" 우려와 "매번 알림=피로" 우려를 동시에 해소하는 지점) 관리자가
     * 앱을 열면 오늘 뭔가 있었는지 바로 알 수 있게 하려는 것 — 새 알림을 보내는 게
     * 아니라 이미 기기에 저장된 이력만 세므로 알림 피로를 늘리지 않는다. 눌러서 탭하면
     * 오늘 이벤트 탭으로 이동해 실제로 어떤 항목들인지 바로 확인할 수 있다.
     */
    private fun refreshAutoResolvedSummary() {
        val summary = findViewById<TextView>(R.id.autoResolvedSummary)
        val count = EventStore.autoResolvedCountToday(this)
        if (count <= 0) {
            summary.visibility = View.GONE
            summary.setOnClickListener(null)
            return
        }
        summary.visibility = View.VISIBLE
        summary.text = "오늘 자동 해제된 위반 ${count}건 · 눌러서 보기"
        summary.setOnClickListener {
            findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = R.id.nav_event_list
        }
    }

    // 활성 경보가 있는 동안, "확인"을 누르기 전까지 경보상세 카드를 흔들림+진동으로
    // 반복 강조. 반복 간격은 4초 —
    // 사용자가 요청한 3~5초 범위 안에서 고른 값.
    private var alertImpactHandler: Handler? = null
    private var alertImpactRunnable: Runnable? = null

    /** 이미 돌고 있으면 아무것도 하지 않는다 — refreshAlertDetail()이 탭을 오갈 때마다
     *  호출해도 매번 처음부터 다시 튀지 않고, 원래 예약된 다음 tick까지 그대로 이어간다. */
    private fun startAlertImpactLoop() {
        if (alertImpactHandler != null) return
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                playAlertImpact()
                handler.postDelayed(this, ALERT_IMPACT_INTERVAL_MS)
            }
        }
        alertImpactHandler = handler
        alertImpactRunnable = runnable
        // 첫 tick은 지금 즉시 — 새 경보가 뜨자마자, 혹은 아직 안 끝난 경보 화면으로
        // 돌아오자마자 한 번 강하게 흔들리게 한다.
        handler.post(runnable)
    }

    private fun stopAlertImpactLoop() {
        alertImpactRunnable?.let { alertImpactHandler?.removeCallbacks(it) }
        alertImpactHandler = null
        alertImpactRunnable = null
    }

    /** 경보상세 카드를 좌우로 튕겼다가 잦아드는 흔들림 + 살짝 부풀었다 돌아오는 스케일
     *  펄스로 흔들고, 함께 강하고 입체적인(웅-웅-웅) 진동을 울린다. 진동은 설정 탭의
     *  "진동" 스위치를 그대로 따른다 — [AlertPlayer.trigger]의 최초 1회 진동과 같은 규칙. */
    private fun playAlertImpact() {
        val cardView = findViewById<View>(R.id.alertDetailCard)

        if (AppSettings.isVibrationEnabled(this)) {
            vibrateImpact()
        }

        val density = resources.displayMetrics.density
        val shakeAmplitudePx = density * 10f
        val shakeX = ObjectAnimator.ofFloat(cardView, View.TRANSLATION_X, 0f, shakeAmplitudePx).apply {
            duration = 600
            interpolator = DangerShakeInterpolator()
        }
        val scaleX = ObjectAnimator.ofFloat(cardView, View.SCALE_X, 1f, 1.04f, 0.985f, 1f).apply {
            duration = 600
        }
        val scaleY = ObjectAnimator.ofFloat(cardView, View.SCALE_Y, 1f, 1.04f, 0.985f, 1f).apply {
            duration = 600
        }
        AnimatorSet().apply {
            playTogether(shakeX, scaleX, scaleY)
            start()
        }
    }

    /** [AlertPlayer]의 최초 1회 진동(400/200/400 단순 패턴)보다 굴곡을 준 웨이브폼 —
     *  "웅-웅-웅" 하고 세 번 강하게 끊어 치는 느낌을 낸다. */
    private fun vibrateImpact() {
        val timings = longArrayOf(0, 150, 80, 150, 80, 140)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val amplitudes = intArrayOf(0, 255, 0, 230, 0, 200)
            val manager = getSystemService(VibratorManager::class.java)
            manager.vibrate(CombinedVibration.createParallel(VibrationEffect.createWaveform(timings, amplitudes, -1)))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitudes = intArrayOf(0, 255, 0, 230, 0, 200)
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(
                VibrationEffect.createWaveform(timings, amplitudes, -1)
            )
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(timings, -1)
        }
    }

    // ---------------------------------------------------------------------
    // ③ 오늘 이벤트 목록 — 캘린더에서 날짜를 고르면 그날 이력만 보여준다.
    // ---------------------------------------------------------------------

    // 시안(캔버스)의 커스텀 월 그리드 — "화면에 지금 펼쳐 놓은 달"은 선택한 날짜와
    // 별개로 둔다 (화살표로 달만 넘기고 아직 날짜를 안 골랐을 수도 있으므로).
    private lateinit var calendarGrid: LinearLayout
    private lateinit var calendarMonthLabel: TextView
    private var displayedMonth: Calendar = Calendar.getInstance()

    private fun setupEventCalendar() {
        calendarGrid = findViewById(R.id.calendarGrid)
        calendarMonthLabel = findViewById(R.id.calendarMonthLabel)
        displayedMonth = Calendar.getInstance().apply {
            timeInMillis = selectedDateMillis
            set(Calendar.DAY_OF_MONTH, 1)
        }

        findViewById<View>(R.id.calendarPrevMonth).setOnClickListener {
            displayedMonth.add(Calendar.MONTH, -1)
            renderCalendarGrid()
        }
        findViewById<View>(R.id.calendarNextMonth).setOnClickListener {
            displayedMonth.add(Calendar.MONTH, 1)
            renderCalendarGrid()
        }
        // 화살표는 한 달씩, 가운데 월 라벨은 연도를 훌쩍 건너뛰고 싶을 때 쓰라고
        // MaterialDatePicker(연도 그리드 내장)를 띄운다 — 두 가지 방법을 다 열어둔다.
        calendarMonthLabel.setOnClickListener {
            openYearMonthPicker()
        }

        renderCalendarGrid()
    }

    private fun openYearMonthPicker() {
        // MaterialDatePicker는 선택값을 "UTC 자정" 기준 millis로 다룬다.
        // 로컬 타임존 그대로 넘기면 기기 시간대에 따라 하루 밀릴 수 있어 변환해준다.
        val utcSelection = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(displayedMonth.get(Calendar.YEAR), displayedMonth.get(Calendar.MONTH), 1)
        }.timeInMillis

        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("날짜 선택")
            .setSelection(utcSelection)
            .build()

        picker.addOnPositiveButtonClickListener { utcMillis ->
            val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
            val localCal = Calendar.getInstance().apply {
                set(
                    utcCal.get(Calendar.YEAR), utcCal.get(Calendar.MONTH), utcCal.get(Calendar.DAY_OF_MONTH),
                    0, 0, 0
                )
                set(Calendar.MILLISECOND, 0)
            }
            selectedDateMillis = localCal.timeInMillis
            displayedMonth = Calendar.getInstance().apply {
                timeInMillis = selectedDateMillis
                set(Calendar.DAY_OF_MONTH, 1)
            }
            renderCalendarGrid()
            refreshEventList()
        }
        picker.show(supportFragmentManager, "event_date_picker")
    }

    /** 현재 displayedMonth 기준으로 월 라벨 + 요일 그리드를 통째로 다시 그린다. */
    private fun renderCalendarGrid() {
        val labelFormatter = SimpleDateFormat("yyyy년 M월", Locale.KOREA)
        calendarMonthLabel.text = labelFormatter.format(displayedMonth.time)

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val year = displayedMonth.get(Calendar.YEAR)
        val month = displayedMonth.get(Calendar.MONTH)
        val daysInMonth = displayedMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
        // DAY_OF_WEEK: 1=일 ~ 7=토 — 그리드 첫 칸(일요일 열)이 몇 번째부터 시작하는지 계산.
        val firstWeekday = Calendar.getInstance().apply {
            set(year, month, 1)
        }.get(Calendar.DAY_OF_WEEK)

        val eventDays = EventStore.datesWithEventsInMonth(this, year, month)

        val today = Calendar.getInstance()
        val isTodayMonth = today.get(Calendar.YEAR) == year && today.get(Calendar.MONTH) == month
        val selectedCal = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
        val isSelectedMonth = selectedCal.get(Calendar.YEAR) == year && selectedCal.get(Calendar.MONTH) == month

        calendarGrid.removeAllViews()
        var day = 1 - (firstWeekday - 1)
        while (day <= daysInMonth) {
            val weekRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
            for (col in 0 until 7) {
                val dayNumber = day
                val cellWrapper = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
                }
                if (dayNumber in 1..daysInMonth) {
                    val isToday = isTodayMonth && today.get(Calendar.DAY_OF_MONTH) == dayNumber
                    val isSelected = isSelectedMonth && selectedCal.get(Calendar.DAY_OF_MONTH) == dayNumber
                    val hasEvent = eventDays.contains(dayNumber)

                    val cell = TextView(this).apply {
                        text = dayNumber.toString()
                        textSize = 12.5f
                        gravity = Gravity.CENTER
                        layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)
                        when {
                            isToday -> {
                                background = ContextCompat.getDrawable(context, R.drawable.bg_day_today)
                                setTextColor(getColorRes(R.color.white))
                                setTypeface(typeface, android.graphics.Typeface.BOLD)
                            }
                            isSelected -> {
                                background = ContextCompat.getDrawable(context, R.drawable.bg_day_selected)
                                setTextColor(getColorRes(R.color.brand_red))
                                setTypeface(typeface, android.graphics.Typeface.BOLD)
                            }
                        }
                        setOnClickListener {
                            selectedDateMillis = Calendar.getInstance().apply {
                                set(year, month, dayNumber, 0, 0, 0)
                                set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            renderCalendarGrid()
                            refreshEventList()
                        }
                    }
                    cellWrapper.addView(cell)

                    if (hasEvent) {
                        val dot = View(this).apply {
                            layoutParams = FrameLayout.LayoutParams(dp(4), dp(4), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                                bottomMargin = dp(3)
                            }
                            // 점은 원(오늘/선택) 바깥 흰 여백에 찍히므로, 오늘 칸이어도
                            // 항상 브랜드 레드로 — 흰 원 위에 흰 점이 되어 안 보이는 걸 방지.
                            background = ContextCompat.getDrawable(context, R.drawable.bg_dot)
                            backgroundTintList = android.content.res.ColorStateList.valueOf(getColorRes(R.color.brand_red))
                        }
                        cellWrapper.addView(dot)
                    }
                }
                weekRow.addView(cellWrapper)
                day++
            }
            calendarGrid.addView(weekRow)
        }
    }

    private fun refreshEventList() {
        val dateTitleView = findViewById<TextView>(R.id.eventListDateTitle)
        val emptyLabel = findViewById<TextView>(R.id.eventListEmptyLabel)
        val titleView = findViewById<TextView>(R.id.eventListTitle)
        val emptyText = findViewById<View>(R.id.eventListEmptyText)
        val container = findViewById<LinearLayout>(R.id.eventListContainer)

        val isToday = isSameDay(selectedDateMillis, System.currentTimeMillis())
        val dateLabel = if (isToday) "오늘 이벤트" else "${formatDateShort(selectedDateMillis)} 이벤트"
        dateTitleView.text = dateLabel
        emptyLabel.text = if (isToday) "오늘 발생한 이벤트가 없습니다." else "선택한 날짜에 이벤트가 없습니다."

        val events = EventStore.eventsForDate(this, selectedDateMillis)
        titleView.text = "${events.size}건"
        emptyText.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE

        container.removeAllViews()
        events.forEach { event -> container.addView(buildEventRow(event)) }
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    private fun formatDateShort(timestampMillis: Long): String {
        val formatter = SimpleDateFormat("M월 d일", Locale.KOREA)
        return formatter.format(Date(timestampMillis))
    }

    private fun buildEventRow(event: AlertEvent): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = getColorRes(R.color.outline)
            setCardBackgroundColor(getColorRes(R.color.surface))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val textBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val header = TextView(this).apply {
            text = event.title
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val detail = TextView(this).apply {
            text = "${formatTime(event.id)} · ${event.body}"
            textSize = 12f
            setTextColor(getColorRes(R.color.text_secondary))
            setPadding(0, dp(3), 0, 0)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val levelChip = Chip(this).apply {
            text = event.level
            textSize = 11f
            setTextColor(getColorRes(levelColorRes(event.level)))
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(getColorRes(levelBgRes(event.level)))
            chipStrokeWidth = 0f
            chipMinHeight = dp(22).toFloat()
            chipStartPadding = dp(8).toFloat()
            chipEndPadding = dp(8).toFloat()
            isClickable = false
            isFocusable = false
            isCheckable = false
            setEnsureMinTouchTargetSize(false)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }

        textBlock.addView(header)
        textBlock.addView(detail)
        textBlock.addView(levelChip)

        val statusChip = Chip(this).apply {
            // autoResolved: 사람이 "확인"을 누른 게 아니라 엣지 PC가 재감지로 자동 해제한 것
            // (기획안 5.6절) — 배지 색/아이콘은 같지만 문구로 구분해준다.
            text = if (event.autoResolved) "자동 해제" else if (event.acknowledged) "확인 완료" else "미확인"
            textSize = 11f
            val statusColor = if (event.acknowledged) R.color.status_ok else R.color.status_pending
            setTextColor(getColorRes(statusColor))
            chipIcon = ContextCompat.getDrawable(
                this@MainActivity,
                if (event.acknowledged) R.drawable.ic_check_circle else R.drawable.ic_nav_alert
            )
            chipIconTint = android.content.res.ColorStateList.valueOf(getColorRes(statusColor))
            chipIconSize = dp(13).toFloat()
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            chipStrokeWidth = 0f
            chipMinHeight = dp(24).toFloat()
            isClickable = false
            isFocusable = false
            isCheckable = false
            setEnsureMinTouchTargetSize(false)
        }

        row.addView(textBlock)
        row.addView(statusChip)
        card.addView(row)
        return card
    }

    private fun levelColorRes(level: String): Int = when (level) {
        "중대" -> R.color.level_critical
        "주의" -> R.color.level_caution
        else -> R.color.level_normal
    }

    /** levelColorRes()의 옅은 배경 짝 — 배너 · 칩 배경에 쓴다. */
    private fun levelBgRes(level: String): Int = when (level) {
        "중대" -> R.color.critical_bg
        "주의" -> R.color.caution_bg
        else -> R.color.normal_bg
    }

    private fun getColorRes(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun formatTime(timestampMillis: Long): String {
        val formatter = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA)
        return formatter.format(Date(timestampMillis))
    }

    // ---------------------------------------------------------------------
    // ① 홈 — 토큰 표시 · 볼륨 슬라이더
    // ---------------------------------------------------------------------

    /**
     * 슬라이더를 기기의 "알람(Alarm)" 볼륨 스트림에 직접 연결한다.
     * 경보음(MediaPlayer)과 TTS 모두 이 스트림으로 재생되므로(AlertPlayer 참고),
     * 소리가 나오는 도중에 슬라이더를 움직이면 그 자리에서 바로 커지고 작아진다.
     */
    private fun setupVolumeControl() {
        val volumeLabel = findViewById<TextView>(R.id.volumeLabel)
        val volumeSlider = findViewById<Slider>(R.id.volumeSlider)
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        volumeSlider.valueTo = maxVolume.toFloat()

        fun updateLabel(level: Int) {
            val percent = if (maxVolume > 0) level * 100 / maxVolume else 0
            volumeLabel.text = "경보음 크기: $percent%"
        }

        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        volumeSlider.value = currentVolume.toFloat().coerceIn(0f, maxVolume.toFloat())
        updateLabel(currentVolume)

        volumeSlider.addOnChangeListener { _, value, fromUser ->
            val level = value.toInt()
            updateLabel(level)
            if (fromUser) {
                // FLAG_SHOW_UI 없이 즉시 반영 — 우리 슬라이더가 이미 크기를 보여주고 있다.
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, level, 0)
            }
        }
    }

    // ---------------------------------------------------------------------
    // ④ 설정 — 경보음 선택 · TTS/진동 켜고 끄기 · 이력 삭제 · 앱 정보
    // ---------------------------------------------------------------------

    private fun setupSettingsScreen() {
        findViewById<View>(R.id.soundPickerRow).setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "경보음 선택")
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, AppSettings.getAlarmSoundUri(this@MainActivity))
            }
            soundPickerLauncher.launch(intent)
        }

        findViewById<SwitchMaterial>(R.id.ttsSwitch).apply {
            isChecked = AppSettings.isTtsEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                AppSettings.setTtsEnabled(this@MainActivity, isChecked)
            }
        }

        findViewById<SwitchMaterial>(R.id.vibrationSwitch).apply {
            isChecked = AppSettings.isVibrationEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                AppSettings.setVibrationEnabled(this@MainActivity, isChecked)
            }
        }

        findViewById<View>(R.id.clearHistoryButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("오늘 이벤트 전체 삭제")
                .setMessage("기록된 경보 이력을 모두 지웁니다. 이 동작은 되돌릴 수 없습니다.")
                .setPositiveButton("삭제") { _, _ ->
                    EventStore.clearAll(this)
                    Toast.makeText(this, "이벤트 이력을 삭제했습니다", Toast.LENGTH_SHORT).show()
                    refreshEventList()
                    refreshAlertDetail()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        findViewById<View>(R.id.openNotificationSettingsButton).setOnClickListener {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            }
            startActivity(intent)
        }

        findViewById<TextView>(R.id.appVersionText).text =
            "RT SOP 알림 v${BuildConfig.VERSION_NAME}"

        updateSoundNameText()
    }

    private fun refreshSettings() {
        updateSoundNameText()
    }

    private fun updateSoundNameText() {
        val uri = AppSettings.getAlarmSoundUri(this) ?: run {
            findViewById<TextView>(R.id.soundNameText).text = "시스템 기본 알람음"
            return
        }
        val name = runCatching { RingtoneManager.getRingtone(this, uri)?.getTitle(this) }.getOrNull()
        findViewById<TextView>(R.id.soundNameText).text = name ?: "선택한 알림음"
    }

    private fun loadToken() {
        // 저장된 토큰이 있으면 우선 표시하고, 최신 토큰을 다시 조회해 갱신한다.
        TokenStore.get(this)?.let { tokenText.text = it }

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                tokenText.text = "토큰 조회 실패: ${task.exception?.message}\n" +
                    "google-services.json이 app/ 폴더에 있는지 확인하세요."
                return@addOnCompleteListener
            }
            val token = task.result
            TokenStore.save(this, token)
            tokenText.text = token
        }
    }
}
