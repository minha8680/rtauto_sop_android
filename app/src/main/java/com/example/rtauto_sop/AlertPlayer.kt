package com.example.rtauto_sop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale

/**
 * 편차 경보 발생 시 알림 표시 + 경고음 재생 + 진동 + TTS 안내를 한 번에 처리한다.
 * 1차 MVP 범위: 도착 시 1회 재생. 전체화면 강제 경보 / 30초 재발송은 이후 단계에서 추가.
 */
object AlertPlayer {

    private const val CHANNEL_ID_RES_NAME = "alert_channel_id"
    private var ttsRef: TextToSpeech? = null

    // "확인" 버튼(알람 종료)이 지금 재생 중인 것을 즉시 멈출 수 있도록 붙잡아 둔다.
    private var currentPlayer: MediaPlayer? = null
    private var currentNotificationId: Int? = null

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
        vibrate(context)
        playAlarmSound(context) {
            speak(context, body)
        }
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

    private fun playAlarmSound(context: Context, onFinished: () -> Unit) {
        val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        // 소프트웨어 배율은 항상 100%로 두고(1f), 실제 크기는 기기의 "알람" 스트림 볼륨이 결정한다.
        // MainActivity의 슬라이더가 AudioManager.STREAM_ALARM을 직접 조절하므로,
        // 재생 도중 슬라이더를 움직이면 이 소리도 그 자리에서 바로 커지고 작아진다.
        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(context, alarmUri)
            setVolume(1f, 1f)
            setOnCompletionListener {
                if (currentPlayer === it) currentPlayer = null
                it.release()
                onFinished()
            }
            setOnErrorListener { mp, _, _ ->
                if (currentPlayer === mp) currentPlayer = null
                mp.release()
                onFinished()
                true
            }
            prepare()
        }
        currentPlayer = player
        player.start()
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
        currentPlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            player.release()
        }
        currentPlayer = null

        ttsRef?.stop()

        cancelVibration(context)

        currentNotificationId?.let { NotificationManagerCompat.from(context).cancel(it) }
        currentNotificationId = null
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
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {}
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
        // TTS도 알람 스트림에 실어 보낸다 - 실제 크기는 위 MediaPlayer와 마찬가지로
        // 기기의 "알람" 스트림 볼륨(슬라이더)이 실시간으로 결정한다.
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
        }
        tts.speak(body, TextToSpeech.QUEUE_FLUSH, params, "sop_alert")
    }
}
