package io.github.hypercopy

import android.content.Context
import android.os.Build
import io.github.hypercopy.data.settings.SettingsRepository

/**
 * v1.105 结构化追踪日志（参考成熟项目风格：Ktor 请求块 / Gradle 任务树 / OkHttp 时序）：
 * - 块状输出：━ 分隔线 + │ 字段行 + 阶段耗时
 * - key=value 结构化字段，logcat 可 grep、程序可解析
 * - 处理周期（handle→jump→到达）与会话摘要（版本+设备+全部开关）统一格式
 */
object TraceLogger {
    private const val TAG = "HyperCopy"
    private val BAR = "━".repeat(48)
    private val BAR_SHORT = "─".repeat(36)

    /** 会话摘要块：版本 + 设备 + 监听模式 + 全部开关状态（App 启动/日志页打开时输出一次） */
    fun sessionSummary(context: Context): String = buildString {
        val settings = SettingsRepository(context.applicationContext)
        appendLine(BAR)
        appendLine("━━━ HyperCopy Session ━━━")
        line("版本", appVersion(context))
        line("设备", "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("模式", settings.readClipboardMonitorMode())
        line("开关", settings.dumpSettings())
        append(BAR)
    }

    /** 处理周期开始块 */
    fun begin(tid: String?, title: String): String = buildString {
        appendLine(BAR)
        appendLine("━━━ $title [${tid ?: "?"}] ━━━")
    }

    /** 字段行：│ 名称: 值 */
    fun line(key: String, value: String): String = "│ $key: $value"

    /** 周期结束块（带总耗时） */
    fun end(title: String, elapsedMs: Long): String = buildString {
        appendLine("└── $title +${elapsedMs}ms")
        append(BAR_SHORT)
    }

    private fun appVersion(context: Context): String = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName}(${pi.versionCode})"
    }.getOrDefault("?")

    /** 输出多行日志到 HyperLog（应用内可见）+ logcat */
    // v1.141.43 升级为 I 级：TraceLogger 块含"输入→命中→耗时"完整处理周期，是时间线分析的关键数据源。
    // v1.141.38 曾因 D 级落盘过滤导致其不落盘（日志只剩入口与保活巡检，无法分析识别/响应/跳转时间）。
    fun emit(block: String) {
        HyperLog.i(TAG, block)
    }
}
