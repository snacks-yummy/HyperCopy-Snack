package io.github.hypercopy.clipboard.monitor
import io.github.hypercopy.HyperLog
import java.io.BufferedReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
class ShizukuLogcatClipboardDetector(
    private val packageName: String,
    private val processStarter: (Array<String>) -> Process?,
    private val onRunningChanged: (Boolean) -> Unit,
    private val onClipboardChanged: () -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var process: Process? = null
    private var worker: Thread? = null
    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread(::readLoop, "HyperCopy-ShizukuLogcat").also { it.start() }
    }
    fun stop() {
        running.set(false)
        process?.destroy()
        process = null
        worker = null
    }
    private fun readLoop() {
        try {
            val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            HyperLog.d(TAG, "start Shizuku logcat clipboard detector")
            // v1.140.11 原版流式监听：logcat -T since ClipboardService:E *:S 持续读流（禁止 -d/-t）。
            // v1.140.6 超时保护：进程启动 5s 拿不到结果放弃，防 processStarter(Shizuku Binder) 卡死。
            val future = java.util.concurrent.FutureTask {
                processStarter(arrayOf("logcat", "-T", since, "ClipboardService:E", "*:S"))
            }
            Thread(future, "HyperCopyLogcatStart").start()
            val proc = runCatching { future.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
            if (proc == null) {
                // 启动失败（processStarter 返回 null / Binder 超时）：结束本次，不自动重启（v1.141.55 行为）
                running.set(false)
                onRunningChanged(false)
                return
            }
            process = proc
            onRunningChanged(true)
            // v1.140.7 stderr 诊断：命令错误（权限/参数）输出到 stderr，打日志便于排查
            Thread {
                runCatching {
                    val err = proc.errorStream.bufferedReader().readLine()
                    if (!err.isNullOrBlank()) HyperLog.d(TAG, "logcat stderr: $err")
                }
            }.start()
            proc.inputStream.bufferedReader().use(::readLines)
        } catch (throwable: Throwable) {
            if (running.get()) HyperLog.d(TAG, "Shizuku logcat detector failed", throwable)
        } finally {
            // 清理
            process = null
        }
        // 读循环结束（readLine 返回 null=logcat 进程结束 / 异常 / stop）：直接停止，不自动重启（v1.141.55 行为）
        if (running.get()) {
            running.set(false)
            onRunningChanged(false)
        }
    }
    private fun readLines(reader: BufferedReader) {
        while (running.get()) {
            val line = reader.readLine() ?: break
            // 原版匹配：日志含本包名 + Clipboard（如 "Denying clipboard access to io.github.hypercopy"）
            if (line.contains(packageName) && line.contains("Clipboard", ignoreCase = true)) {
                HyperLog.d(TAG, "Shizuku detected clipboard log: $line")
                onClipboardChanged()
            }
        }
    }
    private companion object {
        const val TAG = "HyperCopy"
    }
}