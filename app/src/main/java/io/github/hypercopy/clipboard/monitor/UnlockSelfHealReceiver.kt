package io.github.hypercopy.clipboard.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.hypercopy.HyperLog

/**
 * v1.118 解锁自检接收器（借鉴无障碍管理器 USER_PRESENT 机制）：
 * 解锁时是系统清理后台的高发时刻（MIUI/HyperOS 会在锁屏期回收后台服务/重置权限），
 * 收到解锁广播立即触发 KeepAliveMonitor 自检（standby 保持 + 无障碍绑定 + appops），
 * 比 60s 巡检更快恢复服务。
 */
class UnlockSelfHealReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_USER_PRESENT) return
        HyperLog.d(TAG, "解锁广播收到, 立即自检服务")
        Thread {
            runCatching { KeepAliveMonitor.checkAndHeal(context.applicationContext) }
                .onFailure { HyperLog.d(TAG, "解锁自检异常: ${it.message}") }
        }.start()
    }

    private companion object {
        const val TAG = "解锁自愈"
    }
}