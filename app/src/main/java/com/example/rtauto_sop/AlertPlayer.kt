package com.example.rtauto_sop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.CombinedVibration
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale

/**
 * 편차 경보 발생 시 알림 표시 + 경고음 재생 + 진동 + TTS 안내를 한 번에 처리한다.
 * 알림 · 경고음 · 진동은 도착 시 1회. TTS 음성 안내는 경고음과 동시에 시작해서(경고음 완료를
 * 기다리지 않는다 — 실제 알람 사운드는 자연 종료 없이 몇 분씩 이어지는 것도 흔해서, 기다리면
 * 안내가 아예 안 나올 수 있다) "확인"을 누르기 전까지 10초 간격으로 계속 반복한다. 1차 MVP
 * 범위: 사운드/진동까지 반복하는 전체화면 강제 경보(30초 재발송)는 아직 아님 — 이후 단계에서 추가.
 *
 * 경보를 멈추는 경로는 둘 — 사람이 "확인" 버튼을 눌러 [stop]을 직접 호출하거나, 엣지 PC가
 * 같은 위반이 재감지 없이 해제됐다고 FCM으로 알려와서 [resolveIfMatches]가 [stop]을 대신
 * 호출하거나(기획안 5.6절 "해제 조건" — 사람의 확인이 아니라 동일 검출 경로 재확인으로만 해제).
 */
object AlertPlayer {

    private const val TAG = "AlertPlayer"
    private const val CHANNEL_ID_RES_NAME = "alert_channel_id"
    private const val TTS_REPEAT_INTERVAL_MS = 10_000L
    private const val FULL_ALARM_VOLUME = 1f

    // 알림을 앱 안에서 하나로 묶어(그룹) 안드로이드가 "N건" 요약으로 쌓아 보여주게 한다 —
    // 위반이 여러 건 겹쳐도(예: 2인1조 위반 중 헬멧 미착용까지) 최신 것만 보이는 게 아니라
    // 알림 개수만큼 쌓이게 해달라는 요청(2026-09-16, 실사용 중 발견)에 대응.
    private const val ALERT_GROUP_KEY = "com.example.rtauto_sop.ALERT_GROUP"
    private const val SUMMARY_NOTIFICATION_ID = 999_999_999   // 개별 경보 id(currentTimeMillis 기반)와 안 겹치는 고정값
    // 아직 취소 안 된 알림 id들 — 요약 문구의 "N건" 근거. showNotification()이 붙이는
    // deleteIntent(NotificationDismissReceiver)가 관리자가 트레이에서 알림을 직접 스와이프로
    // 지우거나 탭해서 자동 취소될 때도 [onNotificationDismissed]를 통해 이 목록에서 제거해준다 —
    // AlertPlayer.stop()(확인 버튼 / 엣지 PC 해제)만 믿으면 트레이 직접 조작이 반영 안 돼
    // 요약 건수가 실제보다 많게 어긋났었다(2026-09-16 발견, 이제 수정됨).
    private val activeNotificationIds = mutableListOf<Int>()

    // TTS가 말하는 동안 경고음을 이 크기까지 낮춘다(덕킹) — 완전히 죽이지는 않아서
    // "아직 경보가 울리고 있다"는 감각은 남기되, 음성 문구가 또렷하게 들리게 한다.
    private const val DUCKED_ALARM_VOLUME = 0.25f
    private var ttsRef: TextToSpeech? = null

    // "확인" 버튼(알람 종료)이 지금 재생 중인 것을 즉시 멈출 수 있도록 붙잡아 둔다.
    private var currentPlayer: MediaPlayer? = null
    private var currentNotificationId: Int? = null

    // 지금 울리고 있는 경보의 (규칙, 대상) 식별자 — 예: "helmet:7". 엣지 PC가 나중에 보내는
    // "해제" 메시지가 지금 울리는 것과 같은 위반인지 맞춰볼 때 쓴다(resolveIfMatches 참고).
    // 로컬 테스트 경보처럼 key 없이 트리거된 경우 null.
    private var currentKey: String? = null

    // "확인"을 누르기 전까지 TTS를 10초마다 반복 재생하는 예약. stop()이나 새 경보 도착 시 취소한다.
    private var repeatHandler: Handler? = null
    private var repeatRunnable: Runnable? = null

