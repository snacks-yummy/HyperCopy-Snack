package io.github.hypercopy.clipboard.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.hypercopy.HyperLog
import io.github.hypercopy.R
import io.github.hypercopy.ui.framework.MainActivity

class ClipboardMonitorForegroundService : Service() {
    private var unlockReceiver: UnlockSelfHealReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        updateNotification(ShizukuClipboardMonitor.Status.Checking, startForeground = true)
        // v1.118 动态注册解锁自检（USER_PRESENT 在 Android 8+ 静态注册受限，前台服务动态注册更可靠）
        runCatching {
            val receiver = UnlockSelfHealReceiver()
            registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
            unlockReceiver = receiver
            HyperLog.d(TAG, "解锁自检接收器已注册")
        }.onFailure { HyperLog.d(TAG, "解锁自检接收器注册失败: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ShizukuClipboardMonitor.start(applicationContext) { status -> updateNotification(status) }
        // v1.141.26 短信自动监听：Shizuku 轮询检测外卖取件短信 → 自动跳转/通知（见 ShizukuSmsListener）
        ShizukuSmsListener.start(applicationContext)
        // v1.114 持续保活监控（无障碍真实绑定 + Shizuku 状态），防服务被打断
        KeepAliveMonitor.start(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() {
        // v1.118 注销解锁自检接收器
        runCatching { unlockReceiver?.let { unregisterReceiver(it) } }
        unlockReceiver = null
        ShizukuClipboardMonitor.stop()
        ShizukuSmsListener.stop()
        // v1.114 服务被系统销毁时停止保活循环（App 进程还活着时会由 START_STICKY 重建）
        KeepAliveMonitor.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(status: ShizukuClipboardMonitor.Status, startForeground: Boolean = false) {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_foreground_service_title))
            .setContentText(getString(status.textResource()))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (!startForeground) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ShizukuClipboardMonitor.Status.textResource(): Int = when (this) {
        ShizukuClipboardMonitor.Status.Checking -> R.string.notification_foreground_service_checking
        ShizukuClipboardMonitor.Status.RunningShizuku -> R.string.notification_foreground_service_running_shizuku
        ShizukuClipboardMonitor.Status.RunningReadLogs -> R.string.notification_foreground_service_running_read_logs
        ShizukuClipboardMonitor.Status.PermissionDenied -> R.string.notification_foreground_service_permission_denied
        ShizukuClipboardMonitor.Status.Unavailable -> R.string.notification_foreground_service_unavailable
        ShizukuClipboardMonitor.Status.Stopped -> R.string.notification_foreground_service_stopped
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_foreground_service_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_foreground_service_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val TAG = "HyperCopy"
        const val CHANNEL_ID = "clipboard_monitor_service"
        const val NOTIFICATION_ID = 1002
    }
}
