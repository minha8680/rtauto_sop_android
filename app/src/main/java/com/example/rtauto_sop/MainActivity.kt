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
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.CalendarView
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
                    true
                }
                R.id.nav_alert_detail -> {
                    showOnly(alertDetailContent)
                    refreshAlertDetail()
                    true
                }
                R.id.nav_event_list -> {
                    showOnly(eventListContent)
                    refreshEventList()
                    true
                }
                R.id.nav_settings -> {
                    showOnly(settingsContent)
                    refreshSettings()
                    true
                }
                else -> false
            }
        }
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
            levelBar.text = "활성 경보 없음"
            levelBar.setBackgroundColor(getColorRes(R.color.level_idle))
            timeText.visibility = View.GONE
            titleText.visibility = View.GONE
            bodyText.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
            ackButton.isEnabled = false
            ackButton.alpha = 0.5f
            return
        }

        levelBar.text = "${event.level} 편차 발생"
        levelBar.setBackgroundColor(getColorRes(levelColorRes(event.level)))
        timeText.visibility = View.VISIBLE
        titleText.visibility = View.VISIBLE
        bodyText.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        timeText.text = formatTime(event.id)
        titleText.text = event.title
        bodyText.text = event.body
        ackButton.isEnabled = true
        ackButton.alpha = 1f
        ackButton.backgroundTintList = android.content.res.ColorStateList.valueOf(getColorRes(levelColorRes(event.level)))
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

    private fun setupEventCalendar() {
        val calendarView = findViewById<CalendarView>(R.id.eventCalendarView)
        calendarView.date = selectedDateMillis
        calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            selectedDateMillis = Calendar.getInstance().apply {
                set(year, month, dayOfMonth, 0, 0, 0)
            }.timeInMillis
            updateCalendarMonthLabel()
            refreshEventList()
        }
        updateCalendarMonthLabel()

        // CalendarView 자체의 "<  2026년 9월  >" 머리글은 한 달씩만 넘어가고 탭도
        // 못 받아서, 연도를 훌쩍 건너뛰고 싶을 때 쓰라고 별도 라벨을 두고
        // MaterialDatePicker(연도 그리드 내장)를 띄운다.
        findViewById<View>(R.id.calendarHeaderRow).setOnClickListener {
            openYearMonthPicker(calendarView)
        }
    }

    private fun openYearMonthPicker(calendarView: CalendarView) {
        val localNow = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
        // MaterialDatePicker는 선택값을 "UTC 자정" 기준 millis로 다룬다.
        // 로컬 타임존 그대로 넘기면 기기 시간대에 따라 하루 밀릴 수 있어 변환해준다.
        val utcSelection = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(localNow.get(Calendar.YEAR), localNow.get(Calendar.MONTH), localNow.get(Calendar.DAY_OF_MONTH))
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
            calendarView.date = selectedDateMillis
            updateCalendarMonthLabel()
            refreshEventList()
        }
        picker.show(supportFragmentManager, "event_date_picker")
    }

    private fun updateCalendarMonthLabel() {
        val formatter = SimpleDateFormat("yyyy년 M월", Locale.KOREA)
        findViewById<TextView>(R.id.calendarMonthLabel).text = formatter.format(Date(selectedDateMillis))
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
            setTextColor(getColorRes(R.color.white))
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(getColorRes(levelColorRes(event.level)))
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
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(getColorRes(statusColor)).withAlpha(30)
            chipStrokeWidth = dp(1).toFloat()
            chipStrokeColor = android.content.res.ColorStateList.valueOf(getColorRes(statusColor))
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

        findViewById<android.widget.Button>(R.id.clearHistoryButton).setOnClickListener {
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

        findViewById<android.widget.Button>(R.id.openNotificationSettingsButton).setOnClickListener {
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
