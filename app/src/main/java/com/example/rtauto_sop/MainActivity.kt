package com.example.rtauto_sop

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {

    private lateinit var tokenText: TextView

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
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
            AlertPlayer.trigger(
                this,
                "테스트 경보",
                "이것은 테스트 경보입니다. 세정기 구역, 이인 일조 위반, 지금 발생."
            )
        }

        setupVolumeControl()
    }

    private fun setupVolumeControl() {
        val volumeLabel = findViewById<TextView>(R.id.volumeLabel)
        val volumeSeekBar = findViewById<SeekBar>(R.id.volumeSeekBar)

        val savedPercent = (AlertPrefs.getVolume(this) * 100).toInt()
        volumeSeekBar.progress = savedPercent
        volumeLabel.text = "경보음 크기: $savedPercent%"

        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                volumeLabel.text = "경보음 크기: $progress%"
                if (fromUser) {
                    AlertPrefs.setVolume(this@MainActivity, progress / 100f)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
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
