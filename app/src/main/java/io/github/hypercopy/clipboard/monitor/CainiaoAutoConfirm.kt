package io.github.hypercopy.clipboard.monitor

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.hypercopy.data.settings.SettingsRepository

/**
 * v1.88 菜鸟查件自动确认（双通道 + 双目标）：
 * 目标一：菜鸟官方剪贴板弹窗（您是否要查询包裹 / 立即查看）——菜鸟已运行时触发；
 * 目标二：小米 AI 引擎复制直达弹窗（打开菜鸟 / 确认）——冷启动场景首选，官方链接直达详情页。
 * 通道：事件触发 + mark 后 0.8/1.6/2.4s 主动扫描（不依赖系统事件派发）。
 * 日志：android.util.Log，TAG=HyperCopyA11y（绕过 HyperLog 级别过滤）。
 */
object CainiaoAutoConfirm {
    private const val TAG = "无障碍"
    const val CAINIAO_PACKAGE = "com.cainiao.wireless"
    private const val RECENT_WINDOW_MS = 8_000L
    private const val MAX_DEPTH = 40
    private const val MAX_RETRY = 3
    private const val RETRY_DELAY_MS = 400L
    /** v1.113 补偿宽限期：启动后此时间内不触发冷启动补偿（热启动委托 Weex 渲染需 2-4s） */
    private const val COMPENSATE_GRACE_MILLIS = 3_500L
    /** v1.109 详情页到达后延迟弹窗后扫（菜鸟 JS 检测剪贴板弹窗出现时机） */
    private const val POST_DETAIL_SCAN_DELAY_MS = 2_500L
    private const val POST_DETAIL_SCAN_MAX = 3

    /** 菜鸟弹窗特征词（apktool 实证：您是否要查询包裹 / 立即查看） */
    private val CAINIAO_TITLE_HINTS = listOf("查询包裹", "是否要查询", "快递单号", "mailNo")
    private val CAINIAO_BUTTON_HINTS = listOf("立即查看", "查看", "查询")
    /** v1.137 业务提示弹窗特征（菜鸟"温馨提示/系统繁忙/暂无物流"等）：非可确认弹窗，
     * 识别到即视为页面已就绪、无需处理——标记完成停止扫描，防止宽限期后冷启动补偿误重启菜鸟
     *  v1.141.51 补充"请检查运单号输入是否正确"（无效单号提示，20:49 截图2 实锤）：
     *  无效单号时菜鸟显示该提示，A11y 应识别为业务提示停止扫描，不再误判列表页"顺丰速运 SF+数字"为详情页 */
    private val CAINIAO_IGNORE_HINTS = listOf("温馨提示", "系统繁忙", "暂无物流", "请检查运单号", "运单号输入")

    /** v1.110 菜鸟自带分享弹窗（逆向实证：PackageShareDialog，BottomSheetDialogFragment）
     * 触发：详情页分享按钮 → JS 桥 JsHybridAuthBuyModule → showDialog
     * 结构：X关闭(iv_close→dismissAllowingStateLoss) + 保存图片/微信/短信/QQ + 微信邀请绑定
     * 关闭方式：⚠️ v1.111 修正——「暂不绑定」文本无点击监听(纯展示)，必须点 X 关闭按钮！
     * 特征：弹窗标题「绑定菜鸟好友，分享更方便」/「分享更方便」 */
    private val SHARE_DIALOG_HINTS = listOf("绑定菜鸟好友", "分享更方便", "分享本次卡片")
    /** v1.111 关闭按钮候选：X 按钮（content-desc 或 文本），「暂不绑定」不再作为关闭目标 */
    private val SHARE_CLOSE_HINTS = listOf("关闭", "取消", "iv_close")

    @Volatile
    var lastCainiaoLaunchAt: Long = 0L
        private set

    /** v1.91 最近一次成功点击确认的时间戳（冷启动补偿判断用） */
    @Volatile
    var lastConfirmedAt: Long = 0L
        private set

    /** v1.91 冷启动补偿已触发标志（同一跳转只补偿一次） */
    @Volatile
    private var compensated = false
    private var lastCompensatedAt = 0L
    private val COMPENSATE_INTERVAL_MS = 10_000L

