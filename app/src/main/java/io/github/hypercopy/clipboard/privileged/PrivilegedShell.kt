package io.github.hypercopy.clipboard.privileged

import io.github.hypercopy.Config
import io.github.hypercopy.HyperLog
import io.github.hypercopy.clipboard.monitor.ShizukuPermission
import io.github.hypercopy.clipboard.monitor.ShizukuProcess
import io.github.hypercopy.data.settings.SettingsRepository
import kotlin.concurrent.thread

data class ShellResult(val exitCode: Int, val output: String)

object PrivilegedShell {
    private const val TAG = "HyperCopy"
    private const val TIMEOUT_SECONDS = 8L

    fun run(settingsRepository: SettingsRepository, command: String, timeoutSeconds: Long = TIMEOUT_SECONDS): ShellResult {
        return runCatching {
            val useShizuku = settingsRepository.readClipboardMonitorMode() == Config.CLIPBOARD_MONITOR_MODE_SHIZUKU && ShizukuPermission.isGranted()
            HyperLog.d(TAG, "privileged shell ${if (useShizuku) "shizuku" else "root"}: ${command.redactedShellCommand()}")
            val process = if (useShizuku) {
                ShizukuProcess.start(arrayOf("sh", "-c", "$command 2>&1"))
            } else {
                ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            } ?: return ShellResult(-1, "no privileged shell")
            // v1.145.15 fix: 大输出命令（pm get-app-links 全量 86KB > 管道缓冲 64KB）写满管道后
            // 子进程阻塞在 write() 永不退出 → 先等退出再读输出必超时（系统分类无卡片根因）。
            // 改为独立读线程边消费边等退出；超时 destroy 后 join 兜底。
            val outputBuilder = StringBuilder()
            val readerThread = thread(name = "HyperCopyPrivShellRead") {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        val buffer = CharArray(8192)
                        while (true) {
                            val n = reader.read(buffer)
                            if (n <= 0) break
                            outputBuilder.append(buffer, 0, n)
                        }
                    }
                }.onFailure {
                    HyperLog.d(TAG, "privileged shell read failed: ${command.redactedShellCommand()}", it)
                }
            }
            val finished = waitForExit(process, timeoutSeconds)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                HyperLog.d(TAG, "privileged shell timeout: ${command.redactedShellCommand()}")
                return ShellResult(-1, "timeout")
            }
            readerThread.join(2_000L)
            val output = outputBuilder.toString()
            val exitCode = runCatching { process.exitValue() }
                .getOrElse { throwable ->
                    HyperLog.d(TAG, "privileged shell exitValue failed", throwable)
                    -1
                }
            if (exitCode != 0) HyperLog.d(TAG, "privileged shell failed code=$exitCode output=${output.take(300)}")
            ShellResult(exitCode, output)
        }.getOrElse { throwable ->
            HyperLog.d(TAG, "privileged shell exception: ${command.redactedShellCommand()}", throwable)
            ShellResult(-1, throwable.message.orEmpty())
        }
    }

    private fun String.redactedShellCommand(): String {
        return replace(Regex("(-d\\s+)'[^']*'")) { match -> "${match.groupValues[1]}'<redacted>'" }
            .replace(Regex("(--es\\s+'[^']+'\\s+)'[^']*'")) { match -> "${match.groupValues[1]}'<redacted>'" }
    }

    private fun waitForExit(process: Process, timeoutSeconds: Long = TIMEOUT_SECONDS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
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
}
