package io.github.hypercopy.clipboard.handling

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.hypercopy.Config
import io.github.hypercopy.HyperLog
import io.github.hypercopy.R
import io.github.hypercopy.TraceLogger
import io.github.hypercopy.clipboard.jump.PendingJump
import io.github.hypercopy.clipboard.jump.PendingJumpCoordinator
import io.github.hypercopy.clipboard.jump.HeadlessWebViewResolver
import io.github.hypercopy.clipboard.jump.MiuiSuperIslandNotification
import io.github.hypercopy.clipboard.privileged.MiuiXmsfNetworkBlocker
import io.github.hypercopy.clipboard.monitor.ShizukuPermission
import io.github.hypercopy.clipboard.monitor.ShizukuProcess
import io.github.hypercopy.data.rules.ExpressCompanyDetector
import io.github.hypercopy.data.rules.RuleActionMode
import io.github.hypercopy.data.rules.RuleCategory
import io.github.hypercopy.data.rules.RuleConfig
import io.github.hypercopy.data.rules.RuleRepository
import io.github.hypercopy.data.settings.SettingsRepository
import io.github.hypercopy.data.rules.cachedRegex
import io.github.hypercopy.data.rules.directIntent
import io.github.hypercopy.data.rules.extractParameters
import io.github.hypercopy.data.rules.findRule
import io.github.hypercopy.data.rules.matchesInput
import io.github.hypercopy.data.rules.matchRule
import io.github.hypercopy.data.rules.parseIntent
import io.github.hypercopy.data.rules.resolveInputUrl
import io.github.hypercopy.data.rules.resolveTemplate
import io.github.hypercopy.data.rules.clearClipboardEffective
import io.github.hypercopy.data.rules.triggerPatterns
import java.net.HttpURLConnection
import java.net.URL
import io.github.hypercopy.ui.activities.RuleSuggestionActivity
import kotlin.concurrent.thread

object ClipboardTextHandler {
    private const val TAG = "HyperCopy"
    // v1.40 最近一次成功处理的文本（供"从剪贴板添加"在剪贴板被清理后兜底）
    @Volatile
    var lastProcessedText: String? = null

    // v1.141.59 持久化最近处理文本：App 重启后静态缓存丢失，
    // "剪切板添加规则/智能识别"兜底仍可读回最近复制内容（修复 241 实测读取失败）
    private const val PREFS_LAST_PROCESSED = "hypercopy_last_processed"
    private const val KEY_LAST_TEXT = "last_text"

    private fun persistLastProcessed(context: Context, text: String?) {
        runCatching {
            val editor = context.getSharedPreferences(PREFS_LAST_PROCESSED, Context.MODE_PRIVATE).edit()
            if (text.isNullOrBlank()) editor.remove(KEY_LAST_TEXT) else editor.putString(KEY_LAST_TEXT, text)
            editor.apply()
        }
    }

