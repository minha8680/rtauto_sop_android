package com.example.rtauto_sop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 개별 경보 알림에 붙는 `deleteIntent`(setDeleteIntent) 대상 — 관리자가 알림 트레이에서 알림을
 * 직접 스와이프로 지우거나(또는 탭해서 자동 취소되거나) 하면 시스템이 이 브로드캐스트를 보낸다.
 * [AlertPlayer.activeNotificationIds]는 원래 [AlertPlayer.stop]을 통해서만 줄어들었는데, 그건
 * "확인" 버튼이나 엣지 PC의 해제 메시지로만 알림이 없어지는 경우만 가정한 것이라 트레이에서
 * 직접 지운 경우는 반영이 안 됐다(요약 알림의 "N건" 카운트가 실제보다 많게 어긋남) — 그 간극을
 * 메우는 역할.
 */
class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId == -1) return
        AlertPlayer.onNotificationDismissed(context, notificationId)
    }

    companion object {
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