    // MainActivity가 화면에 보이는 동안(onStart~onStop)에만 등록되는 콜백 — 새 경보가
    // 울리기 시작했다는 걸 UI에 알려서 경보상세 탭으로 자동 전환 + 강조 애니메이션을
    // 재생하게 한다. 앱이 백그라운드/종료 상태면 null이라 그냥 무시된다.
    private var uiListener: (() -> Unit)? = null

    // 위와 별개로, 엣지 PC가 위반 "해제"(kind=resolved)를 보고했을 때 등록되는 콜백 —
    // 새 경보와 달리 탭을 강제로 옮기지는 않고, 지금 보고 있는 화면(경보상세 배너, 오늘
    // 이벤트의 "자동 해제" 라벨 등)만 최신 상태로 새로고침한다.
    private var uiResolvedListener: (() -> Unit)? = null

    fun setUiListener(listener: (() -> Unit)?) {
        uiListener = listener
    }

    fun setUiResolvedListener(listener: (() -> Unit)?) {
        uiResolvedListener = listener
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = context.getString(R.string.alert_channel_id)
        if (manager.getNotificationChannel(channelId) != null) return

        val channel = NotificationChannel(
            channelId,
            context.getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            // 알림음 · 진동은 AlertPlayer가 직접 제어하므로 채널 기본음은 꺼둔다.
            setSound(null, null)
            enableVibration(false)
            description = "SOP 편차(2인 1조 위반 · 보호구 미착용 · 안전구역 침범 등) 발생 시 경보"
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * @param title 편차 제목 (예: "2인 1조 위반")
     * @param body  TTS로 읽어줄 상세 문구 (예: "세정기 구역, 2인 1조 위반, 09시 10분 발생")
     * @param key   엣지 PC의 (규칙, 대상) 식별자 — 예: "helmet:7". 이후 이 경보가 해제됐다는
     *              FCM 메시지가 오면 [resolveIfMatches]가 이 값과 비교해 자동으로 멈출지 판단한다.
     *              로컬 테스트 경보(홈 화면 "테스트 경보 재생")처럼 없으면 null.
     */
    fun trigger(context: Context, title: String, body: String, key: String? = null) {
        currentKey = key
        ensureChannel(context)
        showNotification(context, title, body)
        if (AppSettings.isVibrationEnabled(context)) {
            vibrate(context)
        }
        playAlarmSound(context)
        // TTS는 경고음 완료를 기다리지 않고 곧장 시작한다 — 실제 "알람" 사운드는 몇 분씩
        // 이어지도록 만들어진 것도 흔해서(자동으로 안 끝남), 완료 콜백을 기다렸다간 음성
        // 안내가 한참 늦어지거나 아예 안 나올 수 있다. 사이렌(경고음)과 안내 방송(TTS)이
        // 동시에 나온다고 보면 된다.
        if (AppSettings.isTtsEnabled(context)) {
            speak(context, body)
        }
        // "확인"을 누르기 전까지 10초 간격으로 TTS만 계속 반복한다.
        scheduleTtsRepeat(context.applicationContext, body)

        // FCM 수신은 백그라운드 스레드에서 이 함수를 호출할 수 있고, 테스트 버튼은 메인
        // 스레드에서 호출한다 — 어느 쪽이든 UI 콜백은 항상 메인 스레드에서 실행되게 post한다.
        Handler(Looper.getMainLooper()).post { uiListener?.invoke() }
    }

    /** 10초마다 [speak]를 다시 호출해, "확인"을 누르기 전까지 음성 안내를 반복한다. */
    private fun scheduleTtsRepeat(appContext: Context, body: String) {
        cancelTtsRepeat()
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                // 매 반복마다 최신 설정을 확인한다 — 반복 도중 설정 탭에서 TTS를 꺼도 바로 반영되게.
                if (AppSettings.isTtsEnabled(appContext)) {
                    Log.i(TAG, "TTS 반복 재생 (10초 간격): $body")
                    speak(appContext, body)
                }
                handler.postDelayed(this, TTS_REPEAT_INTERVAL_MS)
            }
        }
        repeatHandler = handler
        repeatRunnable = runnable
        handler.postDelayed(runnable, TTS_REPEAT_INTERVAL_MS)
    }

