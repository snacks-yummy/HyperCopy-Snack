package io.github.hypercopy.clipboard.monitor

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.hypercopy.HyperLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.114 持续保活监控（前台服务常驻）：
 * - 每 CHECK_INTERVAL_MS 检查一次无障碍真实绑定（AccessibilityManager 列表），
 *   设置开启但未真实绑定（服务被系统回收/自愈丢失）→ Shizuku 重启
 * - 检查 Shizuku 可用性：可用但未授权 → 尝试重新授权
 * - 独立于 AutoActivator（启动一次性 3 次重试），本监控长期循环，防服务被打断
 */
object KeepAliveMonitor {
    private const val TAG = "HyperCopy-KeepAlive"
    private const val CHECK_INTERVAL_MS = 60_000L
    /** v1.114 fix：首次检查延迟（App 启动后无障碍服务绑定需要时间，避免误判"未绑定"触发自愈闪断） */
    private const val FIRST_CHECK_DELAY_MS = 20_000L
    /** v1.114 fix：自愈冷静期——自愈后 30s 内不再次自愈（防重复移除/添加服务闪断） */
    private const val HEAL_COOLDOWN_MS = 30_000L
    /** v1.114 fix：10 分钟内最多自愈 3 次（防系统绑定慢导致的自愈风暴） */
    private const val HEAL_MAX_PER_10MIN = 3

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var checkRunnable: Runnable? = null

    /** v1.114 自愈次数统计（诊断用） */
    val healCount = AtomicInteger(0)
    @Volatile private var lastHealAt = 0L
    @Volatile private var healTimesIn10Min = ArrayDeque<Long>()

    fun start(context: Context) {
        if (running) return
        running = true
        val appContext = context.applicationContext
        HyperLog.d(TAG, "保活监控已启动, 每 ${CHECK_INTERVAL_MS / 1000}s 检查一次(首次 ${FIRST_CHECK_DELAY_MS / 1000}s 后)")
        checkRunnable = object : Runnable {
            override fun run() {
                if (!running) return
                // v1.114 fix: checkAndHeal 含 Thread.sleep，必须脱离主线程执行
                Thread {
                    runCatching { checkAndHeal(appContext) }
                        .onFailure { HyperLog.d(TAG, "保活检查异常: ${it.message}") }
                    handler.post { if (running) handler.postDelayed(this, CHECK_INTERVAL_MS) }
                }.start()
            }
        }
        handler.postDelayed(checkRunnable!!, FIRST_CHECK_DELAY_MS)
    }

    fun stop() {
        running = false
        checkRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable = null
        HyperLog.d(TAG, "保活监控已停止")
    }

