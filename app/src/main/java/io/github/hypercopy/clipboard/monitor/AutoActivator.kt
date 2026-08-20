package io.github.hypercopy.clipboard.monitor
import android.content.Context
import io.github.hypercopy.HyperLog
import io.github.hypercopy.data.settings.SettingsRepository
import io.github.hypercopy.clipboard.privileged.ShizukuClipboardReader
import kotlin.concurrent.thread

/**
 * 启动自动激活（v1.38）：
 * - Shizuku 服务可用但未授权 → 自动发起授权请求（系统确认框，无需进设置页）
 * - Shizuku 已授权但无障碍未开启 → 静默自动开启（settings 写 enabled_accessibility_services）
 * 全部后台线程执行，不阻塞 UI；幂等（已就绪则跳过）。
 */
object AutoActivator {
    private const val TAG = "HyperCopy-AutoActivate"

    fun activate(context: Context) {
        val appContext = context.applicationContext
        val settingsRepository = SettingsRepository(appContext)
        if (!settingsRepository.readAutoActivate()) {
            HyperLog.d(TAG, "auto activate disabled")
            return
        }
        thread(name = "HyperCopyAutoActivate") {
            runCatching {
                if (ShizukuPermission.isAvailable()) {
                    if (!ShizukuPermission.isGranted()) {
                        // 发起授权（系统弹窗，用户确认一次；之后自动继续开启无障碍）
                        HyperLog.d(TAG, "auto request shizuku permission")
                        ShizukuPermission.requestIfNeeded { granted ->
                            if (granted) {
                                HyperLog.d(TAG, "shizuku granted, auto enable accessibility")
                                enableAccessibilityIfNeeded(appContext)
                            }
                        }
                    } else {
                        // 已授权：静默开启无障碍（兜底通道）
                        enableAccessibilityIfNeeded(appContext)
                        // v1.143.4 覆盖安装权限自愈：启动立即执行完整保活命令链（不等 60s 巡检），
                        // 修复系统重置的 appops（后台弹出 10021/自启动/前台服务/通知）+ 省电白名单；
                        // 命令幂等无副作用（本线程已为后台线程，同步 shell 不阻塞 UI）
                        KeepAliveMonitor.ensureFullKeepAlive(appContext)
                    }
                } else {
                    HyperLog.d(TAG, "shizuku not available, skip auto activate")
                }
                // v1.97 版本感知：App 升级后强制重启无障碍服务（覆盖安装不杀进程，旧代码一直跑）
                val versionCode = runCatching {
                    appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode
                }.getOrDefault(0L).toInt()
                val lastVersion = settingsRepository.readLastActiveVersionCode()
                if (lastVersion != versionCode && AccessibilityUtils.isServiceEnabled(appContext)) {
                    HyperLog.d(TAG, "version changed $lastVersion -> $versionCode, force rebind accessibility")
                    // v1.139.5 延迟重绑：版本升级瞬间用户可能正在复制（浮动窗口读取中），
                    // 立即重绑会打断剪贴板处理导致"复制无反应"；延迟 5s（15s 太久=38s 无保护窗口）
                    HyperLog.d(TAG, "无障碍重绑延迟 5s（避开复制处理活跃期）")
                    Thread.sleep(5000)
                    AccessibilityUtils.setEnabledViaShizuku(appContext, false)
                    Thread.sleep(800)
                    AccessibilityUtils.setEnabledViaShizuku(appContext, true)
                    settingsRepository.persistLastActiveVersionCode(versionCode)
                    Thread.sleep(3000)
                    HyperLog.d(TAG, "force rebind done, attached=${CainiaoAutoConfirm.isAttached()}")
                    // v1.139.5 重绑补偿：窗口期剪贴板事件可能丢失，用 Shizuku 特权读补偿
                    // （不依赖前台焦点；与上次处理内容相同则跳过，避免重复处理）
                    val pending = ShizukuClipboardReader.read()
                    if (!pending.isNullOrBlank() && pending != io.github.hypercopy.clipboard.handling.ClipboardTextHandler.lastProcessedText) {
                        HyperLog.d(TAG, "重绑补偿: 检测到窗口期未处理剪贴板内容 len=${pending.length}")
                        io.github.hypercopy.clipboard.handling.ClipboardTextHandler.handle(appContext, pending, "")
                    }
                }
                // v1.97 self-heal: 真实绑定检测（AccessibilityManager 列表）+ 循环重试
                var healAttempt = 0
                while (healAttempt < 3 && !isServiceReallyBound(appContext)) {
                    Thread.sleep(5000)
                    if (AccessibilityUtils.isServiceEnabled(appContext)) {
                        HyperLog.d(TAG, "self-heal attempt ${healAttempt + 1}: rebinding")
                        AccessibilityUtils.setEnabledViaShizuku(appContext, false)
                        Thread.sleep(800)
                        AccessibilityUtils.setEnabledViaShizuku(appContext, true)
                        Thread.sleep(3000)
                        HyperLog.d(TAG, "self-heal done, bound=${isServiceReallyBound(appContext)} attached=${CainiaoAutoConfirm.isAttached()}")
                    }
                    healAttempt++
                }
            }.onFailure { throwable ->
                HyperLog.d(TAG, "auto activate failed", throwable)
            }
        }
    }

    /** v1.97 真实绑定检测：AccessibilityManager 已启用服务列表（比 isServiceEnabled 设置开关更准）
     * v1.117 修正：MIUI 上 getEnabledAccessibilityServiceList 对部分服务返回不完整（实测 attached=true 但列表缺失），
     * 导致误判"未绑定"→ 启动后 AutoActivator 强制重启服务 3 次 + KeepAlive 再重启 1 次（无意义重启）。
     * 改为优先用 onServiceConnected 回调标志（isAttached），列表仅作参考。
     */
    private fun isServiceReallyBound(context: Context): Boolean {
        if (CainiaoAutoConfirm.isAttached()) return true
        return runCatching {
            val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
                ?: return false
            val expected = android.content.ComponentName(context, ClipboardAccessibilityService::class.java).flattenToString()
            am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.id == expected }
        }.getOrDefault(false)
    }

    private fun enableAccessibilityIfNeeded(context: Context) {
        if (AccessibilityUtils.isServiceEnabled(context)) {
            HyperLog.d(TAG, "accessibility already enabled")
            return
        }
        val ok = AccessibilityUtils.setEnabledViaShizuku(context, true)
        HyperLog.d(TAG, "auto enable accessibility result=$ok")
    }
}