    /** v1.109 详情页弹窗后扫：委托直达到达详情页后菜鸟 JS 检测剪贴板会再弹「是否要查询包裹」，
     * 到达判定后延迟扫描自动点掉（compensated=true 不再事件风暴，仅有限次后扫兜底弹窗出现时机差） */
    @Volatile
    private var allowPostDetailScan = false
    private var postDetailScanCount = 0

    @Volatile
    private var serviceRef: AccessibilityService? = null

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    /** v1.114 详情页展开验证重试计数（每次新跳转重置） */
    private var expandAttempt = 0
    private const val MAX_EXPAND_ATTEMPT = 2

    // v1.92 双写日志：logcat + HyperLog（App 运行日志页可见）
    // v1.104 自动携带 traceId：扫描/确认/到达全程带 tid，logcat grep tid 一条线
    private fun logI(msg: String) {
        Log.i(TAG, "$msg tid=${io.github.hypercopy.HyperLog.TraceContext.current}")
        io.github.hypercopy.HyperLog.d(TAG, "$msg tid=${io.github.hypercopy.HyperLog.TraceContext.current}")
    }
    private fun logW(msg: String) {
        Log.w(TAG, "$msg tid=${io.github.hypercopy.HyperLog.TraceContext.current}")
        io.github.hypercopy.HyperLog.w(TAG, "$msg tid=${io.github.hypercopy.HyperLog.TraceContext.current}")
    }

    /** 无障碍服务是否已连接（自愈检测用） */
    fun isAttached(): Boolean = serviceRef != null

    /** 无障碍服务连接时注入实例 */
    fun attach(service: AccessibilityService) {
        serviceRef = service
        logI("无障碍服务已连接")
    }

    /** v1.100 补偿前写回剪贴板用（跳转时剪贴板可能已被清空，菜鸟热启动检测需要单号在剪贴板） */
    @Volatile
    var lastTrackingNo: String? = null

        /** 菜鸟委托直达 URL 模板（v1.108 逆向实证：HomePageActivity entrust 机制 → Router → LogisticDetailActivity） */
    const val ENTRUST_URL_LOGISTIC = "guoguo://go/logistic?mailNo=%s&ld_type=query&querySourceId=68719476739"
    const val ENTRUST_EXTRA_URL = "url"
    const val ENTRUST_EXTRA_FROM = "from"
    const val ENTRUST_VALUE_FROM = "entrust"

