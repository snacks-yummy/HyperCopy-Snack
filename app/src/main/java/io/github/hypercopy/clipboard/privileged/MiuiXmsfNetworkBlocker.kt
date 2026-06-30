package io.github.hypercopy.clipboard.privileged

import android.content.Context
import io.github.hypercopy.HyperLog
import io.github.hypercopy.clipboard.monitor.ShizukuPermission
import io.github.hypercopy.clipboard.monitor.ShizukuProcess

/**
 * v1.141.14 回退到"一次性 session 模式"（稳定优先）。
 *
 * 此前 v1.141.12~14 尝试用单个常驻 app_process（daemon 模式）复用会话以消除 1.3s 冷启动，
 * 但经真机多次验证：Shizuku newProcess 起的常驻进程其 stdout READY 读取不可靠，
 * 导致 daemon block failed / start failed，且残留多个僵尸 app_process。稳定性远差于一次性模式。
 *
 * 因此回退为 v1.141.11 的一次性 session 模式（09:48 曾 100% 稳定弹岛）：
 * 每次发送新建一个 app_process 断 xmsf 网络，发通知后恢复。约 1.3s 冷启动延迟可接受，
 * 因为文本类通知（取件码/验证码）实际不会高频到达。
 */
object MiuiXmsfNetworkBlocker {
    private const val TAG = "HyperCopy"
    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"
    private const val BINDER_COMMAND_CLASS = "io.github.hypercopy.clipboard.privileged.MiuiXmsfFirewallBinderCommand"
    private const val BLOCK_MILLIS = 80L
    private const val TIMEOUT_MILLIS = 1_500L

    /**
     * 临时断 xmsf 网络发送通知，返回后再恢复。
     * 一次性 session：新建 app_process → 断网 → READY → 发通知 → 恢复退出。
     */
    fun notifyWithTemporaryBlock(context: Context, notify: () -> Unit) {
        if (!ShizukuPermission.isGranted()) {
            notify()
            return
        }

        val uid = runCatching { context.packageManager.getPackageUid(XMSF_PACKAGE, 0) }.getOrNull()
        if (uid == null) {
            notify()
            return
        }

        var notified = false
        try {
            withSingleBlockSession(context, uid) {
                notify()
                notified = true
                Thread.sleep(BLOCK_MILLIS)
            }
        } catch (throwable: Throwable) {
            HyperLog.d(TAG, "xmsf temporary network block failed", throwable)
            if (!notified) notify()
        }
    }

    /** 一次性 session：断网 → READY → 执行通知 → 恢复退出。 */
    private fun withSingleBlockSession(context: Context, uid: Int, onBlocked: () -> Unit) {
        val process = startBinderProcess(context, uid)
            ?: throw IllegalStateException("Shizuku app_process unavailable")
        val reader = process.inputStream.bufferedReader()
        var ready = false
        var restoreRequested = false
        try {
            ready = waitForReady(process, reader)
            if (!ready) error("xmsf block session did not become ready")
            onBlocked()
            process.outputStream.write('\n'.code)
            process.outputStream.flush()
            restoreRequested = true
            if (!waitForExit(process)) {
                runCatching { process.destroyForcibly() }
                error("timeout")
            }
            val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
            if (exitCode != 0) error("exit=$exitCode")
        } finally {
            if (ready && !restoreRequested) {
                runCatching {
                    process.outputStream.write('\n'.code)
                    process.outputStream.flush()
                    waitForExit(process)
                }
            }
            runCatching { reader.close() }
            runCatching { process.outputStream.close() }
            if (runCatching { process.exitValue(); false }.getOrDefault(true)) {
                runCatching { process.destroyForcibly() }
            }
        }
    }

    private fun startBinderProcess(context: Context, uid: Int): Process? {
        return ShizukuProcess.start(
            arrayOf(
                "app_process",
                "-Djava.class.path=${context.applicationInfo.sourceDir}",
                "/system/bin",
                BINDER_COMMAND_CLASS,
                uid.toString(),
                "session",
            ),
        )
    }

    private fun waitForReady(process: Process, reader: java.io.BufferedReader): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val exited = runCatching {
                process.exitValue()
                true
            }.getOrDefault(false)
            if (exited) return false

            if (reader.ready() && reader.readLine() == "READY") return true
            runCatching { Thread.sleep(20L) }
        }
        return false
    }

    private fun waitForExit(process: Process): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { process.exitValue(); true }.getOrDefault(false)) return true
            runCatching { Thread.sleep(20L) }
        }
        return false
    }
}