    private fun cancelTtsRepeat() {
        repeatRunnable?.let { repeatHandler?.removeCallbacks(it) }
        repeatHandler = null
        repeatRunnable = null
    }

    private fun showNotification(context: Context, title: String, body: String) {
        val notificationId = System.currentTimeMillis().toInt()

        // getLaunchIntentForPackage()가 아니라 명시적으로 MainActivity를 지정하고 extra를
        // 실어 보낸다 — 그래야 MainActivity.onCreate()/onNewIntent()가 "알림을 탭해서
        // 들어왔다"는 걸 알고 홈 대신 경보상세 탭으로 바로 연다.
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_ALERT_DETAIL, true)
        }
        // requestCode를 매번 0으로 주면 안드로이드가 "같은 PendingIntent"로 보고 최신 알림의
        // extras로 덮어써버린다(내용은 어차피 다 같은 extra라 지금은 체감 차이가 없지만,
        // 여러 알림이 각자 독립된 tap 대상을 갖게 notificationId를 requestCode로 준다).
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 관리자가 이 알림을 트레이에서 직접 스와이프로 지우거나(또는 탭해서 setAutoCancel(true)로
        // 자동 취소되거나) 하면 NotificationDismissReceiver가 이 알림의 id를 받아
        // activeNotificationIds에서 지우고 요약 건수를 다시 계산한다 — requestCode도 notificationId로
        // 줘서 contentIntent와 마찬가지로 알림마다 독립된 PendingIntent가 되게 한다.
        val deleteIntent = PendingIntent.getBroadcast(
            context, notificationId,
            Intent(context, NotificationDismissReceiver::class.java)
                .putExtra(NotificationDismissReceiver.EXTRA_NOTIFICATION_ID, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, context.getString(R.string.alert_channel_id))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deleteIntent)
            // 같은 그룹으로 묶어서, 위반이 여러 건 쌓이면 안드로이드가 "N건"으로 정리해
            // 보여주게 한다 — 최신 것만 남고 이전 것들이 안 보이는 문제(2026-09-16 발견) 대응.
            .setGroup(ALERT_GROUP_KEY)
            .build()

        currentNotificationId = notificationId
        activeNotificationIds.add(notificationId)
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        updateGroupSummary(context)
    }

    /**
     * 그룹 요약 알림 — 지금 확인 안 된(취소 안 된) SOP 경보가 몇 건인지 보여준다. 개별
     * 경보 알림들과 같은 [ALERT_GROUP_KEY]로 묶여 있어서, 안드로이드가 여러 건을 접어
     * 보여줄 때 이 요약이 맨 위에 뜬다. 활성 알림이 하나도 없으면 요약 자체를 지운다.
     */
    private fun updateGroupSummary(context: Context) {
        if (activeNotificationIds.isEmpty()) {
            NotificationManagerCompat.from(context).cancel(SUMMARY_NOTIFICATION_ID)
            return
        }
        val text = "확인 안 된 위반 ${activeNotificationIds.size}건"
        val summary = NotificationCompat.Builder(context, context.getString(R.string.alert_channel_id))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("RT SOP 알림")
            .setContentText(text)
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setGroup(ALERT_GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(false)
            .build()
        NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, summary)
    }

    /**
     * [NotificationDismissReceiver]가 호출한다 — 관리자가 개별 알림을 트레이에서 직접 지웠을 때
     * (스와이프, 또는 탭에 의한 자동 취소) [activeNotificationIds]/요약 건수를 실제 상태에 맞게
     * 바로잡는다. [stop]과 달리 재생 중인 소리·진동·TTS는 건드리지 않는다 — 알림을 지웠다고
     * 알람까지 자동으로 꺼지면 안 되고(그건 "확인" 버튼의 역할), 이건 순전히 알림 개수 표시만
     * 맞추는 용도다.
     */
    fun onNotificationDismissed(context: Context, notificationId: Int) {
        if (activeNotificationIds.remove(notificationId)) {
            updateGroupSummary(context)
        }
    }

    private fun vibrate(context: Context) {
        val pattern = longArrayOf(0, 400, 200, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager.vibrate(CombinedVibration.createParallel(VibrationEffect.createWaveform(pattern, -1)))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    private fun playAlarmSound(context: Context) {
        // 경보가 연달아 울릴 때 이전 소리 위에 새 소리가 겹쳐 쌓이지 않도록,
        // 새로 재생하기 전에 지금 재생 중인 것부터 확실히 멈추고 정리한다.
        // (이걸 안 하면 currentPlayer가 새 인스턴스로 덮어써지면서 이전 MediaPlayer는
        // 참조를 잃은 채 계속 재생되고, "확인"을 눌러도 최근 것만 멈추고
        // 예전 것들은 못 끄는 상태가 된다.)
        stopCurrentSound()
        // 이전 경보의 TTS가 아직 말하는 중이었다면 그것도 같이 끊는다 —
        // 새 경고음과 옛 음성 안내가 동시에 겹쳐 들리지 않게.
        ttsRef?.stop()
        // 이전 경보의 10초 반복 예약도 취소 — 안 하면 이전 문구와 새 문구가 번갈아 겹쳐 들린다.
        cancelTtsRepeat()

        // 설정 탭에서 사용자가 고른 경보음이 있으면 그걸 쓰고, 없으면 기기 기본 알람음을 쓴다.
        val alarmUri = AppSettings.resolveAlarmSoundUri(context)

        // 소프트웨어 배율은 항상 100%로 두고(1f), 실제 크기는 기기의 "알람" 스트림 볼륨이 결정한다.
        // MainActivity의 슬라이더가 AudioManager.STREAM_ALARM을 직접 조절하므로,
        // 재생 도중 슬라이더를 움직이면 이 소리도 그 자리에서 바로 커지고 작아진다.
        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, alarmUri)
                setVolume(FULL_ALARM_VOLUME, FULL_ALARM_VOLUME)
                setOnPreparedListener { it.start() }
                setOnCompletionListener {
                    if (currentPlayer === it) currentPlayer = null
                    it.release()
                }
                setOnErrorListener { mp, _, _ ->
                    if (currentPlayer === mp) currentPlayer = null
                    mp.release()
                    true
                }
                // prepare()는 완료될 때까지 호출 스레드를 막는 블로킹 호출이라, 트리거 경로가
                // 메인 스레드(테스트 버튼의 클릭 리스너)를 타는 이상 여기서 쓰면 오디오 백엔드가
                // 느릴 때 그대로 ANR로 이어진다 — 반드시 prepareAsync()로 비동기 준비하고
                // setOnPreparedListener에서 재생을 시작한다.
                prepareAsync()
            }
            currentPlayer = player
        } catch (e: Exception) {
            // 경보음 URI가 깨져 있거나(기기에 기본 알람음이 없는 경우 등) setDataSource/prepareAsync가
            // 던지는 예외를 여기서 잡지 않으면 앱 전체가 크래시로 죽는다 — 경보음만 건너뛰고 TTS는 계속한다.
            currentPlayer = null
        }
    }

    private fun speak(context: Context, body: String) {
        // 매번 새로 만들지 않고, 이미 초기화된 인스턴스가 있으면 재사용한다.
        val existing = ttsRef
        if (existing != null) {
            speakWith(context, existing, body)
            return
        }
        val tts = arrayOfNulls<TextToSpeech>(1)
        tts[0] = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts[0]?.language = Locale.KOREAN
                ttsRef = tts[0]
                tts[0]?.let { speakWith(context, it, body) }
            }
        }
    }

    /**
     * "확인"(알람 종료) 버튼에서 호출한다.
     * 재생 중인 경고음 · TTS를 즉시 멈추고, 진동을 취소하고, 떠 있는 알림을 지운다.
     */
    fun stop(context: Context) {
        cancelTtsRepeat()
        stopCurrentSound()

        ttsRef?.stop()

        cancelVibration(context)

        currentNotificationId?.let {
            NotificationManagerCompat.from(context).cancel(it)
            activeNotificationIds.remove(it)
            updateGroupSummary(context)
        }
        currentNotificationId = null
        currentKey = null
    }

    /**
     * 엣지 PC가 FCM으로 "위반 해제"를 보고했을 때 [AlertFcmService]에서 호출한다
     * (기획안 5.6절 — 해제는 사람의 확인이 아니라 엣지 PC의 재감지로만 이뤄져야 함).
     * 지금 울리고 있는 경보의 [currentKey]와 [key]가 일치할 때만 [stop]을 호출한다 —
     * 안 그러면 다른 위반이 마침 울리는 도중에 방금 해제된 이전 위반 신호 때문에
     * 엉뚱하게 꺼져버릴 수 있다. key가 안 맞아 알람은 안 멈추더라도, 호출자
     * (AlertFcmService)가 이미 EventStore를 갱신한 뒤이므로 화면 새로고침 콜백은
     * 항상 알린다 — 예: 이미 "확인"으로 종료된 경보의 해제 신호가 뒤늦게 와도, 오늘
     * 이벤트 목록의 상태 라벨("자동 해제")은 갱신돼야 한다.
     */
    fun resolveIfMatches(context: Context, key: String) {
        if (key.isNotEmpty() && key == currentKey) {
            stop(context)
            // 지금 울리던 알람을 껐는데 아직 확인 안 된 다른 위반이 남아있으면 그걸 위해
            // 알람을 새로 켠다 — 안 그러면 카드는 다음 위반으로 조용히 바뀌는데 경고음·
            // 진동·TTS는 다시 안 울리는 문제가 있었다(2026-09-16 사용자 피드백: "헬멧
            // 미착용이 자동해제되고 2인1조가 다음 경보로 나왔는데 TTS가 안 들렸다").
            promoteNextIfAny(context)
        }
        Handler(Looper.getMainLooper()).post { uiResolvedListener?.invoke() }
    }

    /**
     * 알람을 하나 껐을 때(수동 확인이든 자동 해제든) 아직 확인 안 된 다른 위반이 있으면
     * 그걸 위해 [trigger]를 다시 호출해 경고음·진동·TTS·알림을 새로 켠다. 없으면 아무 일도
     * 안 한다. [resolveIfMatches]와 MainActivity의 "확인" 버튼 양쪽에서 호출한다.
     */
    fun promoteNextIfAny(context: Context) {
        val next = EventStore.latestUnacknowledged(context) ?: return
        trigger(context, next.title, next.body, next.key)
    }

    /** 지금 재생 중인 경고음(MediaPlayer)이 있으면 멈추고 리소스를 해제한다. */
    private fun stopCurrentSound() {
        currentPlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            player.release()
        }
        currentPlayer = null
    }

    private fun cancelVibration(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).cancel()
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
        }
    }

    private fun speakWith(context: Context, tts: TextToSpeech, body: String) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // TTS가 실제로 말하기 시작하는 순간에만 경고음을 낮춰서, 관리자가 문구를
                // 또렷하게 들을 수 있게 한다 — 경고음과 TTS가 같은 스트림 볼륨을 쓰다 보니
                // 사운드가 계속 원래 크기로 나오면 안내 음성이 묻힌다.
                duckAlarmSound()
            }
            override fun onDone(utteranceId: String?) {
                restoreAlarmSound()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                restoreAlarmSound()
            }
        })
        // TTS도 알람 스트림에 실어 보낸다 - 실제 크기는 위 MediaPlayer와 마찬가지로
        // 기기의 "알람" 스트림 볼륨(슬라이더)이 실시간으로 결정한다.
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
        }
        tts.speak(body, TextToSpeech.QUEUE_FLUSH, params, "sop_alert")
    }

    /** TTS가 말하는 동안 경고음(MediaPlayer)만 낮춘다 — 스트림 볼륨은 그대로 두고
     *  이 MediaPlayer 인스턴스의 소프트웨어 볼륨만 줄인다. */
    private fun duckAlarmSound() {
        runCatching { currentPlayer?.setVolume(DUCKED_ALARM_VOLUME, DUCKED_ALARM_VOLUME) }
            .onFailure { Log.w(TAG, "경고음 덕킹 실패 (무시하고 계속 진행)", it) }
    }

    /** TTS 발화가 끝나면(정상 종료든 에러든) 경고음을 원래 크기로 되돌린다. */
    private fun restoreAlarmSound() {
        runCatching { currentPlayer?.setVolume(FULL_ALARM_VOLUME, FULL_ALARM_VOLUME) }
            .onFailure { Log.w(TAG, "경고음 볼륨 복원 실패 (무시하고 계속 진행)", it) }
    }
}