    /**
     * v1.108 构造菜鸟委托直达 Intent（官方 entrust 机制）：
     * 显式 component 指向 HomePageActivity（launcher 入口 WelcomeActivity 不处理委托 extras，
     * 实测 shell/Shizuku 可启动 exported=false 的 LAUNCHER 类 Activity）
     * + extras{url: guoguo://go/logistic?mailNo=X, from: entrust}
     * → HomePageActivity.onCreate 的 startEntrustActivity → Router → LogisticDetailActivity 直达
     * 逆向实证：无需弹窗、无需无障碍扫描、cpCode 可选
     */
    fun buildCainiaoEntrustIntent(context: android.content.Context, trackingNo: String): android.content.Intent {
        val launch = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            component = android.content.ComponentName(
                CAINIAO_PACKAGE,
                "$CAINIAO_PACKAGE.homepage.view.activity.HomePageActivity",
            )
        }
        launch.putExtra(ENTRUST_EXTRA_URL, String.format(ENTRUST_URL_LOGISTIC, android.net.Uri.encode(trackingNo)))
        launch.putExtra(ENTRUST_EXTRA_FROM, ENTRUST_VALUE_FROM)
        return launch
    }

    /** 记录最近一次跳菜鸟并启动主动扫描 */
    fun markCainiaoLaunch(trackingNo: String? = null) {
        lastTrackingNo = trackingNo
        lastCainiaoLaunchAt = System.currentTimeMillis()
        retryCount = 0
        compensated = false
        // v1.109 重置详情页弹窗后扫标志（新一跳转重新允许）
        allowPostDetailScan = false
        postDetailScanCount = 0
        // v1.114 重置展开验证重试计数
        expandAttempt = 0
        logI("已标记菜鸟启动, 计划扫描 0.8/1.6/2.4s")
        // v1.105 菜鸟直达追踪块：单号 + 自动确认开关（直达详情页的必要条件）
        val autoConfirm = runCatching {
            SettingsRepository(serviceRef?.applicationContext ?: return@runCatching false).readCainiaoAutoConfirm()
        }.getOrDefault(false)
        io.github.hypercopy.TraceLogger.emit(buildString {
            appendLine(io.github.hypercopy.TraceLogger.begin(io.github.hypercopy.HyperLog.TraceContext.current, "菜鸟直达追踪"))
            appendLine(io.github.hypercopy.TraceLogger.line("单号", trackingNo ?: "-"))
            appendLine(io.github.hypercopy.TraceLogger.line("开关", "autoConfirm=${if (autoConfirm) "ON" else "OFF"} (OFF=弹窗需手动点)"))
        })
        scheduleScan(800L)
        scheduleScan(1600L)
        scheduleScan(2400L)
    }

    /** 事件通道 */
    fun onEvent(service: AccessibilityService, event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg != CAINIAO_PACKAGE) return
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            logI("事件 pkg=$pkg type=$type, 扫描中")
            // v1.141.46 事件通道兜底：节点树遍历中菜鸟 Weex 重绘可能 recycle 旧节点，
            // getChild/text 抛 IllegalStateException → 无保护会崩溃 AccessibilityService
            runCatching { scanOnce(service) }
                .onFailure { logW("事件扫描异常: ${it.message}") }
        }
    }

    private fun scheduleScan(delayMs: Long) {
        handler.postDelayed({
            // v1.141.46 主动扫描兜底：主线程 Handler 回调中节点树遍历异常会导致进程崩溃
            runCatching {
                val service = serviceRef ?: run {
                    logW("扫描跳过: 无障碍服务未连接")
                    return@runCatching
                }
                logI("计划扫描 +$delayMs ms")
                scanOnce(service)
            }.onFailure { logW("计划扫描异常: ${it.message}") }
        }, delayMs)
    }

    /** 单次扫描：按窗口包名匹配弹窗并点按钮 */
    private fun scanOnce(service: AccessibilityService) {
        val autoConfirm = SettingsRepository(service).readCainiaoAutoConfirm()
        val autoExpand = SettingsRepository(service).readCainiaoAutoExpand()
        // v1.109 双开关任一开启即扫描：autoConfirm 负责弹窗确认，autoExpand 负责详情页轨迹展开
        if (!autoConfirm && !autoExpand) {
            logI("扫描跳过: 自动确认与自动展开均未开启")
            return
        }
        // v1.100 已判定成功（详情页到达）或已补偿后不再扫描，防事件风暴空转
        // v1.109 例外：详情页到达后安排的一次性弹窗后扫（allowPostDetailScan）仍允许执行
        if (compensated && !allowPostDetailScan) return
        val now = System.currentTimeMillis()
        val inCainiaoWindow = now - lastCainiaoLaunchAt <= RECENT_WINDOW_MS
        if (!inCainiaoWindow) {
            retryCount = 0
            return
        }
        val root = service.rootInActiveWindow
        if (root == null) {
            // v1.96 窗口未就绪（冷启动加载中）不消耗重试次数，2s 后重扫
            logW("窗口加载中, 2秒后重扫")
            handler.postDelayed({ runCatching { scanOnce(service) } }, 2000L)
            return
        }
        val rootPkg = root.packageName?.toString().orEmpty()
        logI("扫描 根包=$rootPkg 菜鸟窗口=$inCainiaoWindow")
        val titleHints: List<String>
        val buttonHints: List<String>
        if (rootPkg != CAINIAO_PACKAGE) return
        titleHints = CAINIAO_TITLE_HINTS
        buttonHints = CAINIAO_BUTTON_HINTS
        // v1.110 分享弹窗优先处理：菜鸟自带「绑定菜鸟好友」分享弹窗会遮挡详情页
        // v1.111 修正：关闭目标从「暂不绑定」改为 X 关闭按钮（逆向实证暂不绑定无点击监听）
        // v1.113 移到 compensated 检查之前：详情页到达后用户点分享仍能自动关闭
        if (autoConfirm || autoExpand) {
            val shareNode = findNodeWithHint(root, SHARE_DIALOG_HINTS, 0)
            if (shareNode != null) {
                val shareContainer = shareNode.parent ?: root
                val closeBtn = findCloseButton(shareContainer)
                if (closeBtn != null) {
                    val ok = closeBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    logI(if (ok) "分享弹窗已自动关闭(点X)" else "分享弹窗关闭失败(点击返回false)")
                    return
                }
                logI("分享弹窗已出现但未找到关闭按钮, 交由详情页逻辑")
            }
        }
        // v1.100 已判定成功（详情页到达）或已补偿后不再扫描，防事件风暴空转
        // v1.109 例外：详情页到达后安排的一次性弹窗后扫（allowPostDetailScan）仍允许执行
        if (compensated && !allowPostDetailScan) return
        // v1.113 查询弹窗检测优先于详情页判定：
        // 弹窗自身含「顺丰速运 SF123456789012」单号文本，isCainiaoDetailPage 会误判"已到达"而不点弹窗
        if (autoConfirm) {
            val dialogNode = findNodeWithHint(root, titleHints, 0)
            if (dialogNode != null) {
                // v1.100 修复：按钮是标题的兄弟节点（同一 dialog 容器内），从容器节点向下找
                val dialogContainer = dialogNode.parent ?: root
                val button = findClickableButton(dialogContainer, buttonHints, 0)
                if (button == null) {
                    logW("未找到确认按钮, 重试 ${retryCount + 1}/$MAX_RETRY")
                    dumpTopNodes(root, 12)
                    scheduleRetry(service)
                    return
                }
                val clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) {
                    retryCount = 0
                    lastConfirmedAt = System.currentTimeMillis()
                    // v1.104 输出弹窗确认耗时（自 markCainiaoLaunch 起）
                    logI("弹窗已自动确认($rootPkg) 耗时=${lastConfirmedAt - lastCainiaoLaunchAt}ms")
                    // v1.141.51 修复：弹窗确认（点"立即查看"）会触发菜鸟重新导航/Weex 重绘，
                    // 详情页展开状态丢失（收起）——20:49 测试实锤：展开验证通过后弹窗确认 → 又收起。
                    // 延迟 1.2s 等页面稳定后重新扫描展开（新一次展开流程，重置验证重试计数）。
                    if (autoExpand) {
                        expandAttempt = 0
                        handler.postDelayed({
                            runCatching {
                                val svc = serviceRef ?: return@postDelayed
                                val rootNow = svc.rootInActiveWindow ?: return@postDelayed
                                if (rootNow.packageName?.toString() == CAINIAO_PACKAGE) {
                                    logI("弹窗确认后重新展开(防收起)")
                                    autoExpandDetail(rootNow)
                                }
                            }
                        }, 1200L)
                    }
                } else {
                    logW("点击失败, 重试 ${retryCount + 1}/$MAX_RETRY")
                    scheduleRetry(service)
                }
                return
            }
        }
        // v1.137 业务提示弹窗检测：菜鸟「温馨提示/系统繁忙/暂无物流」等业务提示
        // （非可确认弹窗，无需点击）。识别到即页面已就绪——标记完成停止扫描，
        // 避免 3.5s 宽限期后触发冷启动补偿误重启菜鸟（用户日志实锤该场景触发扫描风暴）
        if (autoConfirm || autoExpand) {
            val ignoreNode = findNodeWithHint(root, CAINIAO_IGNORE_HINTS, 0)
            if (ignoreNode != null) {
                retryCount = 0
                compensated = true
                logI("检测到业务提示弹窗(如系统繁忙), 停止扫描不再补偿")
                return
            }
        }
        // v1.109 详情页判定（autoExpand 目标）：委托直达已到详情页时无需再找弹窗
        if (isCainiaoDetailPage(root)) {
            retryCount = 0
            compensated = true
            // v1.104 输出到达详情页总耗时（自 markCainiaoLaunch 起）
            logI("已到达详情页(自动), 停止扫描 耗时=${System.currentTimeMillis() - lastCainiaoLaunchAt}ms")
            // v1.130 直达成功清剪贴板：防残留单号被后续浮动窗口/无障碍嗅探重复触发跳转
            // （用户日志实锤：21:03 残留 YTO9876543210123 在桌面被嗅探再次跳菜鸟）
            // entrust 用 extras 传单号不依赖剪贴板，清空不影响直达链路
            clearClipboardAfterSuccess(service)
            if (autoExpand) autoExpandDetail(root)
            // v1.109 详情页弹窗后扫：菜鸟 JS 检测剪贴板会再弹「是否要查询包裹」，延迟扫描自动点掉
            if (autoConfirm && postDetailScanCount < POST_DETAIL_SCAN_MAX) {
                postDetailScanCount++
                handler.postDelayed({
                    allowPostDetailScan = true
                    runCatching { scanOnce(service) }
                    allowPostDetailScan = false
                }, POST_DETAIL_SCAN_DELAY_MS)
            }
            return
        }
        // v1.109 仅自动确认开启时才继续找弹窗（autoExpand-only 场景不弹窗确认）
        if (!autoConfirm) return
        logW("未找到弹窗标题($rootPkg), 重试 ${retryCount + 1}/$MAX_RETRY")
        dumpTopNodes(root, 12)
        scheduleRetry(service)
    }

    private fun scheduleRetry(service: AccessibilityService) {
        if (retryCount >= MAX_RETRY) {
            // v1.137 修复：计数重置移到宽限期判断之后（原实现先重置计数再判宽限，
            // 导致宽限期内"1.5s后重扫"后计数从 0 重新累计 → 无限循环刷日志/高频扫描，
            // 用户日志实锤 38s 内数百次扫描+节点 dump 风暴）
            // v1.113 补偿宽限期：热启动委托跳转需 Weex 渲染 2-4s，
            // 启动后 COMPENSATE_GRACE_MILLIS 内不触发补偿（避免打断正在进行的直达跳转造成"二次进入"）
            val elapsedSinceLaunch = System.currentTimeMillis() - lastCainiaoLaunchAt
            if (elapsedSinceLaunch < COMPENSATE_GRACE_MILLIS) {
                retryCount = 0
                // v1.137 修复：宽限期内不再主动重扫（原实现 1.5s 后重扫）。
                // Weex 渲染完成/弹窗出现会派发无障碍事件，事件通道 onEvent 会自然触发重扫。
                logW("补偿宽限中(启动 ${elapsedSinceLaunch}ms < ${COMPENSATE_GRACE_MILLIS}ms), 停止主动扫描等待事件")
                return
            }
            retryCount = 0
            // v1.100 诊断：补偿条件不满足时打印原因（避免静默跳过无法定位）
            val nowMs = System.currentTimeMillis()
            val condWindow = nowMs - lastCainiaoLaunchAt <= RECENT_WINDOW_MS + 2000L
            val condConfirmed = nowMs - lastConfirmedAt > 1500L
            val condInterval = nowMs - lastCompensatedAt > COMPENSATE_INTERVAL_MS
            if (!(!compensated && condWindow && condConfirmed && condInterval)) {
                logW("补偿跳过: 已补偿=$compensated 窗口=${nowMs - lastCainiaoLaunchAt}ms 已确认=${nowMs - lastConfirmedAt}ms 间隔=${nowMs - lastCompensatedAt}ms")
            }
            // v1.91 冷启动补偿：菜鸟冷启动不弹检测窗，自动 HOME+重开触发热启动检测
            if (!compensated &&
                System.currentTimeMillis() - lastCainiaoLaunchAt <= RECENT_WINDOW_MS + 2000L &&
                System.currentTimeMillis() - lastConfirmedAt > 1500L &&
                System.currentTimeMillis() - lastCompensatedAt > COMPENSATE_INTERVAL_MS
            ) {
                compensated = true
                logI("冷启动补偿: 重启菜鸟(热重启,不按HOME)")
                handler.postDelayed({ runCatching { coldStartCompensate(service) } }, 500L)
            }
            return
        }
        retryCount++
        handler.postDelayed({ runCatching { scanOnce(service) } }, RETRY_DELAY_MS)
    }

    /** v1.130 直达成功清剪贴板：防残留单号被后续嗅探重复触发跳转（仅当剪贴板仍是本次单号时清空） */
    private fun clearClipboardAfterSuccess(service: AccessibilityService?) {
        val trackingNo = io.github.hypercopy.clipboard.handling.ClipboardTextHandler.lastProcessedText
        if (trackingNo.isNullOrBlank()) return
        runCatching {
            val cm = service?.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
            val current = cm.primaryClip?.getItemAt(0)?.text?.toString()
            if (current == trackingNo) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                logI("直达成功已清剪贴板(防残留重复触发): $trackingNo")
            }
        }.onFailure { logW("清剪贴板失败: ${it.message}") }
    }
    /** v1.96 冷启动补偿：直接热启动重开菜鸟（不 HOME，用户无感；后台热拉起触发剪贴板检测） */
    private fun coldStartCompensate(service: AccessibilityService) {
        lastCompensatedAt = System.currentTimeMillis()
        // v1.100 写回单号到剪贴板：跳转后剪贴板可能已被清空，菜鸟热启动检测需要单号在剪贴板
        val trackingNo = lastTrackingNo
        if (!trackingNo.isNullOrBlank()) {
            runCatching {
                val cm = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("hypercopy-compensate", trackingNo))
                // v1.141.48 修复：写回必须记录防抖，否则浮动窗口/嗅探回读写回的单号
                // 会再次命中规则触发跳转 → 补偿-写回-再跳转 死循环（19:33 日志实锤 3 次重复跳转）
                io.github.hypercopy.clipboard.handling.ClipboardWriteGuard.record(trackingNo)
                logI("剪贴板已写回单号: $trackingNo (供菜鸟检测, 已登记防抖)")
            }.onFailure { logW("剪贴板写回失败: ${it.message}") }
        }
        val intent = android.content.Intent(
            android.content.Intent.ACTION_MAIN
        ).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            .setPackage(CAINIAO_PACKAGE)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = io.github.hypercopy.clipboard.privileged.ShizukuActivityLauncher.launch(intent)
        logI("补偿重启菜鸟 ok=$ok")
        // 重开菜鸟后重置窗口并继续扫描（v1.100 补偿≠成功：重置 compensated 允许后续弹窗确认扫描）
        lastCainiaoLaunchAt = System.currentTimeMillis()
        compensated = false
        retryCount = 0
        handler.postDelayed({ runCatching { scanOnce(service) } }, 1200L)
    }

    /** v1.141.46 安全获取子节点：菜鸟 Weex 重绘可能 recycle 旧节点，getChild 抛 IllegalStateException。
     * 单节点失效只跳过该分支，不中断整棵节点树扫描。 */
    private fun safeChild(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? =
        runCatching { node.getChild(index) }.getOrNull()

    private fun findNodeWithHint(node: AccessibilityNodeInfo, hints: List<String>, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        val text = node.text?.toString().orEmpty() + node.contentDescription?.toString().orEmpty()
        if (text.isNotBlank() && hints.any { text.contains(it) }) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = safeChild(node, i) ?: continue
            findNodeWithHint(child, hints, depth + 1)?.let { return it }
        }
        return null
    }

    private fun findClickableButton(node: AccessibilityNodeInfo, hints: List<String>, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        val text = node.text?.toString().orEmpty() + node.contentDescription?.toString().orEmpty()
        if (text.isNotBlank() && hints.any { text.contains(it) }) {
            if (node.isClickable) return node
            var parent = node.parent
            var up = 0
            while (parent != null && up < 6) {
                if (parent.isClickable) return parent
                parent = parent.parent
                up++
            }
        }
        for (i in 0 until node.childCount) {
            val child = safeChild(node, i) ?: continue
            findClickableButton(child, hints, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * v1.111 找分享弹窗关闭按钮（X）：逆向实证 PackageShareDialog 的 iv_close ImageView
     * → onClick → dismissAllowingStateLoss()。「暂不绑定」文本无点击监听不可用。
     * 匹配策略：①contentDescription 含「关闭/取消」 ②clazz 含 ImageView 且可点（X 图标）
     * ③兜底：容器内第一个可点击节点（弹窗内 X 通常最先渲染）。
     */
    private fun findCloseButton(container: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 策略1：文本/描述含关闭类关键词
        findNodeWithHint(container, SHARE_CLOSE_HINTS, 0)?.let { hintNode ->
            if (hintNode.isClickable) return hintNode
            var parent = hintNode.parent
            var up = 0
            while (parent != null && up < 6) {
                if (parent.isClickable) return parent
                parent = parent.parent
                up++
            }
        }
        // 策略2：ImageView 且可点击（X 关闭按钮）
        val imgClickable = findClickableImageView(container, 0)
        if (imgClickable != null) return imgClickable
        // 不启用兜底策略：找不到关闭按钮宁可跳过（避免误点微信邀请跳转）
        return null
    }

    private fun findClickableImageView(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        val clazz = node.className?.toString().orEmpty()
        if (clazz.contains("ImageView", ignoreCase = true) && node.isClickable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = safeChild(node, i) ?: continue
            findClickableImageView(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun findFirstClickable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        if (node.isClickable) return node
        for (i in 0 until node.childCount) {
            val child = safeChild(node, i) ?: continue
            findFirstClickable(child, depth + 1)?.let { return it }
        }
        return null
    }

    /** v1.100 详情页识别：节点含「物流公司+运单号」（如 顺丰速运 SF123456789012）即视为已到达详情页 */
    private fun isCainiaoDetailPage(root: AccessibilityNodeInfo): Boolean {
        val detailRe = Regex("(顺丰|圆通|中通|申通|韵达|极兔|京东|EMS|邮政|德邦|丹鸟).{0,6}[A-Za-z]{0,4}\\d{8,}")
        var found = false
        fun walk(node: AccessibilityNodeInfo) {
            if (found) return
            val text = node.text?.toString().orEmpty()
            if (text.isNotEmpty() && detailRe.containsMatchIn(text)) {
                found = true
                return
            }
            for (i in 0 until node.childCount) {
                runCatching { walk(node.getChild(i)) }.getOrDefault(Unit)
            }
        }
        runCatching { walk(root) }.getOrDefault(Unit)
        return found
    }

    /**
     * v1.109 详情页自动展开：到达物流详情页后查找「展开」按钮并点击，
     * 展示完整物流轨迹（默认折叠为最近几条，Weex 渲染无 URL 展开参数，只能模拟点击）。
     * 兼容「展开」「展开v」「展开全部」等文本；点击失败静默不重试（详情页已到达，展开属增强体验）。
     * v1.114 增强：点击后延迟验证重试——Weex 页面渲染中点击可能落空（日志显示已点击但实际没展开），
     * 1.5s 后复查若「展开」按钮仍在（说明没点中/页面重绘）再点，最多 MAX_EXPAND_ATTEMPT 次。
     */
    private fun autoExpandDetail(root: AccessibilityNodeInfo) {
        val expandNode = findNodeWithHint(root, listOf("展开"), 0)
        if (expandNode == null) {
            logI("详情页自动展开: 未找到展开按钮(可能已展开)")
            return
        }
        var target = expandNode
        if (!target.isClickable) {
            var parent = target.parent
            var up = 0
            while (parent != null && up < 6) {
                if (parent.isClickable) {
                    target = parent
                    break
                }
                parent = parent.parent
                up++
            }
        }
        val clicked = target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        logI(if (clicked) "详情页自动展开: 已点击展开按钮(第${expandAttempt + 1}次)" else "详情页自动展开: 点击失败(节点不可点)")
        // v1.114 点击后验证：Weex 渲染中点击可能落空，1.5s 后复查展开按钮是否仍在
        if (clicked && expandAttempt < MAX_EXPAND_ATTEMPT) {
            expandAttempt++
            handler.postDelayed({
                runCatching {
                    val service = serviceRef ?: return@postDelayed
                    val rootNow = service.rootInActiveWindow ?: return@postDelayed
                    val stillThere = findNodeWithHint(rootNow, listOf("展开"), 0)
                    if (stillThere != null) {
                        logI("详情页自动展开: 验证发现展开按钮仍在(可能未展开), 重试第${expandAttempt + 1}次")
                        autoExpandDetail(rootNow)
                    } else {
                        logI("详情页自动展开: 验证通过(展开按钮已消失)")
                    }
                }
            }, 1500L)
        }
    }
    private fun dumpTopNodes(node: AccessibilityNodeInfo, count: Int) {
        var n = 0
        fun walk(current: AccessibilityNodeInfo, depth: Int) {
            if (n >= count || depth > 6) return
            val t = current.text?.toString().orEmpty()
            val d = current.contentDescription?.toString().orEmpty()
            if (t.isNotBlank() || d.isNotBlank()) {
                logI("  节点[d$depth] 可点=${current.isClickable} 文本='$t' 描述='$d'")
                n++
            }
            for (i in 0 until current.childCount) {
                val c = safeChild(current, i) ?: continue
                walk(c, depth + 1)
            }
        }
        walk(node, 0)
    }
}
