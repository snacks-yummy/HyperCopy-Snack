package io.github.hypercopy.clipboard.monitor

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import io.github.hypercopy.HyperLog
import kotlin.concurrent.thread

/**
 * 无障碍保活通道（v1.140.11 职责收敛：仅保活，不参与任何剪贴板检测/规则跳转）。
 *
 * 规则检测完全走原版 Shizuku 链路（Probe 监听器 + logcat 流式嗅探 + 抢焦点读取），
 * 本服务仅负责：防止本软件被系统杀死（自动开启/被回收自愈）+ 菜鸟查件自动确认。
 */
class ClipboardAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        running.set(true)
        CainiaoAutoConfirm.attach(this)
        HyperLog.d(TAG, "accessibility keepalive connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 无障碍辅助功能（非规则检测）：菜鸟查件自动确认
        CainiaoAutoConfirm.onEvent(this, event)
    }

    override fun onInterrupt() {
        // 系统中断，忽略
    }

    override fun onDestroy() {
        running.set(false)
        super.onDestroy()
        // v1.116 修复自愈死循环：判据改为「系统设置里是否仍启用自己」——
        // 被动回收（设置仍含本组件）→ 自动自愈恢复；
        // 主动 disable（setEnabledViaShizuku(false) 已把本组件从设置移除）→ 跳过，天然断循环
        // （v1.114 版本无条件自愈 + disable 触发 onDestroy → 每 3.1s 无限重启，日志实锤）
        val stillEnabled = runCatching {
            AccessibilityUtils.isServiceEnabled(applicationContext)
        }.getOrDefault(false)
        if (!stillEnabled) {
            HyperLog.d(TAG, "无障碍服务销毁(设置已移除), 不触发自愈")
            return
        }
        // v1.116 静态限流（companion 跨实例共享；v1.114 实例字段在服务重建后归零导致限流失效）
        val now = System.currentTimeMillis()
        if (now - lastDestroyHealAt < DESTROY_HEAL_INTERVAL_MS) return
        if (destroyHealCount >= DESTROY_HEAL_MAX) return
        lastDestroyHealAt = now
        destroyHealCount++
        HyperLog.d(TAG, "无障碍服务被系统回收, 自动重启自愈(第${destroyHealCount}次)")
        thread(name = "HyperCopyA11yHeal") {
            Thread.sleep(3000)
            runCatching {
                // 二次确认：3s 后设置仍启用才重启（期间用户可能手动关闭）
                if (AccessibilityUtils.isServiceEnabled(applicationContext) && ShizukuPermission.isGranted()) {
                    AccessibilityUtils.setEnabledViaShizuku(applicationContext, false)
                    Thread.sleep(800)
                    AccessibilityUtils.setEnabledViaShizuku(applicationContext, true)
                }
            }
        }
    }

    companion object {
        private const val TAG = "HyperCopy"
        /** v1.116 自愈频率限制（静态跨实例共享；v1.114 实例字段在服务重建后归零导致限流失效）：
         * 10 分钟内最多 2 次，防系统杀→重启死循环 */
        private const val DESTROY_HEAL_INTERVAL_MS = 600_000L
        private const val DESTROY_HEAL_MAX = 2
        @Volatile private var lastDestroyHealAt = 0L
        @Volatile private var destroyHealCount = 0
        private val running = java.util.concurrent.atomic.AtomicBoolean(false)
        /** 无障碍服务是否已连接（保活状态） */
        fun isRunning(): Boolean = running.get()
    }
}
