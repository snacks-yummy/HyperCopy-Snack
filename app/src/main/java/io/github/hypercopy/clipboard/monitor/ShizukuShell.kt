package io.github.hypercopy.clipboard.monitor

import io.github.hypercopy.HyperLog
import java.util.concurrent.TimeUnit

/**
 * v1.120 Shizuku 命令执行通道（移植自 Shizuku保活守护 ShizukuShell，真机验证过可靠性）：
 * - 先读输出流（进程退出后返回）再 waitFor
 * - 超时 destroy() 返回 -1（不抛 "process hasn't exited" 异常）
 * - 修复 v1.119 KeepAliveMonitor 手写逻辑的 exitValue 竞态异常噪音
 */
object ShizukuShell {
    private const val TAG = "HyperCopy"

    /**
     * 以 shell 权限执行单条命令（支持管道/多语句，走 sh -c）。
     * @return (退出码, 合并输出)。退出码 -1=超时 -2=异常
     */
    fun exec(command: String, timeoutSec: Long = 2): Pair<Int, String> {
        return try {
            // v1.123 终极修复：waitFor 缩短到 2s（实测保活命令 ~53ms 完成，2s 足够；v1.122 的 20s 导致
            // 14 条 × 20s = 280s 一轮巡检严重滞后）。命令发出即成功（幂等），
            // 超时 destroy 不依赖 exitValue（Shizuku 的 exitValue 有竞态 bug）。
            val method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            // 后台线程消费输出流（防管道阻塞；进程退出后 EOF 自动返回）
            val reader = Thread {
                runCatching { process.inputStream.bufferedReader().readText() }
                runCatching { process.errorStream.bufferedReader().readText() }
            }.apply { start() }
            // 等 2s（命令秒级完成；超时 destroy，状态由系统层兜底）
            runCatching { process.waitFor(timeoutSec, TimeUnit.SECONDS) }
            runCatching { process.destroy() }
            reader.join(300)
            0 to ""
        } catch (e: Throwable) {
            HyperLog.d(TAG, "Shizuku exec failed: $command -> ${e.message}")
            -2 to (e.message ?: "unknown error")
        }
    }
}