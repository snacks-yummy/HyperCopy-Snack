package io.github.hypercopy
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** 结构化日志条目（v1.32）：级别/标签/消息/时间戳，供日志 UI 分级展示 */
data class LogEntry(
    val level: String,
    val tag: String,
    val message: String,
    val timestamp: Long,
) {
    fun formatted(): String = "${LogEntry.timeFormat.format(Date(timestamp))} $level/$tag: $message"
    companion object {
        private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }
}

object HyperLog {
    @Volatile
    private var context: Context? = null
    // Bug⑤修复：内存环形缓冲（供日志 UI 展示）
    // v1.139 扩充：300→3000（match-debug 每轮 37 条规则日志，300 条仅够 1-2 轮处理周期）
    // v1.141.39 改为设置页可配置（Config.KEY_LOG_BUFFER_MAX，档位 1000~50000，默认 10000）：
    //   磁盘 2MB 容量≈6000-10000 行，内存窗口需 ≥ 磁盘容量，否则 UI 日志页出现"磁盘有但 UI 看不到"的断裂。
    //   实测空闲 ~20 行/分，10000 条≈8 小时窗口，每条 LogEntry≈200B，10000 条≈2MB 内存可接受。
    private val buffer = ArrayDeque<LogEntry>()
    // v1.141.9 落盘日志：镜像到 Via 文件夹/HyperCopy_logs/hypercopy.log，便于外部/工具直接读取运行日志（免前台抓取）
    // v1.141.56 从 Download/HyperCopy 迁移到 Via 绑定文件夹（工作区根目录）下，统一日志目录
    private const val LOG_DIR = "HyperCopy_logs"
    private const val LOG_FILE = "hypercopy.log"
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024 // 2MB 超限轮转归档（v1.141.38 前为直接清空）
    private const val MAX_LINE_CHARS = 2000             // v1.141.38 单行日志最大字符（防超长文本撑爆文件）
    private const val MAX_BACKUP_FILES = 1              // v1.141.38 保留最近归档日志份数
    // v1.141.38 单线程串行写盘：替代每行 new Thread，避免线程爆炸/乱序
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "HyperLog-Writer").apply { isDaemon = true }
    }
    @Volatile
    private var logFile: java.io.File? = null

    /** v1.141.57 双写兜底：Via 文件夹主写 + Download 镜像，保证日志永不丢失 */
    @Volatile
    private var logFiles: List<java.io.File> = emptyList()

    fun init(context: Context) {
        this.context = context.applicationContext
        initLogFile(context.applicationContext)
    }
    private fun initLogFile(ctx: Context) {
        runCatching {
            val targets = mutableListOf<java.io.File>()
            // v1.141.56 主写 Via 绑定文件夹内 HyperCopy_logs（用户可直接读取，测试便利）
            val viaDir = File(File("/storage/emulated/0/Via/复制直达项目二改"), LOG_DIR)
            val viaWritable = runCatching {
                viaDir.mkdirs()
                val primary = File(viaDir, LOG_FILE)
                resolveWritableLogFile(viaDir, primary)?.let { targets += it; true } ?: false
            }.getOrDefault(false)
            // v1.142.1e 降级提示：重装后 App uid 变化 → Via 目录（旧 uid 属主）无写权限
            // （MANAGE_EXTERNAL_STORAGE 重装后丢失），日志会静默切到 Download——用户读旧 Via 日志误判。
            // 把降级事实写进最终可写日志，任何日志文件都能看到提示。
            if (!viaWritable) {
                val hint = "\n[HyperLog] ⚠️ Via 日志目录不可写（重装后可能丢失\"所有文件访问\"权限，uid 变化）→ 日志已降级写入 Download/HyperCopy_logs/，请重新授权或以此为最新日志源\n"
                runCatching {
                    val dlDir0 = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), LOG_DIR)
                    dlDir0.mkdirs()
                    java.io.FileWriter(File(dlDir0, LOG_FILE), true).use { it.write(hint) }
                }
            }
            // v1.141.57 镜像兜底：Download 公共目录（App 天然可写，不依赖"所有文件访问"授权）
            val dlDir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                LOG_DIR,
            )
            runCatching {
                dlDir.mkdirs()
                val primary = File(dlDir, LOG_FILE)
                resolveWritableLogFile(dlDir, primary)?.let { targets += it }
            }
            logFiles = targets.distinct()
            logFile = logFiles.firstOrNull()
            // 启动时标注分隔
            val header = "\n========== app start ${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} ==========\n"
            logFiles.forEach { f -> runCatching { java.io.FileWriter(f, true).use { it.write(header) } } }
        }
    }

    /** v1.141.31 探测并解析一个可写的日志文件：优先主文件，不可写则递增序号选新文件 */
    private fun resolveWritableLogFile(dir: File, primary: File): java.io.File? {
        // 主文件不存在 → 新进程创建（属主即本 uid，可写）
        if (!primary.exists()) return primary
        // 主文件存在 → 探测是否可写（旧 uid 残留文件对本 uid 只读/无写权限）
        if (probeWritable(primary)) return primary
        // 主文件不可写 → 切到递增序号文件 hypercopy_N.log（N 从 2 开始，属主为本 uid，可写）
        var idx = 2
        while (true) {
            val alt = File(dir, "hypercopy_$idx.log")
            if (!alt.exists()) return alt            // 新文件，直接可写
            if (probeWritable(alt)) return alt        // 旧序号文件可写则复用
            idx++
        }
    }

    /** 探测文件是否可写（尝试以 append 模式打开；失败=不可写） */
    private fun probeWritable(f: java.io.File): Boolean {
        return runCatching {
            java.io.FileWriter(f, true).use { it.write("") }
            true
        }.getOrDefault(false)
    }
    /** v1.104 全局追踪上下文：handle 生成 tid，扫描/确认/到达各阶段日志自动携带，logcat grep tid 一条线 */
    object TraceContext {
        @Volatile
        var current: String? = null
        fun new(): String {
            val tid = (0xFFF..0xFFFF).random().toString(16).uppercase()
            current = tid
            return tid
        }
    }
    /** v1.104 追加 i 级（logcat Info + 内存缓冲，供应用内日志界面完整显示） */
    fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
        append("I", tag, message)
    }
    fun i(tag: String, message: String, throwable: Throwable) {
        android.util.Log.i(tag, message, throwable)
        append("I", tag, message)
    }
    fun d(tag: String, message: String) {
        if (logLevel() >= Config.LOG_LEVEL_DEBUG) Log.d(tag, message)
        append("D", tag, message)
    }
    fun d(tag: String, message: String, throwable: Throwable) {
        if (logLevel() >= Config.LOG_LEVEL_DEBUG) Log.d(tag, message, throwable)
        append("D", tag, message)
    }
    fun w(tag: String, message: String) {
        if (logLevel() >= Config.LOG_LEVEL_BASIC) Log.w(tag, message)
        append("W", tag, message)
    }
    fun w(tag: String, message: String, throwable: Throwable) {
        if (logLevel() >= Config.LOG_LEVEL_BASIC) Log.w(tag, message, throwable)
        append("W", tag, message)
    }
    fun e(tag: String, message: String, throwable: Throwable) {
        if (logLevel() >= Config.LOG_LEVEL_BASIC) Log.e(tag, message, throwable)
        append("E", tag, message)
    }
    @Synchronized
    private fun append(level: String, tag: String, message: String) {
        val entry = LogEntry(level, tag, message, System.currentTimeMillis())
        buffer.addLast(entry)
        while (buffer.size > bufferMax()) buffer.removeFirst()
        // v1.141.9 镜像落盘（后台写文件避免卡 UI；超 2MB 轮转归档）。全量级别落盘，方便外部直接读运行日志。
        val files = logFiles
        if (files.isNotEmpty()) {
            // v1.141.43 修正 v1.141.38 过激过滤（"仅 debug 模式落盘 D"把关键链路 命中/提取/跳转耗时 全滤掉，
            // 日志只剩入口与保活巡检，失去时间线诊断价值）。改为精准降噪：
            // 仅跳过 [match-debug] 逐条匹配噪音（每轮 37 条规则 × 2 行），其余 D 级恢复全量落盘。
            if (level != "D" || !message.startsWith("[match-debug]")) {
                appendToFile(files, entry.formatted())
            }
        }
    }
    /** v1.141.38 单线程串行写盘：净化内容 + 超限轮转归档（不再直接清空丢历史） */
    /** v1.141.57 双写兜底：遍历写所有目标文件（Via + Download），各自 runCatching 互不影响 */
    private fun appendToFile(files: List<java.io.File>, line: String) {
        val clean = sanitize(line)
        writer.execute {
            files.forEach { f ->
                runCatching {
                    rotateIfNeeded(f)
                    java.io.FileWriter(f, true).use { it.write(clean + "\n") }
                }
            }
        }
    }
    /** v1.141.38 超限轮转：当前文件重命名为 .old.log 归档（保留最近 MAX_BACKUP_FILES 份），不直接清空 */
    private fun rotateIfNeeded(file: java.io.File) {
        if (file.length() > MAX_FILE_BYTES) {
            val backup = java.io.File(file.parentFile, file.nameWithoutExtension + ".old.log")
            if (backup.exists()) backup.delete()
            file.renameTo(backup)
        }
    }
    /** v1.141.38 净化日志行：丢弃 \x00 等控制字符、换行转空格、超长截断，保证外部 grep/tail 干净 */
    private fun sanitize(line: String): String {
        val sb = StringBuilder(line.length)
        for (c in line) {
            when {
                c == '\n' || c == '\r' || c == '\t' -> sb.append(' ')
                c.code < 32 -> Unit // 丢弃 \x00 等控制字符
                else -> sb.append(c)
            }
            if (sb.length >= MAX_LINE_CHARS) break
        }
        return if (line.length > sb.length) sb.toString() + "…" else sb.toString()
    }
    /** 最近的日志（新→旧），供日志 UI 结构化展示 */
    @Synchronized
    fun recentLogs(): List<LogEntry> = buffer.toList().reversed()
    /** 纯文本格式（新→旧），供复制/导出 */
    @Synchronized
    fun recentLogText(): String = buffer.toList().joinToString("\n") { it.formatted() }
    fun clearBuffer() {
        synchronized(this) { buffer.clear() }
    }

    private fun logLevel(): Int {
        val appContext = context ?: return Config.DEFAULT_LOG_LEVEL
        return appContext
            .getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(Config.KEY_LOG_LEVEL, Config.DEFAULT_LOG_LEVEL)
    }

    /** v1.141.39 日志缓冲条数：从设置读取，钳制在 MIN..MAX（防止越界写入破坏环形缓冲） */
    private fun bufferMax(): Int {
        val appContext = context ?: return Config.DEFAULT_LOG_BUFFER_MAX
        return appContext
            .getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(Config.KEY_LOG_BUFFER_MAX, Config.DEFAULT_LOG_BUFFER_MAX)
            .coerceIn(Config.MIN_LOG_BUFFER_MAX, Config.MAX_LOG_BUFFER_MAX)
    }
}
