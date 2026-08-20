package io.github.hypercopy.clipboard.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.hypercopy.HyperLog
import io.github.hypercopy.data.settings.SettingsRepository

/** v1.142.6o D6：前台服务常驻通知「暂停/恢复」按钮（通知栏一键开关监听，比快捷磁贴更直接） */
class ToggleMonitorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = SettingsRepository(context.applicationContext)
        val enabled = !settings.readMonitorEnabled()
        settings.persistMonitorEnabled(enabled)
        HyperLog.d("HyperCopy", "通知按钮切换 monitorEnabled=$enabled")
        // 刷新通知（按钮文案随状态切换）
        runCatching {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            nm.cancel(ClipboardMonitorForegroundService.NOTIFICATION_ID)
        }
    }
}