    fun readPersistedLastProcessed(context: Context): String? = runCatching {
        context.getSharedPreferences(PREFS_LAST_PROCESSED, Context.MODE_PRIVATE)
            .getString(KEY_LAST_TEXT, null)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * v1.141.2 规则详情摘要（命中日志用），便于诊断 actionMode/渠道解析等。
     * 输出：分类 / 执行方式 / 规则级通知渠道 / 目标类型+包名+模板 / 优先级 / 分组。
     */
    private fun ruleDebugBrief(rule: RuleConfig): String = buildString {
        append("cat=${rule.category}")
        append(" mode=${rule.actionMode}")
        append(" notif=${rule.notificationMode ?: "跟随全局"}")
        val t = rule.target
        append(" target=${t.type}")
        if (t.packageName.isNotBlank()) append("/${t.packageName}")
        if (t.template.isNotBlank()) append(" tpl=${t.template.take(48)}")
        if (rule.priority != 0) append(" pri=${rule.priority}")
        if (rule.group.isNotBlank()) append(" group=${rule.group}")
        if (rule.delayMillis > 0) append(" delay=${rule.delayMillis}ms")
    }
    // v1.33 去重窗口改为可配置（SettingsRepository.readDuplicateWindowMillis），默认 1.5s
    private const val DEFAULT_DUPLICATE_WINDOW_MILLIS = 1_500L
    private const val UNMATCHED_NOTIFY_MIN_INTERVAL_MILLIS = 30_000L

    private var lastText: String = ""
    private var lastHandledAt: Long = 0L
    private var lastUnmatchedNotifyAt: Long = 0L
    // v1.103 版本号缓存（PackageManager 查询较慢，命中后缓存；日志首行即可见版本）
    @Volatile
    private var cachedAppVersion: String? = null
    private fun appVersion(context: Context): String {
        cachedAppVersion?.let { return it }
        cachedAppVersion = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName}(${pi.versionCode})"
        }.getOrDefault("?")
        return cachedAppVersion!!
    }
    // 并发保护：LSPosed 广播（主线程）与无障碍回调（后台线程）可能并发进入 handle，
    // 去重字段的"检查+写入"必须是原子的，否则双通道可能各自通过去重检查导致重复跳转
    private val dedupeLock = Any()
    private val unmatchedNotifyLock = Any()
    // v1.134 防跳转循环：跳转到目标 App 后剪贴板残留链接，用户在目标 App 内操作
    // （粘贴/复制/App自动读取）会再次触发嗅探 → 同内容再次命中 → 再次跳转。
    // 记录最近跳转（目标+内容+时间），30s 内同目标同内容不重复跳转。
    private data class LastJump(val targetPkg: String, val content: String, val at: Long)
    @Volatile
    private var lastJump: LastJump? = null
    // v1.141.65 通用防循环窗口 30s→8s：
    // 用户连续复制同一条口令/链接测试时，30s 窗口导致"复制→命中→被拦"假死（01:33 实锤：
    // 用户多次复制 27 HU7405 666:/🔐bHPqgu... 每次都被"同目标同内容30s内已跳转"拦截 → 看起来没反应）。
    // 8s 已足够：死循环（目标App写回剪贴板→再跳转）是秒级连续触发，滑动续期(L110)持续拦截；
    // 循环停止 8s 后自动解锁，不影响用户正常重复复制。与外卖取件场景(TAKEOUT_LOOP_WINDOW_MILLIS=8s)一致。
    private val JUMP_LOOP_WINDOW_MILLIS = 8_000L
    // v1.141.24 外卖取件跳转：跳浏览器/无头WebView 场景不会在目标 App 内形成循环（跳完即离开），
    // 用更短窗口（8s）避免连续测试被 30s 防循环拦截。识别：内容含 mt.cn/取件外卖特征。
    private val TAKEOUT_LOOP_WINDOW_MILLIS = 8_000L

    private fun shouldBlockJumpLoop(targetPkg: String, content: String): Boolean {
        if (targetPkg.isBlank() || content.isBlank()) return false
        val last = lastJump ?: return false
        // 外卖取件场景（含取件短链域名）缩短防循环窗口，便于连续测试/多次取件
        val isTakeout = content.contains("mt.cn", ignoreCase = true) || content.contains("dpurl.cn", ignoreCase = true)
        val window = if (isTakeout) TAKEOUT_LOOP_WINDOW_MILLIS else JUMP_LOOP_WINDOW_MILLIS
        val blocked = System.currentTimeMillis() - last.at < window &&
            last.targetPkg == targetPkg && last.content == content
        // v1.140.20 滑动续期：循环持续期间拦截持续生效（循环一停 30s 后自动解锁，不影响正常重复复制）
        if (blocked) lastJump = last.copy(at = System.currentTimeMillis())
        return blocked
    }

    private fun recordJump(targetPkg: String, content: String) {
        if (targetPkg.isBlank() || content.isBlank()) return
        lastJump = LastJump(targetPkg, content, System.currentTimeMillis())
        // v1.140.24 跳转后浮窗免疫：成功跳转同步写防抖记录，使跳转触发后的
        // 剪贴板清理(clearClipboardAfterJump)/写回保险/第三方回写 引发的再次嗅探，
        // 在 5s 内被 ClipboardWriteGuard 拦截 → 不再反复拉起透明浮窗阅读 → 消除屏幕闪烁
        ClipboardWriteGuard.record(content)
    }

    fun handle(context: Context, text: String, source: String, skipSelfCheck: Boolean = false) {
        // v1.100 无条件入口日志（Log.i 直出，不受日志级别过滤）——定位广播链路是否到达
        // v1.103 追加版本号，日志可直接确认运行版本
        // v1.104 双写 HyperLog（应用内日志界面可见）+ 生成 traceId + 记录起点耗时
        // v1.105 TraceLogger 块状结构化输出（输入/来源/开关/识别/命中/耗时）
        val tid = HyperLog.TraceContext.new()
        val startMs = System.currentTimeMillis()
        val input = text.trim()
        val appContext = context.applicationContext
        val settingsSummary = SettingsRepository(appContext).dumpSettings()
        HyperLog.i(TAG, "处理入口: text=${input.take(24)} source=$source ver=${appVersion(context)} tid=$tid")
        TraceLogger.emit(buildString {
            appendLine(TraceLogger.begin(tid, "处理周期"))
            appendLine(TraceLogger.line("输入", "${input.take(40)}${if (input.length > 40) "…" else ""} (len=${input.length})"))
            appendLine(TraceLogger.line("来源", source))
            appendLine(TraceLogger.line("版本", appVersion(context)))
            appendLine(TraceLogger.line("开关", settingsSummary))
        })
        if (input.isEmpty()) {
            HyperLog.d(TAG, "输入为空, 跳过处理")
            return
        }
        // v1.141.58 修复：移除"来源为本App 跳过处理"（Bug A）。
        // 根因：浮动窗口抢焦点读取时 source 可能被 dumpsys 抓成本 App 的 Activity 别名
        //（io.github.hypercopy.ui.framework.MainActivityAlias）→ startsWith 包名误判 self → 跳过处理
        // → 淘宝口令/链接首次读取无反应（14:52:37 日志实锤）。
        // 用户明确需求：软件内复制也要正常触发处理（日志页复制不再跳过）。
        // skipSelfCheck 参数保留兼容调用方，但不再执行 self 跳过。
        if (input.length > Config.CLIPBOARD_TEXT_MAX_LENGTH) {
            HyperLog.d(TAG, "输入超长(len=${input.length} > ${Config.CLIPBOARD_TEXT_MAX_LENGTH}), 跳过处理")
            return
        }
        // v1.79 剪贴板改写回写防抖：跳过自己刚写入的内容（避免 写回→检测→再处理 死循环）
        if (ClipboardWriteGuard.shouldIgnore(input)) {
            HyperLog.d(TAG, "忽略自身写入的剪贴板内容(防抖)")
            return
        }
        // v1.141.60 记录最近处理文本（供剪贴板被清理后"从剪贴板添加"兜底）
        // 提前到去重/防循环/监控开关拦截前：任何有效复制都会持久化，App 重启后仍可读回
        lastProcessedText = input
        persistLastProcessed(appContext, input)
        val now = System.currentTimeMillis()
        val duplicateWindow = SettingsRepository(context.applicationContext).readDuplicateWindowMillis()
        // v1.112 菜鸟委托跳转同单号长去重：浮动窗口会重复嗅探剪贴板残留单号（source=miui.home 等），
        // 1.5s 窗口不足导致同一单号被二次跳转（"2次进入"）。菜鸟场景用 8s 长窗口，其余保持配置值。
        val effectiveWindow = if (io.github.hypercopy.data.rules.ExpressCompanyDetector.looksLikeTrackingNumber(input)) {
            maxOf(duplicateWindow, 8_000L)
        } else {
            duplicateWindow
        }
        synchronized(dedupeLock) {
            if (input == lastText && now - lastHandledAt < effectiveWindow) {
                HyperLog.d(TAG, "去重窗口内重复内容, 跳过(距上次 ${now - lastHandledAt}ms < ${effectiveWindow}ms)")
                return
            }
            lastText = input
            lastHandledAt = now
        }

        val settingsRepository = SettingsRepository(appContext)
        // v1.85 快递直达：express 分类命中时强制直接跳转（跳过通知/灵动岛），默认开
        val expressDirectJump = settingsRepository.readExpressDirectJump()
        // v1.30 快捷磁贴总开关：暂停时跳过一切处理
        if (!settingsRepository.readMonitorEnabled()) {
            HyperLog.d(TAG, "监控总开关已关闭, 跳过处理")
            return
        }
        val appListWorkMode = settingsRepository.readAppListWorkMode()
        val appListPackages = settingsRepository.readAppListPackages()
        if (shouldSkipByAppList(source, appListWorkMode, appListPackages)) {
            HyperLog.d(TAG, "来源App列表拦截: source=$source mode=$appListWorkMode")
            return
        }

        val rules = RuleRepository(appContext).readRules()
        val ignoreJumpApp = settingsRepository.readIgnoreJumpApp()
        // 匹配调试日志（独立开关，不受软件内日志级别影响；开启后逐条打印匹配过程）
        // v1.103 增强：命中时输出具体触发的正则原文 + 捕获参数，未命中可结合 diagnose 定位原因
        val debugLog = settingsRepository.readMatchDebugLog()
        if (debugLog) {
            HyperLog.d(TAG, "[match-debug] input(len=${input.length}) source='$source' rules=${rules.size} tid=${HyperLog.TraceContext.current}")
            rules.forEachIndexed { i, r ->
                val hitPattern = runCatching {
                    r.triggerPatterns().firstOrNull { p -> cachedRegex(p, r.regexOptions).containsMatchIn(input) }
                }.getOrNull()
                if (hitPattern != null) {
                    val params = runCatching { r.extractParameters(input).entries.take(3).joinToString(" ") { "${it.key}=${it.value.take(20)}" } }.getOrDefault("")
                    HyperLog.d(TAG, "[match-debug] rule[$i] id=${r.id} name='${r.name}' ✅HIT regex=${hitPattern} params=[$params]")
                } else {
                    HyperLog.d(TAG, "[match-debug] rule[$i] id=${r.id} name='${r.name}' enabled=${r.enabled} match=false")
                }
            }
        }
        val stats = io.github.hypercopy.data.rules.RuleStatsRepository(appContext)
        // ① 规则优先：用户显式配置的规则优先级最高。
        //    原实现系统链接先于规则，导致用户添加的规则（如抖音）被系统链接逻辑抢先接管而"不生效"
        val match = matchRule(input, rules, source, activeOnly = true)
        if (match != null) {
            // v1.103 无条件命中日志：显示命中规则 + 具体触发的正则（不依赖 match-debug 开关）
            // v1.104 双写 HyperLog + tid + 耗时
            // v1.105 TraceLogger 块输出：识别 + 命中 + 周期结束
            val hitRegex = runCatching {
                match.rule.triggerPatterns().firstOrNull { p -> cachedRegex(p, match.rule.regexOptions).containsMatchIn(input) }
            }.getOrNull()
            val describe = if (match.rule.category == RuleCategory.Express) ExpressCompanyDetector.describe(input) else null
            TraceLogger.emit(buildString {
                appendLine(TraceLogger.line("识别", describe ?: "-"))
                appendLine(TraceLogger.line("命中", "${match.rule.name} (mode=${match.rule.actionMode})"))
                appendLine(TraceLogger.line("正则", hitRegex ?: "-"))
                // v1.141.2 命中日志输出规则详情，便于诊断渠道/模式解析
                appendLine(TraceLogger.line("规则详情", ruleDebugBrief(match.rule)))
                append(TraceLogger.end("规则命中", System.currentTimeMillis() - startMs))
            })
            val targetPackageName = jumpPackageName(appContext, match.rule.target.packageName, match.intent)
            if (shouldIgnoreJump(appContext, source, targetPackageName, ignoreJumpApp)) {
                HyperLog.d(TAG, "目标App内复制已忽略(防循环): source=$source target=$targetPackageName")
                return
            }
            // v1.134 防跳转循环：30s 内同目标同内容不重复跳转（目标App内残留触发）
            if (shouldBlockJumpLoop(targetPackageName, input)) {
                HyperLog.d(TAG, "同目标同内容30s内已跳转, 忽略(防循环): target=$targetPackageName")
                return
            }
            recordJump(targetPackageName, input)
            stats.increment(match.rule.id)
            // v1.85 快递直达：express 分类 + 开关开 → 强制直接跳转（覆盖规则级通知模式）
            val matchNotifMode = if (expressDirectJump && match.rule.category == RuleCategory.Express) {
                Config.JUMP_NOTIFICATION_MODE_NONE
            } else {
                match.rule.notificationMode
            }
            submitJump(
                appContext,
                PendingJump.IntentJump(
                    title = match.rule.name,
                    intent = match.intent,
                    packageName = targetPackageName,
                ),
                match.rule.clearClipboardEffective,
                notificationModeOverride = matchNotifMode,
                delayMillis = match.rule.delayMillis,
                ruleId = match.rule.id,
            )
            return
        }

        val rule = findRule(input, rules, source, activeOnly = true)
        if (rule != null) {
            // v1.103 无条件命中日志：DirectOpen/WebView/ClipboardWrite 等模式命中时显示规则 + 正则
            // v1.104 双写 HyperLog + tid + 耗时
            // v1.105 TraceLogger 块输出：识别 + 命中 + 周期结束
            val hitRegex = runCatching {
                rule.triggerPatterns().firstOrNull { p -> cachedRegex(p, rule.regexOptions).containsMatchIn(input) }
            }.getOrNull()
            val describe = if (rule.category == RuleCategory.Express) ExpressCompanyDetector.describe(input) else null
            TraceLogger.emit(buildString {
                appendLine(TraceLogger.line("识别", describe ?: "-"))
                appendLine(TraceLogger.line("命中", "${rule.name} (mode=${rule.actionMode})"))
                appendLine(TraceLogger.line("正则", hitRegex ?: "-"))
                // v1.141.2 命中日志输出规则详情，便于诊断渠道/模式解析
                appendLine(TraceLogger.line("规则详情", ruleDebugBrief(rule)))
                append(TraceLogger.end("规则命中", System.currentTimeMillis() - startMs))
            })
            when (rule.actionMode) {
                RuleActionMode.DirectOpen -> {
                    val targetPkg = rule.target.packageName
                    var intent = rule.directIntent(input, appContext.packageManager)
                    // v1.108 菜鸟委托直达：express 分类 + 目标菜鸟 → 官方 entrust 机制直达物流详情页
                    // （逆向实证：launchIntent + extras{url=guoguo://go/logistic?mailNo=X, from=entrust}
                    //   → HomePageActivity.startEntrustActivity → Router → LogisticDetailActivity，无需弹窗/扫描）
                    if (rule.category == RuleCategory.Express && targetPkg == io.github.hypercopy.clipboard.monitor.CainiaoAutoConfirm.CAINIAO_PACKAGE) {
                        // v1.141.48 修复：mailNo 必须为纯单号。原实现传整段剪贴板文本（如短信原文
                        // 【京东物流】关于运单JD0228717729868配送情况...），菜鸟 Router 解析 mailNo
                        // 失败 → 打不开详情页（实测：纯单号可直达、整段短信文本打不开，且触发系统选择器）
                        val entrustTrackingNo = ExpressCompanyDetector.extractTrackingNumber(input)?.uppercase()
                        if (entrustTrackingNo != null) {
                            val entrust = io.github.hypercopy.clipboard.monitor.CainiaoAutoConfirm.buildCainiaoEntrustIntent(appContext, entrustTrackingNo)
                            HyperLog.d(TAG, "委托直达详情页: ${entrust.getStringExtra(io.github.hypercopy.clipboard.monitor.CainiaoAutoConfirm.ENTRUST_EXTRA_URL)}")
                            intent = entrust
                        } else {
                            HyperLog.w(TAG, "菜鸟委托直达: 未提取到纯单号, 走默认启动(可能落首页) input=${input.take(40)}")
                        }
                    }
                    var fallbackUsed = false
                    // v1.83 快递兜底：目标 App 未安装时回退快递100 网页查件（网页端自动识别快递公司）
                    if (rule.category == RuleCategory.Express && targetPkg.isNotBlank()) {
                        val installed = runCatching { appContext.packageManager.getPackageInfo(targetPkg, 0) }.isSuccess
                        if (!installed) {
                            val nu = rule.extractParameters(input).values.firstOrNull().orEmpty()
                            if (nu.isNotBlank()) {
                                intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.kuaidi100.com/chaxun?nu=${Uri.encode(nu)}"))
                                fallbackUsed = true
                                HyperLog.d(TAG, "快递100网页兜底: 菜鸟未安装 nu=$nu")
                            }
                        }
                    }
                    val targetPackageName = jumpPackageName(appContext, if (fallbackUsed) "" else targetPkg, intent)
                    // v1.84 快递公司识别：express 规则命中时，通知/Toast 显示所属快递公司
                    val company = if (rule.category == RuleCategory.Express) ExpressCompanyDetector.detect(input) else null
                    val jumpTitle = if (company != null) "${rule.name} · $company" else rule.name
                    if (shouldIgnoreJump(appContext, source, targetPackageName, ignoreJumpApp)) {
                        HyperLog.d(TAG, "目标App内复制已忽略(防循环): source=$source target=$targetPackageName")
                        return
                    }
                    // v1.134 防跳转循环：30s 内同目标同内容不重复跳转（目标App内残留触发）
                    if (shouldBlockJumpLoop(targetPackageName, input)) {
                        HyperLog.d(TAG, "同目标同内容30s内已跳转, 忽略(防循环): target=$targetPackageName")
                        return
                    }
                    recordJump(targetPackageName, input)
                    // v1.139.2c/v1.140.25 便捷下载写回保险：确认将跳转后才写回剪贴板，防 HyperOS/MIUI
                    // 剪贴板隐私保护导致目标 App 读不到链接。移至防循环判断之后，避免被防循环拦截的
                    // 重复复制仍写回剪贴板 → 触发新嗅探 → 维持异常粘贴循环
                    if (targetPkg == "com.lcw.easydownload") {
                        runCatching {
                            val cm = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("HyperCopy", input))
                            ClipboardWriteGuard.record(input)
                            HyperLog.d(TAG, "便捷下载剪贴板写回保险: len=${input.length}")
                        }
                    }
                    stats.increment(rule.id)
                    // v1.85 快递直达：express 分类 + 开关开 → 强制直接跳转（覆盖规则级通知模式）
                    val directNotifMode = if (expressDirectJump && rule.category == RuleCategory.Express) {
                        Config.JUMP_NOTIFICATION_MODE_NONE
                    } else {
                        rule.notificationMode
                    }
                    submitJump(
                        appContext,
                        PendingJump.IntentJump(
                            title = jumpTitle,
                            intent = intent,
                            packageName = targetPackageName,
                        ),
                        rule.clearClipboardEffective,
                        notificationModeOverride = directNotifMode,
                        delayMillis = rule.delayMillis,
                        ruleId = rule.id,
                    )
                    return
                }
                RuleActionMode.WebViewResolveAndOpen -> {
                    if (shouldIgnoreJump(appContext, source, rule.target.packageName, ignoreJumpApp)) return
                    stats.increment(rule.id)
                    startWebViewResolve(appContext, rule, input)
                    return
                }
                RuleActionMode.ParseAndOpen -> {
                    // matchRule 已处理 ParseAndOpen 命中；走到这里说明 extraction 未满足，交给系统链接兜底
                }
                // v1.79 剪贴板改写回写：命中后把模板渲染结果写回剪贴板（不跳转）
                RuleActionMode.ClipboardWrite -> {
                    stats.increment(rule.id)
                    val parameters = rule.extractParameters(input).toMutableMap()
                    parameters["input"] = input
                    val rendered = runCatching { rule.target.resolveTemplate(parameters, encode = { it }) }.getOrDefault(input)
                    val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(rule.name, rendered))
                    ClipboardWriteGuard.record(rendered)
                    HyperLog.d(TAG, "剪贴板改写: '${rendered.take(80)}' (规则=${rule.name})")
                    val preview = rendered.take(80)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            appContext,
                            appContext.getString(R.string.clipboard_write_toast, preview),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                    // v1.126+ 短信验证码类规则也支持通知方式（normal/live/miui_island）
                    clipboardWriteNotify(appContext, rule, input, rendered)
                    return
                }
                // v1.138 仅通知：命中后发通知栏通知（不跳转、不改剪贴板）——取件码/取货码场景
                RuleActionMode.NotifyOnly -> {
                    stats.increment(rule.id)
                    val parameters = rule.extractParameters(input).toMutableMap()
                    parameters["input"] = input
                    val rendered = runCatching { rule.target.resolveTemplate(parameters, encode = { it }) }.getOrDefault(input)
                    notifyOnlyResult(appContext, rule, input, rendered)
                    return
                }
            }
        }
        // v1.84 云端快递识别兜底（默认关）：本地规则未命中且文本疑似字母前缀单号时，调快递100 自动识别公司并查件
        if (settingsRepository.readCloudExpressDetect() && ExpressCompanyDetector.looksLikeTrackingNumber(input)) {
            val cloudResult = queryCloudExpressCompany(input)
            if (cloudResult != null) {
                val (company, trackingNo) = cloudResult
                HyperLog.d(TAG, "云端快递识别: $company ($trackingNo)")
                val cainiaoLaunch = appContext.packageManager.getLaunchIntentForPackage("com.cainiao.wireless")
                val intent = cainiaoLaunch ?: Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.kuaidi100.com/chaxun?nu=${Uri.encode(trackingNo)}"),
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                submitJump(
                    appContext,
                    PendingJump.IntentJump(
                        title = "$company · $trackingNo",
                        intent = intent,
                        packageName = cainiaoLaunch?.`package`.orEmpty(),
                    ),
                    false,
                )
                return
            }
        }
        // ② 系统链接处理（规则未命中时兜底）
        if (settingsRepository.readSystemLinkHandling()) {
            val systemJump = SystemLinkHandler.createJump(appContext, input)
            if (systemJump != null && !shouldIgnoreJump(appContext, source, systemJump.packageName, ignoreJumpApp)) {
                submitJump(appContext, systemJump, settingsRepository.readSystemLinkClearClipboardAfterJump())
                return
            }
        }

        // ③ 规则与系统链接均未命中 → 未命中提醒
        // v1.103 未命中诊断：输出可读原因（如"圆通需 13 位数字"），日志直接可定位
        // v1.104 追加 tid
        // v1.105 TraceLogger 块结束：诊断 + 周期结束
        val diagnose = ExpressCompanyDetector.diagnose(input)
        TraceLogger.emit(buildString {
            appendLine(TraceLogger.line("诊断", diagnose ?: "未知"))
            append(TraceLogger.end("未命中", System.currentTimeMillis() - startMs))
        })
        notifyUnmatched(appContext, input)
    }

    private fun startWebViewResolve(context: Context, rule: RuleConfig, input: String) {
        val resolveUrl = rule.resolveInputUrl(input)
        // v1.141.24 外卖取件跳转：后台无头 WebView 自动走完整链（软件内模拟浏览器）。
        // mt.cn → 302 peisong → 页面 JS 生成 weixin://dl/business/?t=TICKET 并 location.href 跳转
        // → WebView shouldOverrideUrlLoading 捕获该 scheme → 自动拉起微信小程序（用户仅点一次系统"打开"确认）。
        // 不能用纯 HTTP 302(OneRedirectResolver)，拿不到页面 JS 动态生成的 scheme；需真实 WebView 引擎。
        if (resolveUrl.contains("mt.cn", ignoreCase = true) || resolveUrl.contains("ele.me", ignoreCase = true)) {
            HyperLog.d(TAG, "外卖取件跳转: 后台无头WebView走链 $resolveUrl")
            HeadlessWebViewResolver.resolveAndLaunch(
                context = context,
                url = resolveUrl,
                packageName = "",
                clearClipboardAfterJump = rule.clearClipboardEffective,
            )
            return
        }
        if (rule.parseAfterRedirect) {
            thread(name = "HyperCopyRedirectResolve") {
                val redirectedUrl = OneRedirectResolver.resolve(resolveUrl)
                HyperLog.d(TAG, "redirect parse url: $redirectedUrl")
                val intent = rule.parseIntent(
                    redirectedUrl,
                    requireMatch = false,
                    extraParameters = mapOf("input" to input, "redirectUrl" to redirectedUrl),
                ) ?: run {
                    HyperLog.d(TAG, "redirect parse no parameters: $redirectedUrl")
                    return@thread
                }
                val targetPackageName = jumpPackageName(context, rule.target.packageName, intent)
                submitJump(
                    context,
                    PendingJump.IntentJump(
                        title = rule.name,
                        intent = intent,
                        packageName = targetPackageName,
                    ),
                    rule.clearClipboardEffective,
                    notificationModeOverride = rule.notificationMode,
                    delayMillis = rule.delayMillis,
                    ruleId = rule.id,
                )
            }
            return
        }
        submitJump(
            context,
            PendingJump.WebViewJump(
                title = rule.name,
                url = resolveUrl,
                packageName = rule.target.packageName,
            ),
            rule.clearClipboardEffective,
        )
    }

    // v1.79 延迟跳转：delayMillis>0 时延迟提交（执行前校验规则仍存在且启用）
    private fun submitJump(
        context: Context,
        jump: PendingJump,
        clearClipboardAfterJump: Boolean,
        notificationModeOverride: String? = null,
        delayMillis: Int = 0,
        ruleId: String? = null,
    ) {
        if (delayMillis > 0) {
            val appContext = context.applicationContext
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (ruleId != null) {
                    val stillValid = RuleRepository(appContext).readRules().any { it.id == ruleId && it.enabled }
                    if (!stillValid) {
                        HyperLog.d(TAG, "延迟跳转已取消: 规则被删除/禁用 id=$ruleId")
                        return@postDelayed
                    }
                }
                submitJumpNow(appContext, jump, clearClipboardAfterJump, notificationModeOverride)
            }, delayMillis.toLong())
        } else {
            submitJumpNow(context, jump, clearClipboardAfterJump, notificationModeOverride)
        }
    }

    private fun submitJumpNow(context: Context, jump: PendingJump, clearClipboardAfterJump: Boolean, notificationModeOverride: String?) {
        HyperLog.d(TAG, "跳转: ${jump.title} -> ${jump.packageName.ifBlank { "web" }} (mode=${notificationModeOverride ?: "跟随全局"}) tid=${HyperLog.TraceContext.current}")
        // v1.93 引导：express 跳菜鸟且自动确认未开启时提示一次
        val guideRepo = SettingsRepository(context.applicationContext)
        if (jump.packageName == "com.cainiao.wireless" &&
            !guideRepo.readCainiaoAutoConfirm() &&
            guideRepo.readExpressDirectJump()
        ) {
            HyperLog.w(TAG, "提示: 菜鸟查件自动确认未开启——请在 设置-快递直达 中开启「菜鸟查件自动确认」，复制单号后才会自动点确认直达详情页")
            runCatching {
                android.widget.Toast.makeText(
                    context.applicationContext,
                    "开启「菜鸟查件自动确认」后，复制快递单号将自动直达菜鸟详情页",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        // v1.30 命中即时反馈（可开关）：Toast 提示命中的规则与目标 App
        val settingsRepository = SettingsRepository(context.applicationContext)
        if (settingsRepository.readShowHitToast()) {
            val label = jump.packageName.ifBlank { "" }
            val toastText = context.getString(io.github.hypercopy.R.string.toast_hit_rule, jump.title, label)
            // v1.55 修复：后台线程（无障碍回调）直接 Toast 崩溃（Can't toast on a thread...）
            // → 切主线程显示
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    context.applicationContext,
                    toastText,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        // v1.30 最近跳转历史
        io.github.hypercopy.data.rules.JumpHistoryRepository(context.applicationContext)
            .record(jump.title, jump.packageName)
        PendingJumpCoordinator.submit(context, jump, clearClipboardAfterJump, notificationModeOverride)
    }

    private fun shouldIgnoreJump(context: Context, source: String, targetPackageName: String, ignoreJumpApp: Boolean): Boolean {
        if (!ignoreJumpApp) return false
        if (targetPackageName.isBlank()) return false
        if (source.isNotBlank() && source == targetPackageName) return true
        // Shizuku 等场景拿不到复制来源时：用当前前台 App 兜底判断。
        // 用户正在目标 App 内复制分享链接（如抖音里分享抖音链接）→ 属于分享行为，不二次打开。
        if (source.isBlank()) {
            val foreground = foregroundPackageName(context) ?: return false
            return foreground == targetPackageName
        }
        return false
    }

    /** 获取当前前台 App 包名（Shizuku shell 多命令回退；失败返回 null 不拦截） */
    private fun foregroundPackageName(context: Context): String? {
        if (!ShizukuPermission.isGranted()) return null
        val self = context.packageName
        runCatching {
            // 方案1：dumpsys window（HyperOS 实测可用）
            foregroundFromShell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'", self)?.let { return it }
            // 方案2：dumpsys activity activities
            foregroundFromShell("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'", self)?.let { return it }
        }
        return null
    }

    private fun foregroundFromShell(command: String, selfPackageName: String): String? {
        val process = ShizukuProcess.start(arrayOf("sh", "-c", command)) ?: return null
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.destroyForcibly()
        return Regex("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+").findAll(output)
            .map { it.value }
            .firstOrNull { it != selfPackageName }
    }

    private fun jumpPackageName(context: Context, configPackageName: String, intent: android.content.Intent): String {
        if (configPackageName.isNotBlank()) return configPackageName
        return intent.`package` ?: intent.component?.packageName ?: intent.resolveActivity(context.packageManager)?.packageName.orEmpty()
    }

    // v1.84 云端快递识别：调快递100 自动识别接口（公开接口，无需 key），返回 (公司名, 单号)
    private fun queryCloudExpressCompany(text: String): Pair<String, String>? {
        val nu = ExpressCompanyDetector.extractTrackingNumber(text) ?: return null
        return runCatching {
            val connection = URL("https://www.kuaidi100.com/autonumber/autoComNum?text=${Uri.encode(nu)}")
                .openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(body)
                val auto = json.optJSONArray("auto")
                if (auto != null && auto.length() > 0) {
                    val first = auto.getJSONObject(0)
                    val company = first.optString("comName").ifBlank { first.optString("comCode") }
                    if (company.isNotBlank()) company to nu else null
                } else {
                    HyperLog.d(TAG, "云端快递识别: $nu 无结果")
                    null
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse {
            HyperLog.d(TAG, "云端快递识别失败: ${it.message}")
            null
        }
    }

    private fun shouldSkipByAppList(source: String, workMode: String, packages: Set<String>): Boolean {
        if (source.isBlank()) return false
        // v1.141.26 内置通道来源（短信监听等 source="sms"）不应受"来源 App 黑白名单"限制。
        // App 黑白名单语义是"控制哪些 App 内复制能触发"，短信不是 App 复制，放行。
        if (source == "sms") return false
        return when (workMode) {
            Config.APP_LIST_WORK_MODE_BLACKLIST -> source in packages
            Config.APP_LIST_WORK_MODE_WHITELIST -> source !in packages
            else -> false
        }
    }

    /** 复制内容未命中任何规则时，发通知引导用户一键添加规则（可开关，带频率限制防打扰） */
    private fun notifyUnmatched(context: Context, text: String) {
        val settingsRepository = SettingsRepository(context)
        if (!settingsRepository.readNotifyUnmatched()) return
        val now = System.currentTimeMillis()
        synchronized(unmatchedNotifyLock) {
            if (now - lastUnmatchedNotifyAt < UNMATCHED_NOTIFY_MIN_INTERVAL_MILLIS) return
            lastUnmatchedNotifyAt = now
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Config.UNMATCHED_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.notification_channel_unmatched_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val intent = Intent(context, RuleSuggestionActivity::class.java)
            .putExtra(Config.EXTRA_SUGGESTION_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, Config.UNMATCHED_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_add)
            .setContentTitle(context.getString(R.string.notification_unmatched_title))
            .setContentText(context.getString(R.string.notification_unmatched_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notification_unmatched_text)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(Config.UNMATCHED_NOTIFICATION_ID, notification)
        }
        HyperLog.d(TAG, "未命中提示通知已发送")
    }

    /** v1.138 仅通知（notify_only）：命中后发通知栏通知，不跳转、不改剪贴板。
     * 取件码/取货码场景：复制短信 → 通知栏展示提取结果（如取件码 3-3-1020）。
     * 通知格式由设置控制：是否显示平台名（【XX】）+ 总开关。 */
    private fun notifyOnlyResult(context: Context, rule: RuleConfig, input: String, content: String) {
        val settingsRepository = SettingsRepository(context)
        if (!settingsRepository.readNotifyPickupCode()) {
            HyperLog.d(TAG, "仅通知已关闭(设置), 跳过: ${rule.name}")
            return
        }
        // v1.141.87 结构化通知：胶囊(title)=平台+核心值；展开(content)=平台+类型+值
        // 平台名从短信【XX】提取（开关控制）；类型标签按规则语义补全（验证码/取件码）
        val includePlatform = settingsRepository.readNotifyIncludePlatform()
        val platform = if (includePlatform) extractNotifyPlatform(input) else ""
        // v1.141.78 通知方式适配 + 结构化排版：
        // ① 空内容兜底：template 空时用提取参数结构化渲染（不回退原文，避免通知含链接）
        val effectiveContent = if (content.isBlank()) {
            val params = rule.extractParameters(input).toMutableMap()
            params["input"] = input
            runCatching { rule.target.resolveTemplate(params, encode = { it }) }.getOrDefault("")
        } else content
        val label = notifyLabel(rule, effectiveContent)
        // ② 渠道适配：灵动岛/普通/live 统一结构化（v1.141.87 不再因 miui_island 丢弃平台名）
        // v1.141.87c 关键词式通知：title=平台+类型+码值；content 多行结构化
        val title: String
        val text: String
        if (rule.name.contains("外卖")) {
            // v1.141.87e 外卖结构化：title=平台外卖取件码+码值；content=地址+存放位置
            val params = rule.extractParameters(input)
            val code = params["r2"].orEmpty()
            val cabinet = params["r1"].orEmpty()
            val address = extractWaimaiAddress(input)
            title = if (platform.isNotBlank()) "${platform}外卖取件码$code" else "外卖取件码$code"
            text = buildString {
                if (address.isNotBlank()) append("地址：$address\n")
                if (cabinet.isNotBlank()) append("存放位置：$cabinet")
            }
        } else {
            title = when {
                effectiveContent.isBlank() -> platform.takeIf { it.isNotBlank() } ?: rule.name
                platform.isNotBlank() && label.isNotBlank() -> "$platform$label：$effectiveContent"
                platform.isNotBlank() -> "$platform：$effectiveContent"
                label.isNotBlank() -> "$label：$effectiveContent"
                else -> effectiveContent
            }
            text = ""
        }
        // v1.141 委托独立文本通知引擎：渠道=规则级>全局文本渠道>普通，channel/ID独立，不混用跳转
        TextNotification.notify(
            context,
            TextNotificationEntry(
                notificationId = Config.TEXT_NOTIFY_PICKUP_NOTIFICATION_ID,
                title = title,
                content = text,
                packageName = rule.target.packageName,
                icon = android.R.drawable.ic_dialog_info,
            ),
            rule,
            TAG,
        )
    }

    /** v1.126+ 短信验证码（ClipboardWrite）类规则：自动复制恒定 + 通知可选（按独立文本渠道）。
     * 复制动作由调用方（handle 内）恒定执行，不进入文本通知引擎。
     * v1.141.87 结构化通知：胶囊(title)=平台+验证码；展开(content)=【平台】验证码+码值 */
    private fun clipboardWriteNotify(context: Context, rule: RuleConfig, input: String, text: String) {
        val includePlatform = SettingsRepository(context).readNotifyIncludePlatform()
        val platform = if (includePlatform) extractNotifyPlatform(input) else ""
        val title = if (platform.isNotBlank()) "${platform}验证码：$text" else "验证码：$text"
        // v1.141.87c 关键词式通知：title 完整（平台+验证码+码值），content 置空避免重复
        val content = ""
        // v1.141 委托独立文本通知引擎：渠道=规则级>全局文本渠道>普通，channel/ID独立，不混用跳转
        TextNotification.notify(
            context,
            TextNotificationEntry(
                notificationId = Config.TEXT_NOTIFY_VERIFY_NOTIFICATION_ID,
                title = title,
                content = content,
                packageName = rule.target.packageName,
                icon = android.R.drawable.ic_menu_edit,
            ),
            rule,
            TAG,
        )
    }

    /** v1.141.87 平台提取：从短信【XX】提取来源（招商银行/丰巢/美团…），无则空串 */
    private fun extractNotifyPlatform(input: String): String =
        Regex("【([^】]+)】").find(input)?.groupValues?.get(1)?.trim() ?: ""

    /** v1.141.87e 外卖地址提取：「已放在{地址}{柜位}…」→ 地址部分（柜位前文本） */
    private fun extractWaimaiAddress(input: String): String =
        Regex("已放[在至](.+?)(?:[A-Za-z0-9]{0,3}号?柜|外卖柜|格口)").find(input)?.groupValues?.get(1)?.trim() ?: ""

    /** v1.141.87 通知类型标签：content 已含类型词时不再重复（如外卖模板已含"取件码"） */
    private fun notifyLabel(rule: RuleConfig, content: String): String = when {
        rule.name.contains("验证码") -> "验证码"
        rule.name.contains("取件码") && !content.contains("取件码") -> "取件码"
        else -> ""
    }
}
/** v1.79 剪贴板改写回写防抖：记录最近写入内容，短窗口内跳过相同内容的再次处理（防死循环） */
object ClipboardWriteGuard {
    @Volatile
    private var lastSignature = ""
    @Volatile
    private var lastWrittenAt = 0L
    private const val WINDOW_MILLIS = 5_000L

    fun record(text: String) {
        lastSignature = text.hashCode().toString() + "|" + text.length
        lastWrittenAt = System.currentTimeMillis()
    }

    fun shouldIgnore(text: String): Boolean {
        if (lastSignature.isEmpty()) return false
        return text.hashCode().toString() + "|" + text.length == lastSignature &&
            System.currentTimeMillis() - lastWrittenAt < WINDOW_MILLIS
    }

    /** v1.139 剪贴板改写防抖窗口内是否活跃（供浮动窗口/嗅探跳过改写回环读取） */
    fun isWithinWriteWindow(): Boolean =
        lastSignature.isNotEmpty() && System.currentTimeMillis() - lastWrittenAt < WINDOW_MILLIS
}
