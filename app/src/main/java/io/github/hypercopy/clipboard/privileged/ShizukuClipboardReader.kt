package io.github.hypercopy.clipboard.privileged
import io.github.hypercopy.HyperLog
import io.github.hypercopy.clipboard.monitor.ShizukuPermission
import io.github.hypercopy.clipboard.monitor.ShizukuProcess
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * v1.139.3 Shizuku 特权读剪贴板兜底（v1.139.9 增强）。
 *
 * 背景：浮动窗口（透明 Activity）抢焦点读取在部分场景会失败/被打断
 * （版本升级无障碍重绑、HyperOS 焦点竞态/后台弹窗限制），导致复制后无处理。
 * 本类用 Shizuku 特权执行 dumpsys clipboard 直接读取——shell 权限不受前台焦点限制。
 *
 * v1.139.9 增强：newProcess 超时保护（实测可能卡 4 分钟）+ 失败原因日志 + 重试。
 */
object ShizukuClipboardReader {
    private const val TAG = "HyperCopy"
    private const val PROCESS_START_TIMEOUT_MILLIS = 3_000L
    private const val PROCESS_WAIT_TIMEOUT_MILLIS = 3_000L

    /** 执行 dumpsys clipboard 并解析出剪贴板文本；失败/无内容返回 null（带重试+失败日志） */
    fun read(): String? {
        if (!ShizukuPermission.isGranted()) {
            HyperLog.d(TAG, "Shizuku 读剪贴板: 权限未授予")
            return null
        }
        // v1.140.4 简化：单轮快速尝试（dumpsys → service_call），不重试不等待
        // （Android 13+ shell 读剪贴板全局限制，多轮尝试纯浪费时间）
        val t1 = readOnce(arrayOf("sh", "-c", "dumpsys clipboard"), "dumpsys", quiet = true)
        if (t1 != null) return t1
        return readOnce(arrayOf("sh", "-c", "service call clipboard 1"), "service_call", quiet = true)
    }

    private fun readOnce(cmd: Array<String>, channel: String, quiet: Boolean = false): String? {
        return runCatching {
            // Shizuku newProcess 反射可能卡住（实测 4 分钟）→ 独立线程 + 超时保护
            val processRef = AtomicReference<Process?>(null)
            val latch = CountDownLatch(1)
            val worker = kotlin.concurrent.thread(name = "HyperCopyShizukuClipRead") {
                try {
                    processRef.set(ShizukuProcess.start(cmd))
                } catch (t: Throwable) {
                    HyperLog.d(TAG, "Shizuku 读剪贴板: newProcess 异常 ${t.message}")
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(PROCESS_START_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                HyperLog.d(TAG, "Shizuku 读剪贴板: newProcess 超时(${PROCESS_START_TIMEOUT_MILLIS}ms), 放弃")
                runCatching { worker.interrupt() }
                return null
            }
            val process = processRef.get()
            if (process == null) {
                HyperLog.d(TAG, "Shizuku 读剪贴板: newProcess 返回 null")
                return null
            }
            // 等待 dumpsys 输出（最多 3s）
            val deadline = System.currentTimeMillis() + PROCESS_WAIT_TIMEOUT_MILLIS
            while (System.currentTimeMillis() < deadline) {
                val exited = runCatching { process.exitValue(); true }.getOrDefault(false)
                if (exited) break
                runCatching { Thread.sleep(50L) }
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (output.isBlank()) {
                HyperLog.d(TAG, "Shizuku 读剪贴板[$channel]: 输出为空")
                return null
            }
            val text = if (channel == "service_call") parseServiceCall(output) else parse(output)
            if (text == null) {
                if (!quiet) HyperLog.d(TAG, "Shizuku 读剪贴板[$channel]: 解析失败 output=${output.take(120).replace('\n', ' ')}")
            } else if (!quiet) {
                HyperLog.d(TAG, "Shizuku 读剪贴板[$channel]成功 len=${text.length}")
            }
            text
        }.getOrElse { throwable ->
            HyperLog.d(TAG, "Shizuku 读剪贴板[$channel]异常: ${throwable.message}")
            null
        }
    }

    /** 解析 service call clipboard 1 的 Parcel 十六进制输出 → 提取剪贴板文本（多编码多候选） */
    private fun parseServiceCall(output: String): String? {
        if (output.isBlank()) return null
        // 提取所有十六进制字节（跳过 0x 地址和 ASCII 显示列）
        val hex = Regex("0x[0-9a-f]+: ([0-9a-f ]+)").findAll(output)
            .joinToString("") { it.groupValues[1].replace(" ", "") }
        if (hex.length < 16) return null
        val bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        // 多编码候选（Parcel writeString 为 UTF-8；部分 ROM 显示 UTF-16）
        val candidates = mutableListOf<String>()
        runCatching { String(bytes, Charsets.UTF_8).let { extractReadable(it) }?.let { candidates += it } }
        runCatching { String(bytes, Charsets.UTF_16LE).let { extractReadable(it) }?.let { candidates += it } }
        runCatching { String(bytes, Charsets.UTF_16BE).let { extractReadable(it) }?.let { candidates += it } }
        if (candidates.isEmpty()) return null
        // 优先含 :// 的（URL），否则取最长
        return candidates.firstOrNull { it.contains("://") }
            ?: candidates.maxByOrNull { it.length }
    }

    /** 从解码字符串中提取最长可读文本段（URL/中文/常用符号） */
    private fun extractReadable(decoded: String): String? {
                val segments = Regex("""[\x20-\x7e\u4e00-\u9fff:/?#[\[\]@!$&'()*+,;=._~-]{6,}""").findAll(decoded)
            .map { it.value }.toList()
        return segments.maxByOrNull { it.length }?.takeIf { it.length >= 6 }
    }

    private fun parse(output: String): String? {
        if (output.isBlank()) return null
        // 常见格式: ClipData { text/plain {T:https://...} }；多级兜底（不同 ROM 输出格式不同）
        val clipText = Regex("""text/[\w.+-]+\s*\{[^}]*?T:([^}]+)\}""").find(output)
            ?: Regex("""ClipData\s*\{[^}]*?T:([^}]+)\}""").find(output)
            ?: Regex("""T:([^\s}]+)""").find(output)
            ?: Regex("""text/plain[^
]*?([a-z]+://[^\s}]+)""").find(output)
        return clipText?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }
}
