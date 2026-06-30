package io.github.hypercopy.clipboard.monitor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.github.hypercopy.HyperLog
import io.github.hypercopy.clipboard.handling.ClipboardFloatingActivity
import io.github.hypercopy.clipboard.handling.ClipboardWriteGuard
import io.github.hypercopy.clipboard.privileged.IntentAmStartCommand
import java.util.UUID

object ClipboardFocusRequester {
    private const val TAG = "HyperCopy"
    private const val REQUEST_DEBOUNCE_MILLIS = 800L
    // v1.80 误触修复：抢焦点读取失败后冷却，防止 Denying 自我循环反复触发
    // （浮窗读取时系统会产生新的 Denying 日志 → 检测器误当新信号 → 无限循环）
    // 冷却 5s > 浮窗完整生命周期(~1s) + 轮询间隔(0.8s)，足以中断循环
    private const val FAIL_COOLDOWN_MILLIS = 5_000L
    private const val SHIZUKU_COMMAND_TIMEOUT_MILLIS = 3_000L
    private var lastRequestAt = 0L
    @Volatile
    private var lastFailedAt = 0L
    private var pendingToken: String? = null
    private var pendingClearToken: String? = null
    private var pendingClearCallback: ((Boolean) -> Unit)? = null
    // v1.139.1 键盘保护：启动浮动窗口前记录输入法活跃状态
    //（onCreate 时焦点已切换，imm.isActive 不可靠 → 必须在 request 时（焦点仍在用户 App）记录）
    @Volatile
    private var lastImeWasActive = false

    /** v1.80 记录抢焦点读取失败时间（由 FloatingActivity 读取彻底失败时调用） */
    fun markFailed() {
        lastFailedAt = System.currentTimeMillis()
    }

    fun request(context: Context, force: Boolean = false, retry: Int = 0) {
        val now = System.currentTimeMillis()
        // 失败冷却：读取失败说明剪贴板为空或环境异常，短期内不再重复抢焦点
        if (now - lastFailedAt < FAIL_COOLDOWN_MILLIS) {
            // v1.139.9 冷却期特权读兜底：浮动窗口失败后的冷却期内复制仍可处理
            // （不抢焦点，Shizuku 特权直接读剪贴板；dumpsys 不产生 Denying，无循环风险）
            HyperLog.d(TAG, "失败冷却期内, 直接 Shizuku 特权读兜底")
            val fallbackText = io.github.hypercopy.clipboard.privileged.ShizukuClipboardReader.read()
            if (!fallbackText.isNullOrBlank()) {
                HyperLog.d(TAG, "冷却期特权读成功, 直接处理 len=${fallbackText.length}")
                io.github.hypercopy.clipboard.handling.ClipboardTextHandler.handle(context.applicationContext, fallbackText, "")
            }
            return
        }
        if (now - lastRequestAt < REQUEST_DEBOUNCE_MILLIS) return
        // v1.139 剪贴板改写防抖窗口内跳过浮动窗口：改写回环（如验证码 520194 被二次嗅探）
        // 读取内容必然被 handle 防抖拦截，抢焦点只会打断用户输入（键盘关闭）
        if (ClipboardWriteGuard.isWithinWriteWindow()) {
            HyperLog.d(TAG, "剪贴板改写防抖窗口内, 跳过浮动窗口(防键盘打断)")
            return
        }
        // v1.140.11 无障碍已退出规则检测（仅保活），不再检查其运行状态；
        // force 参数保留兼容其他调用方
        lastRequestAt = now
        // v1.139.1 键盘保护：此刻焦点仍在用户 App（剪贴板来源），记录输入法状态供浮动窗口恢复键盘
        lastImeWasActive = isImeActive(context)
        HyperLog.d(TAG, "启动浮动窗口前键盘活跃=$lastImeWasActive")
        val token = UUID.randomUUID().toString()
        pendingToken = token
        val sourcePackage = foregroundPackageName(context)
        // v1.50 诊断日志：确认 FloatingActivity 是否启动成功
        if (ShizukuPermission.isGranted() && startByShizuku(context, token, sourcePackage, retry = retry)) {
            HyperLog.d(TAG, "浮动窗口已通过Shizuku启动")
            return
        }
        runCatching {
            context.startActivity(floatingActivityIntent(context, token, sourcePackage, retry))
            HyperLog.d(TAG, "floating activity started via app context")
        }.onFailure {
            HyperLog.d(TAG, "start clipboard floating activity failed (app context)", it)
            // 兜底启动失败：清除 token 防悬挂
            if (pendingToken == token) pendingToken = null
        }
    }

    /** v1.139.1 浮动窗口读取启动前记录的输入法活跃状态（onCreate 时调用） */
    fun wasImeActiveBeforeRequest(): Boolean = lastImeWasActive

