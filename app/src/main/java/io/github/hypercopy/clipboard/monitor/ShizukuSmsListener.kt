package io.github.hypercopy.clipboard.monitor

import android.content.Context
import io.github.hypercopy.HyperLog
import io.github.hypercopy.clipboard.handling.ClipboardTextHandler
import io.github.hypercopy.data.rules.RuleRepository
import io.github.hypercopy.data.rules.findRule

/**
 * v1.141.26 短信自动监听：收到短信时自动识别并处理（验证码/取货码/取件码/外卖等）。
 *
 * 背景：HyperCopy 是剪贴板嗅探模块，短信不会自动进剪贴板，导致需手动复制才能触发规则。
 * 本监听用 Shizuku 轮询读系统短信库（content://sms，shell 权限可读，实测可行），
 * 检测到新短信 → 预检是否有启用规则命中 → 命中则走完整规则引擎（ClipboardTextHandler.handle），
 * 未命中则静默忽略（不打扰、不污染剪贴板）。
 *
 * 安全设计：
 *  - 不写入用户剪贴板（避免覆盖用户复制的真实内容），直接喂给规则引擎处理文本；
 *  - 命中预检：只有存在启用规则能命中的短信才进入 handle，其余（银行/广告/通知类）完全静默；
 *  - 去重：记录已处理的最大 _id，新短信才处理；
 *  - 复用既有规则引擎 = 验证码提取 / 取件码 / 外卖取件跳转等所有已配置规则一律生效。
 */
object ShizukuSmsListener {
    private const val TAG = "短信"
    private const val POLL_INTERVAL_MILLIS = 2500L
    // 已处理的最大短信 _id（避免重复触发）
    @Volatile
    private var lastSeenId: Long = 0L
    // v1.141.42 @Volatile：worker 线程 while(running) 读、主线程 stop() 写，无 volatile 则跨线程不可见，
    // stop() 后线程可能继续轮询（"假停"）
    @Volatile
    private var running = false
    private var worker: Thread? = null

    fun start(context: Context) {
        if (running) return
        if (!ShizukuPermission.isGranted()) {
            HyperLog.d(TAG, "Shizuku 短信监听: 权限未授予, 跳过")
            return
        }
        running = true
        val appContext = context.applicationContext
        worker = Thread({
            // 初始化 lastSeenId = 当前最新 _id，避免历史短信立即触发
            lastSeenId = readLatestId()
            while (running) {
                runCatching { pollOnce(appContext) }
                    .onFailure { HyperLog.d(TAG, "短信监听轮询异常: ${it.message}") }
                runCatching { Thread.sleep(POLL_INTERVAL_MILLIS) }
            }
        }, "HyperCopy-SmsListener").also { it.start() }
        HyperLog.d(TAG, "Shizuku 短信监听已启动")
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        HyperLog.d(TAG, "Shizuku 短信监听已停止")
    }

    /** 单次轮询：读最新短信，命中规则才交给引擎处理 */ 
    private fun pollOnce(context: Context) {
        // 读最新一条短信（content query 不支持 --limit，用 sort DESC 让最新在首行）
        val output = ShizukuProcess.start(arrayOf("sh", "-c", "content query --uri content://sms --projection _id:body --sort 'date DESC'"))?.let { proc ->
            runCatching {
                proc.inputStream.bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }.orEmpty()
        if (output.isBlank()) return
        // 解析第一行 Row: 0 _id=NN, body=xxx, date=...
        val firstLine = output.lineSequence().firstOrNull { it.startsWith("Row:") } ?: return
        val id = Regex("""_id=(\d+)""").find(firstLine)?.groupValues?.get(1)?.toLongOrNull() ?: return
        // body 值含中文/逗号，用末尾 ", date=" 或行尾作边界提取完整 body
        val body = Regex("""body=(.*?)(?:, date=|$)""", RegexOption.DOT_MATCHES_ALL)
            .find(firstLine)?.groupValues?.get(1)?.trim() ?: ""
        // 去重：不是新短信则不处理
        if (id <= lastSeenId) return
        lastSeenId = id
        if (body.isBlank()) return
        // 命中预检：仅当存在启用规则能命中该短信时才进入完整引擎。
        // 验证码/取件码/取件/外卖等已配置规则都会在此命中；其余（银行/广告）静默忽略。
        val rules = runCatching { RuleRepository(context).readRules() }.getOrDefault(emptyList())
        if (rules.isEmpty()) return
        val hit = runCatching { findRule(body, rules, sourcePackage = "sms", activeOnly = true) != null }.getOrDefault(false)
        if (!hit) {
            HyperLog.d(TAG, "短信无规则命中, 忽略 _id=$id: ${body.take(20)}")
            return
        }
        HyperLog.d(TAG, "检测到规则命中短信 _id=$id: ${body.take(40)}")
        // 直接喂给完整规则引擎（source=sms, skipSelfCheck 走轮询通道语义，不写用户剪贴板）
        ClipboardTextHandler.handle(context, body, source = "sms", skipSelfCheck = true)
    }

    /** 读取当前最新短信 _id（首次启动初始化用） */
    private fun readLatestId(): Long {
        return runCatching {
            val output = ShizukuProcess.start(arrayOf("sh", "-c", "content query --uri content://sms --projection _id --sort 'date DESC'"))?.let { proc ->
                runCatching { proc.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
            }.orEmpty()
            val firstLine = output.lineSequence().firstOrNull { it.startsWith("Row:") } ?: return@runCatching 0L
            Regex("""_id=(\d+)""").find(firstLine)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        }.getOrDefault(0L)
    }
}