package io.github.hypercopy.clipboard.handling

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.hypercopy.Config
import io.github.hypercopy.HyperLog
import io.github.hypercopy.R
import io.github.hypercopy.ui.framework.MainActivity

/**
 * v1.50 剪贴板读取失败兜底通知器（免 root 方案）：
 * HyperOS 拒绝后台读取（无障碍直读/抢焦点均被 Denying）时，
 * 弹"点击处理"通知 → 用户点击 → MainActivity 前台读取成功（焦点规则允许）→ 跳转。
 */
object ClipboardFallbackNotifier {
    private const val TAG = "兜底通知"
    private const val CHANNEL_ID = "hypercopy_clipboard_fallback"
    private const val NOTIFICATION_ID = 2002
    /** 通知节流：30s 内不重复打扰（复制内容读不到，无法按内容去重） */
    private const val THROTTLE_MILLIS = 30_000L
    private var lastNotifyAt = 0L

    fun notify(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < THROTTLE_MILLIS) {
            HyperLog.d(TAG, "fallback notification throttled")
            return
        }
        lastNotifyAt = now
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            HyperLog.d(TAG, "fallback notification permission missing")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.notification_channel_fallback_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val intent = Intent(appContext, MainActivity::class.java)
            .putExtra(Config.EXTRA_PROCESS_CLIPBOARD, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(appContext.getString(R.string.fallback_notification_title))
            .setContentText(appContext.getString(R.string.fallback_notification_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification) }
            .onFailure { HyperLog.d(TAG, "fallback notification failed", it) }
        HyperLog.d(TAG, "fallback notification posted")
    }
}
