package com.example.rtauto_sop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
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
 */
object AlertPlayer {

    private const val TAG = "AlertPlayer"
    private const val CHANNEL_ID_RES_NAME = "alert_channel_id"
    private const val TTS_REPEAT_INTERVAL_MS = 10_000L
    private const val FULL_ALARM_VOLUME = 1f

    // TTS가 말하는 동안 경고음을 이 크기까지 낮춘다(덕킹) — 완전히 죽이지는 않아서
    // "아직 경보가 울리고 있다"는 감각은 남기되, 음성 문구가 또렷하게 들리게 한다.
    private const val DUCKED_ALARM_VOLUME = 0.25f
    private var ttsRef: TextToSpeech? = null

    // "확인" 버튼(알람 종료)이 지금 재생 중인 것을 즉시 멈출 수 있도록 붙잡아 둔다.
    private var currentPlayer: MediaPlayer? = null
    private var currentNotificationId: Int? = null

    // "확인"을 누르기 전까지 TTS를 10초마다 반복 재생하는 예약. stop()이나 새 경보 도착 시 취소한다.
    private var repeatHandler: Handler? = null
    private var repeatRunnable: Runnable? = null

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
     */
    fun trigger(context: Context, title: String, body: String) {
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
        val openIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
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
            .build()

        val notificationId = System.currentTimeMillis().toInt()
        currentNotificationId = notificationId
        NotificationManagerCompat.from(context).notify(notificationId, notification)
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

        currentNotificationId?.let { NotificationManagerCompat.from(context).cancel(it) }
        currentNotificationId = null
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