    /** v1.114 自愈限流：30s 冷静期内不重复自愈；10 分钟内最多 3 次 */
    private fun canHeal(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastHealAt < HEAL_COOLDOWN_MS) return false
        // 清理 10 分钟前的记录
        while (healTimesIn10Min.isNotEmpty() && now - healTimesIn10Min.first() > 600_000L) {
            healTimesIn10Min.removeFirst()
        }
        if (healTimesIn10Min.size >= HEAL_MAX_PER_10MIN) return false
        return true
    }

    private fun recordHeal() {
        lastHealAt = System.currentTimeMillis()
        healTimesIn10Min.addLast(lastHealAt)
    }

    /** 单次检查：无障碍真实绑定 + Shizuku 状态 + 完整保活命令链（v1.119 独立保活，对齐 Shizuku保活守护） */
    fun checkAndHeal(context: Context) {
        // 0. v1.119 完整保活命令链：一次性重放全部命令（幂等）
        //    来源：Shizuku保活守护 KeepAliveManager（standardCommands + miuiCommands 全量）
        ensureFullKeepAlive(context)
        // 1. 无障碍真实绑定检测（比 isServiceEnabled 设置开关更准）
        val enabled = AccessibilityUtils.isServiceEnabled(context)
        val bound = isServiceReallyBound(context)
        if (enabled && !bound) {
            if (!canHeal()) {
                HyperLog.d(TAG, "无障碍未绑定但自愈限流中(冷静期/10分钟上限), 跳过本次自愈 绑定=$bound")
                return
            }
            recordHeal()
            HyperLog.d(TAG, "无障碍设置开启但未真实绑定(可能被系统回收), 自愈重启")
            AccessibilityUtils.setEnabledViaShizuku(context, false)
            Thread.sleep(800)
            AccessibilityUtils.setEnabledViaShizuku(context, true)
            healCount.incrementAndGet()
            Thread.sleep(1500)
            HyperLog.d(TAG, "自愈后绑定=${isServiceReallyBound(context)} 附着=${CainiaoAutoConfirm.isAttached()}")
        } else if (!enabled && ShizukuPermission.isGranted()) {
            // 设置被关闭（少见：用户手动关/系统重置）→ 自动重新开启（shizuku 已授权时静默恢复）
            if (!canHeal()) {
                HyperLog.d(TAG, "无障碍被关闭但自愈限流中, 跳过本次自愈")
                return
            }
            recordHeal()
            HyperLog.d(TAG, "无障碍设置被关闭, 自动重新开启")
            AccessibilityUtils.setEnabledViaShizuku(context, true)
            healCount.incrementAndGet()
        }
        // 2. Shizuku 保活：v1.119 增加主动重连（ShizukuKeepAlive 的 reconnect 能力）
        if (ShizukuPermission.isAvailable()) {
            if (!ShizukuPermission.isGranted()) {
                HyperLog.d(TAG, "Shizuku 可用但授权丢失, 尝试重新授权")
                ShizukuPermission.requestIfNeeded { }
            }
        } else {
            // Shizuku 服务不可用 → 尝试唤醒重连（部分 ROM 杀掉后 binder 可重新拉起）
            ShizukuPermission.waitForAvailable { available ->
                if (available) HyperLog.d(TAG, "Shizuku 重连成功")
            }
        }
    }

    /**
     * v1.119 完整保活命令链（独立保活，零外部依赖）：
     * 对齐 Shizuku保活守护 KeepAliveManager 三层命令集：
     * ① AOSP 通用层：Doze 白名单 + AppOps（后台/前台/通知/唤醒锁）+ standby 活跃
     * ② MIUI/HyperOS 增强层：自启动 + 悬浮窗 + 使用统计
     * ③ 数字 op 兜底：10024(后台弹出) + 10050(自启动) + 10051(关联启动)
     * 命令幂等，重复执行无副作用；每 60s 巡检一次防系统周期性重置。
     */
    // v1.143.4 可见性放宽：AutoActivator 启动自愈复用（覆盖安装后立即执行，不等 60s 巡检）
    internal fun ensureFullKeepAlive(context: Context) {
        if (!ShizukuPermission.isGranted()) return
        val pkg = context.packageName
        val commands = listOf(
            // ① AOSP 通用层
            "cmd deviceidle whitelist +$pkg",
            "cmd appops set $pkg RUN_IN_BACKGROUND allow",
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "cmd appops set $pkg START_FOREGROUND allow",
            "cmd appops set $pkg POST_NOTIFICATION allow",
            "cmd appops set $pkg WAKE_LOCK allow",
            "am set-inactive $pkg false",
            "am set-standby-bucket $pkg active",
            // ② MIUI/HyperOS 增强层
            // v1.144.2 删除 AUTO_START 命名版：HyperOS 3 实测 exit 255「Unknown operation string: AUTO_START」
            // （MIUI 私有命名 op 已移除），自启动由下方数字版 10050/10051 承担——此前被 ShizukuShell 假成功掩盖
            "cmd appops set $pkg GET_USAGE_STATS allow",
            // ③ 数字 op 兜底（MIUI 私有 code，部分版本命名 op 不识别）
            // v1.143.4 补 10021（后台弹出界面生效 op，v1.142.1 实测 10024 非全机型生效）——与一键配置对齐
            "appops set $pkg 10021 allow",
            "appops set $pkg 10024 allow",
            "appops set $pkg 10050 allow",
            "appops set $pkg 10051 allow",
            // v1.144.0 补齐（对齐一键配置全项，覆盖安装后系统重置的权限完整恢复）：
            "appops set $pkg 10045 allow", // 获取应用列表（MIUIOP）
            "appops set $pkg 10004 allow", // HyperOS3 设置相关 UI（桌面快捷方式等）
            "appops set $pkg 10008 allow", // 锁屏显示
            "appops set $pkg 10017 allow", // 动态壁纸等
            "appops set $pkg 10020 allow",
            "appops set $pkg 10053 allow",
            "appops set $pkg 10022 foreground", // 设置相关（前台）
            // HyperOS3 省电策略界面数据源双写（v1.142.1f 一键配置同款，幂等追加）
            "if ! settings get system miui_power_save_whitelist | grep -q $pkg; then settings put system miui_power_save_whitelist \"$(settings get system miui_power_save_whitelist),$pkg\"; fi",
            "if ! settings get secure miui_power_save_whitelist | grep -q $pkg; then settings put secure miui_power_save_whitelist \"$(settings get secure miui_power_save_whitelist),$pkg\"; fi",
        )
        var successCount = 0
        // v1.144.1 修复假成功：ShizukuShell.exec 无条件返回 exit 0（waitFor 结果被 runCatching 吞掉 +
        // 无论命令是否完成都 destroy → appops set 实际未生效，自愈 23/23 假成功实证）。
        // 改用 PrivilegedShell.run（v1.142.1d 一键配置同款通道：waitForExit try exitValue 轮询 +
        // 真实超时判定 + ShizukuProcess 启动），保证 appops 命令真实生效。
        val settingsRepo = io.github.hypercopy.data.settings.SettingsRepository(context)
        commands.forEach { cmd ->
            val result = io.github.hypercopy.clipboard.privileged.PrivilegedShell.run(settingsRepo, cmd)
            if (result.exitCode == 0) successCount++
        }
        HyperLog.d(TAG, "保活命令链巡检完成: $successCount/${commands.size} 成功")
        // v1.120 无条件 I 级日志：即使 logLevel=Basic（覆盖安装后重置）也能在 logcat 看到巡检结果
        HyperLog.i(TAG, "保活巡检: $successCount/${commands.size} 命令成功")
    }

    /** 真实绑定检测：优先 onServiceConnected 回调标志（isAttached），AccessibilityManager 列表作参考。
     * v1.117 修正：MIUI 上 getEnabledAccessibilityServiceList 对部分服务返回不完整（实测 attached=true 但列表缺失），
     * 误判会导致 KeepAliveMonitor 无意义自愈重启（与 AutoActivator 同理）。 */
    fun isServiceReallyBound(context: Context): Boolean {
        if (CainiaoAutoConfirm.isAttached()) return true
        return runCatching {
            val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
                as? android.view.accessibility.AccessibilityManager ?: return false
            val expected = ComponentName(context, ClipboardAccessibilityService::class.java).flattenToString()
            am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.id == expected }
        }.getOrDefault(false)
    }
}
