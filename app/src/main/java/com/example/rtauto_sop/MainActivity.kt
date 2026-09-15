package com.example.rtauto_sop

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 저장된 다크모드 값을 화면이 그려지기 전에 먼저 적용한다.
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
        // 프로그램적으로 selectedItemId를 바꿀 때와 달리, 최초 진입 시엔 리스너가 안 불려서
        // 기본 탭(홈)의 액션바 제목을 따로 한 번 맞춰준다.
        if (savedInstanceState == null) {
            setTabTitle("홈")
        }
        // savedInstanceState가 null일 때만 인트로를 재생한다 — 다크모드 전환 등으로
        // 액티비티가 recreate될 때는 non-null이라, 설정을 바꿀 때마다 매번 스플래시가
        // 다시 뜨는 걸 막는다. 진짜 첫 실행에서만 보이면 된다.
        if (savedInstanceState == null) {
            runIntroAnimation()
        } else {
            findViewById<View>(R.id.introOverlay).visibility = View.GONE
            // 다크모드 전환 등으로 recreate될 때 보고 있던 탭 그대로 복원한다.
            // (홈/경보상세/이벤트목록 컨테이너의 visibility는 기본 View 상태 저장에
            // 포함되지 않아, 복원해주지 않으면 매번 홈 화면으로 되돌아간다.)
            val savedNavId = savedInstanceState.getInt(KEY_SELECTED_NAV_ID, R.id.nav_home)
            findViewById<BottomNavigationView>(R.id.bottomNav).selectedItemId = savedNavId
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

        val event = EventStore.latestUnacknowledged(this)
        if (event == null) {
            // 활성 경보가 없을 때는 배너 자체를 숨긴다 — 굳이 회색 "없음" 바를 보여주지 않는다.
            levelBar.visibility = View.GONE
            timeText.visibility = View.GONE
            titleText.visibility = View.GONE
            bodyText.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
            ackButton.isEnabled = false
            ackButton.alpha = 0.5f
            return
        }

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
            Toast.makeText(this, "경보를 종료했습니다", Toast.LENGTH_SHORT).show()
            refreshAlertDetail()
            refreshEventList()
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
            text = if (event.acknowledged) "확인 완료" else "미확인"
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