    private fun isImeActive(context: Context): Boolean {
        return runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.isActive
        }.getOrDefault(false)
    }

    fun consumeToken(token: String?): Boolean {
        val expected = pendingToken ?: return false
        if (token != expected) return false
        pendingToken = null
        return true
    }

    fun requestClear(context: Context, onComplete: (Boolean) -> Unit): String? {
        if (!ShizukuPermission.isGranted()) return null
        val token = UUID.randomUUID().toString()
        pendingClearToken = token
        pendingClearCallback = onComplete
        return if (startByShizuku(context, token, "", ACTION_CLEAR_CLIPBOARD)) {
            token
        } else {
            cancelClearToken(token)
            null
        }
    }

    fun consumeClearToken(token: String?, cleared: Boolean): Boolean {
        val expected = pendingClearToken ?: return false
        if (token != expected) return false
        pendingClearToken = null
        pendingClearCallback?.invoke(cleared)
        pendingClearCallback = null
        return true
    }

    fun cancelClearToken(token: String?) {
        if (token == null || token != pendingClearToken) return
        pendingClearToken = null
        pendingClearCallback = null
    }

    fun isPendingClearToken(token: String?): Boolean {
        return token != null && token == pendingClearToken
    }

    private fun startByShizuku(context: Context, token: String, sourcePackage: String, action: String = ACTION_READ_CLIPBOARD, retry: Int = 0): Boolean {
        val component = ComponentName(context.packageName, ClipboardFloatingActivity::class.java.name).flattenToString()
        val commandParts = mutableListOf(
            "am",
            "start",
            "--user",
            "0",
            "-n",
            component,
            "--es",
            EXTRA_START_TOKEN,
            token,
            "--es",
            EXTRA_ACTION,
            action,
        )
        if (sourcePackage.isNotBlank()) {
            commandParts += listOf("--es", EXTRA_SOURCE_PACKAGE, sourcePackage)
        }
        if (retry > 0) {
            commandParts += listOf("--ei", EXTRA_RETRY, retry.toString())
        }
        commandParts += listOf(
            "-f",
            // v1.47 加 NO_ANIMATION：避免抢焦点时出现任务切换动画（用户感知为"跳回设置页"）
            (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION).toString(),
        )
        val command = commandParts.joinToString(" ") { IntentAmStartCommand.shellQuote(it) }
        return runCatching {
            HyperLog.d(TAG, "Shizuku 启动剪贴板浮动窗口")
            val process = ShizukuProcess.start(arrayOf("sh", "-c", command)) ?: return false
            if (!waitForExit(process)) {
                process.destroyForcibly()
                HyperLog.d(TAG, "Shizuku 启动剪贴板浮动窗口超时")
                return false
            }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                HyperLog.d(TAG, "Shizuku 启动剪贴板浮动窗口失败: ${output.take(300)}")
            }
            exitCode == 0
        }.getOrElse { throwable ->
            HyperLog.d(TAG, "Shizuku 启动剪贴板浮动窗口异常", throwable)
            false
        }
    }

    private fun floatingActivityIntent(context: Context, token: String, sourcePackage: String, retry: Int = 0): Intent {
        return Intent(context, ClipboardFloatingActivity::class.java)
            .putExtra(EXTRA_START_TOKEN, token)
            .putExtra(EXTRA_SOURCE_PACKAGE, sourcePackage)
            .putExtra(EXTRA_ACTION, ACTION_READ_CLIPBOARD)
            .putExtra(EXTRA_RETRY, retry)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    private fun foregroundPackageName(context: Context): String {
        if (!ShizukuPermission.isGranted()) return ""
        return runCatching {
            val process = ShizukuProcess.start(arrayOf("sh", "-c", "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"))
                ?: return ""
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!waitForExit(process)) process.destroyForcibly()
            Regex("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+").findAll(output)
                .map { it.value }
                .firstOrNull { it != context.packageName && !isLauncherPackage(it) } ?: ""
        }.getOrElse { throwable ->
            HyperLog.d(TAG, "read foreground package failed", throwable)
            ""
        }
    }

    /** v1.140.12 桌面/启动器包名过滤：跳转后回桌面复制, 来源不应显示桌面 */
    private fun isLauncherPackage(pkg: String): Boolean {
        return pkg in setOf(
            "com.miui.home", "com.android.launcher", "com.android.launcher3",
            "com.google.android.apps.nexuslauncher", "com.huawei.android.launcher",
            "com.oppo.launcher", "com.vivo.launcher", "com.bbk.launcher2",
            "com.meizu.flyme.launcher", "com.sec.android.app.launcher",
            "net.oneplus.launcher", "com.samsung.android.app.spage",
        )
    }
    private fun waitForExit(process: Process): Boolean {
        val deadline = System.currentTimeMillis() + SHIZUKU_COMMAND_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val exited = runCatching {
                process.exitValue()
                true
            }.getOrDefault(false)
            if (exited) return true
            runCatching { Thread.sleep(50L) }
        }
        return false
    }

    const val EXTRA_START_TOKEN = "io.github.hypercopy.extra.FLOATING_START_TOKEN"
    const val EXTRA_SOURCE_PACKAGE = "io.github.hypercopy.extra.CLIPBOARD_SOURCE_PACKAGE"
    const val EXTRA_ACTION = "io.github.hypercopy.extra.FLOATING_ACTION"
    // v1.140.2 浮动窗口重试标记
    const val EXTRA_RETRY = "io.github.hypercopy.extra.FLOATING_RETRY"
    const val ACTION_READ_CLIPBOARD = "read_clipboard"
    const val ACTION_CLEAR_CLIPBOARD = "clear_clipboard"
}
