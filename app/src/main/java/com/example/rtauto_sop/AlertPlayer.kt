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

        NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
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
        val volume = AlertPrefs.getVolume(context)

        // Ringtone 대신 MediaPlayer를 쓰는 이유: 기기의 시스템 알람 볼륨을 건드리지 않고
        // 이 앱 안에서만 볼륨(0.0~1.0)을 조절하기 위해서다 (setVolume은 MediaPlayer에만 있음).
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(context, alarmUri)
            setVolume(volume, volume)
            setOnCompletionListener {
                it.release()
                onFinished()
            }
            setOnErrorListener { mp, _, _ ->
                mp.release()
                onFinished()
                true
            }
            prepare()
            start()
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

    private fun speakWith(context: Context, tts: TextToSpeech, body: String) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {}
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
        val volume = AlertPrefs.getVolume(context)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
        }
        tts.speak(body, TextToSpeech.QUEUE_FLUSH, params, "sop_alert")
    }
}
