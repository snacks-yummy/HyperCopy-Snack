package io.github.hypercopy.clipboard.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.hypercopy.HyperLog

/**
 * v1.119 开机自启接收器（对齐 Shizuku保活守护 BootReceiver）：
 * 设备重启后自动恢复前台服务（剪贴板监听 + 保活监控 + 无障碍自愈），
 * 无需用户手动打开 App——实现 HyperCopy 独立保活闭环。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return
        HyperLog.d(TAG, "开机广播收到, 自动恢复监听服务")
        // 延迟 3s 启动（等系统就绪），前台服务 START_STICKY 常驻
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching {
                ClipboardMonitorController.startForCurrentMode(context)
                HyperLog.d(TAG, "开机自启: 监听服务已启动")
            }.onFailure { HyperLog.d(TAG, "开机自启失败: ${it.message}") }
        }, 3000L)
    }

    private companion object {
        const val TAG = "HyperCopy-Boot"
    }
}