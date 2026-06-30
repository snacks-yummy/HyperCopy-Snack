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
    // Bug⑤修复：内存环形缓冲（最近 3000 条），供日志 UI 展示
    // v1.139 扩充：300→3000（match-debug 每轮 37 条规则日志，300 条仅够 1-2 轮处理周期）
    private const val BUFFER_MAX = 3000
    private val buffer = ArrayDeque<LogEntry>()
    // v1.141.9 落盘日志：镜像到 /sdcard/Download/HyperCopy/hypercopy.log，便于外部/工具直接读取运行日志（免前台抓取）
    private const val LOG_DIR = "HyperCopy"
    private const val LOG_FILE = "hypercopy.log"
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024 // 2MB 超限重写
    @Volatile
    private var logFile: java.io.File? = null
    fun init(context: Context) {
        this.context = context.applicationContext
        initLogFile(context.applicationContext)
    }
    private fun initLogFile(ctx: Context) {
        runCatching {
            // 统一写到公共下载目录 Download/HyperCopy（shell 可直接读取，免前台抓取）
            val dir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                LOG_DIR,
            )
            dir.mkdirs()
            val f = File(dir, LOG_FILE)
            logFile = f
            // 启动时标注分隔
            f.appendText("\n========== app start ${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} ==========\n")
        }
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
        while (buffer.size > BUFFER_MAX) buffer.removeFirst()
        // v1.141.9 镜像落盘（后台写文件避免卡 UI；超 2MB 重写）。全量级别落盘，方便外部直接读运行日志。
        val file = logFile
        if (file != null) {
            appendToFile(file, entry.formatted())
        }
    }
    private fun appendToFile(file: java.io.File, line: String) {
        Thread {
            runCatching {
                if (file.length() > MAX_FILE_BYTES) {
                    file.writeText("")
                }
                java.io.FileWriter(file, true).use { it.write(line + "\n") }
            }
        }.start()
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
}
