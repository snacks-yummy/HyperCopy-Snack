package io.github.hypercopy

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * v1.141.56 中文 UI 操作全量日志（用户测试辅助）。
 *
 * 与 HyperLog（技术诊断日志）分离，专门记录用户在软件内的**全部操作**，
 * 如点击 UI 选项、打开页面、测试正则、跳转、规则调整（含详情）等，
 * 全部使用**中文**描述，落盘到 Via 绑定文件夹内的 HyperCopy_logs 目录，便于外部直接读取。
 *
 * 【日志位置】/storage/emulated/0/Via/复制直达项目二改/HyperCopy_logs/ui_actions.log
 * 新增独立文件 ui_actions.log（仅中文操作），不混入技术日志，便于按操作语义检索。
 *
 * 线程安全：单线程串行写盘，避免乱序/线程爆炸。
 */
object UiActionLogger {
    private const val TAG = "UiAction"
    private val DIR_NAME = "HyperCopy_logs"
    private val FILE_NAME = "ui_actions.log"

    // 单线程串行写盘（与 HyperLog 一致，防乱序）
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "UiAction-Writer").apply { isDaemon = true }
    }

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var enabled = true

    fun init(context: Context) {
        runCatching {
            val ctx = context.applicationContext
            // Via 绑定文件夹工作区根目录
            val baseDir = File("/storage/emulated/0/Via/复制直达项目二改")
            // 组件实现（对 Via 绑定文件夹写入）+ 兜底降级到 Download/HyperCopy
            val dir = File(baseDir, DIR_NAME)
            dir.mkdirs()
            val primary = File(dir, FILE_NAME)
            logFile = resolveWritableLogFile(dir, primary)
            logFile?.appendText("\n========== UI 操作日志 ${fmt()} ==========\n")
        }
    }

    /** 探测可写日志文件：主文件不可写则递增序号，保证总能落盘 */
    private fun resolveWritableLogFile(dir: File, primary: File): File? {
        if (!primary.exists()) return primary
        if (probeWritable(primary)) return primary
        var idx = 2
        while (true) {
            val alt = File(dir, FILE_NAME.replace(".log", "_$idx.log"))
            if (!alt.exists()) return alt
            if (probeWritable(alt)) return alt
            idx++
        }
    }

    private fun probeWritable(f: File): Boolean =
        runCatching { java.io.FileWriter(f, true).use { it.write("") }; true }.getOrDefault(false)

    /** 进入某个页面 */
    fun page(pageName: String) = log("页面", "进入「$pageName」")

    /** 切换底部 Tab */
    fun tab(tabName: String) = log("Tab", "切到底部「$tabName」")

    /** 点击选项 / 开关 / 单选 */
    fun option(title: String, value: String) = log("选项", "点击「$title」→ $value")

    /** 打开/关闭开关 */
    fun switch(title: String, on: Boolean) = log("开关", "「$title」→ ${if (on) "开" else "关"}")

    /** 测试正则（含详情） */
    fun regexTest(pattern: String, testText: String, matched: Boolean?, extracted: String = "") {
        val m = when (matched) {
            true -> "命中"
            false -> "未命中"
            null -> "未匹配"
        }
        val detail = buildString {
            append("测试正则：pattern=[$pattern] 文本=[${truncate(testText, 80)}] 结果=$m")
            if (extracted.isNotBlank()) append(" 提取=[$extracted]")
        }
        log("正则", detail)
    }

    /** 测试跳转 */
    fun jumpTest(name: String, result: String, detail: String = "") {
        log("跳转", "测试跳转「$name」→ $result ${if (detail.isNotBlank()) " $detail" else ""}")
    }

    /** 规则变更（新增/修改/删除，含详情） */
    fun ruleChanged(op: String, ruleName: String, detail: String = "") {
        log("规则", "${op}规则「$ruleName」${if (detail.isNotBlank()) " $detail" else ""}")
    }

    /** 智能识别 */
    fun autoRecognize(text: String, result: String) {
        log("识别", "智能识别文本=[${truncate(text, 100)}] → $result")
    }

    /** 通用操作日志 */
    fun log(category: String, message: String) {
        if (!enabled) return
        val file = logFile
        if (file == null) return
        val line = "${fmt()} [${category}] $message"
        val clean = sanitize(line)
        writer.execute {
            runCatching {
                java.io.FileWriter(file, true).use { it.write(clean + "\n") }
            }
        }
    }

    private fun fmt(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())

    private fun truncate(s: String, max: Int): String =
        if (s.length > max) s.take(max) + "…" else s

    private fun sanitize(line: String): String {
        val sb = StringBuilder(line.length)
        for (c in line) {
            when {
                c == '\n' || c == '\r' || c == '\t' -> sb.append(' ')
                c.code < 32 -> Unit
                else -> sb.append(c)
            }
            if (sb.length >= 2000) break
        }
        return if (line.length > sb.length) sb.toString() + "…" else sb.toString()
    }
}
