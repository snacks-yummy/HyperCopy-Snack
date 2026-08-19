package io.github.hypercopy.clipboard.monitor

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.hypercopy.data.settings.SettingsRepository

/**
 * v1.141.63 淘宝口令弹窗自动确认（无障碍辅助，非规则检测）：
 * 口令规则跳转仅打开淘宝主页（target.template 空 → getLaunchIntentForPackage），
 * 淘宝主页检测剪贴板口令 → NewTaoPasswordDialog 弹「查看详情/打开/进入店铺」（apktool 实证
 * clip_taopassword_ui.xml：tpd_item_action/tpd_common_action/tpd_shop_action）→ 必须手动点击。
 * 本监听在 HyperCopy 跳转淘宝后 8s 窗口内自动点击主按钮（标准 Dialog + 标准 TextView，可读可点）。
 * 通道：事件触发 + mark 后 0.8/1.6/2.4s 主动扫描（对齐 CainiaoAutoConfirm 模式）。
 * 防误触：仅 markTaobaoLaunch 后窗口内生效（手动打开淘宝不触发）+ 点击成功即停（confirmed）+ 排除「关闭」。
 */
object TaobaoKoulingConfirm {
    private const val TAG = "HyperCopyA11y"
    const val TAOBAO_PACKAGE = "com.taobao.taobao"
    /** 跳转后扫描窗口：淘宝冷启动+Weex 渲染 2-5s，窗口放宽到 12s（17:25 实测弹窗晚于 2.4s 计划扫描出现） */
    private const val RECENT_WINDOW_MS = 12_000L
    private const val MAX_DEPTH = 40
    private const val MAX_WINDOW_RETRY = 2
    /** 扫描限频：防 WINDOW_CONTENT_CHANGED 事件风暴（17:20 实测 3s 30+ 次扫描） */
    private const val SCAN_MIN_INTERVAL_MS = 200L
    /** apktool 实证按钮文本：tpd_item_action=查看详情 / tpd_common_action=打开 / tpd_shop_action=进入店铺 */
    private val BUTTON_HINTS = listOf("查看详情", "打开", "进入店铺")
    /** 排除词：弹窗内「关闭」X 按钮不可作为确认目标 */
    private val EXCLUDE_HINTS = listOf("关闭", "取消")

    @Volatile
    var lastTaobaoLaunchAt: Long = 0L
        private set
    /** 最近一次成功点击确认的时间戳（防误触间隔判断用） */
    @Volatile
    var lastConfirmedAt: Long = 0L
        private set
    /** 同一次跳转只点击一次（点击成功即停，防事件风暴重复点） */
    @Volatile
    private var confirmed = false
    /** 窗口未就绪重扫计数（防无限 2s 重扫） */
    private var windowRetryCount = 0
    /** 上次扫描时间戳（限频） */
    @Volatile
    private var lastScanAt = 0L
    @Volatile
    private var serviceRef: AccessibilityService? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun logI(msg: String) {
        Log.i(TAG, "$msg tid=${io.github.hypercopy.HyperLog.TraceContext.current}")
        io.github.hypercopy.HyperLog.d(TAG, "$msg tid=${io.github.hypercopy.HyperLog.TraceContext.current}")
    }
    private fun logW(msg: String) {
        Log.w(TAG, "$msg tid=${io.github.hypercopy.HyperLog.TraceContext.current}")
        io.github.hypercopy.HyperLog.w(TAG, "$msg tid=${io.github.hypercopy.HyperLog.TraceContext.current}")
    }
    /** 无障碍服务是否已连接 */
    fun isAttached(): Boolean = serviceRef != null
    /** 无障碍服务连接时注入实例（ClipboardAccessibilityService.onServiceConnected） */
    fun attach(service: AccessibilityService) {
        serviceRef = service
        logI("淘宝口令确认监听已连接")
    }
    /** HyperCopy 跳转淘宝后标记启动主动扫描（PendingJumpCoordinator hook） */
    fun markTaobaoLaunch() {
        lastTaobaoLaunchAt = System.currentTimeMillis()
        confirmed = false
        windowRetryCount = 0
        lastScanAt = 0L
        logI("已标记淘宝跳转, 计划扫描 0.8/1.6/2.4s")
        scheduleScan(800L)
        scheduleScan(1600L)
        scheduleScan(2400L)
        scheduleScan(3600L)
        scheduleScan(4800L)
        scheduleScan(6000L)
    }
    /** 事件通道：淘宝窗口变化时扫描（对齐 CainiaoAutoConfirm.onEvent） */
    fun onEvent(service: AccessibilityService, event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg != TAOBAO_PACKAGE) return
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            runCatching { scanOnce(service) }
                .onFailure { logW("事件扫描异常: ${it.message}") }
        }
    }
    private fun scheduleScan(delayMs: Long) {
        handler.postDelayed({
            runCatching {
                val service = serviceRef ?: run {
                    logW("扫描跳过: 无障碍服务未连接")
                    return@runCatching
                }
                scanOnce(service)
            }.onFailure { logW("计划扫描异常: ${it.message}") }
        }, delayMs)
    }
    /** 单次扫描：窗口内匹配淘宝口令弹窗主按钮并点击 */
    private fun scanOnce(service: AccessibilityService) {
        // 限频：事件通道风暴时合并扫描（WINDOW_CONTENT_CHANGED 高频派发）
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastScanAt < SCAN_MIN_INTERVAL_MS) return
        lastScanAt = nowMs
        val enabled = runCatching {
            SettingsRepository(service).readTaobaoKoulingConfirm()
        }.getOrDefault(true)
        if (!enabled) {
            logW("扫描跳过: 淘宝口令自动确认开关已关闭")
            return
        }
        if (confirmed) return
        val now = System.currentTimeMillis()
        if (now - lastTaobaoLaunchAt > RECENT_WINDOW_MS) return
        val root = service.rootInActiveWindow
        if (root == null) {
            // 窗口未就绪（冷启动加载中）：最多重扫 2 次，之后依赖事件通道
            if (windowRetryCount < MAX_WINDOW_RETRY) {
                windowRetryCount++
                logW("淘宝窗口加载中, 2秒后重扫(${windowRetryCount}/$MAX_WINDOW_RETRY)")
                handler.postDelayed({ runCatching { scanOnce(service) } }, 2000L)
            }
            return
        }
        val rootPkg = root.packageName?.toString().orEmpty()
        if (rootPkg == TAOBAO_PACKAGE) {
            val button = findClickableButton(root, BUTTON_HINTS, 0)
            if (button != null) {
                performConfirmClick(button, "活动窗口")
                return
            }
        }
        // v1.141.67 弹窗可能是独立 window（rootInActiveWindow 只返回首页，17:31 dump 实锤）：
        // 遍历所有无障碍窗口找弹窗（Dialog/悬浮窗会作为独立 window 出现在 windows 列表）
        val windows = runCatching { service.windows ?: emptyList() }.getOrDefault(emptyList())
        if (windows.isNotEmpty()) {
            var taobaoWindows = 0
            for (win in windows) {
                val winRoot = runCatching { win.root }.getOrNull() ?: continue
                val winPkg = winRoot.packageName?.toString().orEmpty()
                if (winPkg != TAOBAO_PACKAGE) continue
                taobaoWindows++
                val btn = findClickableButton(winRoot, BUTTON_HINTS, 0)
                if (btn != null) {
                    performConfirmClick(btn, "窗口=${win.title}")
                    return
                }
            }
            logW("已遍历${windows.size}个窗口(淘宝${taobaoWindows}个), 均未找到确认按钮, dump:")
            for (win in windows) {
                val winRoot = runCatching { win.root }.getOrNull() ?: continue
                if (winRoot.packageName?.toString() != TAOBAO_PACKAGE) continue
                logI("  [window] title=${win.title} pkg=${winRoot.packageName} type=${win.type}")
                dumpTopNodes(winRoot, 8)
            }
            return
        }
        logW("未找到淘宝口令确认按钮(活动窗口+windows列表), dump活动窗口:")
        dumpTopNodes(root, 15)
    }
    /** 点击确认按钮并记录（成功即停防重复） */
    private fun performConfirmClick(button: AccessibilityNodeInfo, where: String) {
        val clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            confirmed = true
            lastConfirmedAt = System.currentTimeMillis()
            logI("淘宝口令弹窗已自动确认($where) 耗时=${lastConfirmedAt - lastTaobaoLaunchAt}ms")
        } else {
            logW("淘宝口令弹窗点击失败(节点不可点, $where)")
        }
    }
    /** 安全获取子节点：Weex 重绘可能 recycle 旧节点，getChild 抛 IllegalStateException（对齐菜鸟 v1.141.46） */
    private fun safeChild(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? =
        runCatching { node.getChild(index) }.getOrNull()
    /** 找含特征文本的可点击节点：命中按钮特征词且不含排除词；文本节点不可点时向上找可点祖先 */
    private fun findClickableButton(node: AccessibilityNodeInfo, hints: List<String>, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        val text = node.text?.toString().orEmpty() + node.contentDescription?.toString().orEmpty()
        if (text.isNotBlank() &&
            hints.any { text.contains(it) } &&
            EXCLUDE_HINTS.none { text.contains(it) }
        ) {
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
    /** 诊断：输出节点树前 N 个文本/可点节点（定位弹窗节点不可读问题） */
    private fun dumpTopNodes(node: AccessibilityNodeInfo, count: Int) {
        var n = 0
        fun walk(current: AccessibilityNodeInfo, depth: Int) {
            if (n >= count || depth > 8) return
            val t = current.text?.toString().orEmpty()
            val d = current.contentDescription?.toString().orEmpty()
            val c2 = current.className?.toString().orEmpty()
            if (t.isNotBlank() || d.isNotBlank() || current.isClickable) {
                logI("  节点[d$depth] 可点=${current.isClickable} 类=$c2 文本='$t' 描述='$d'")
                n++
            }
            for (i in 0 until current.childCount) {
                val ch = safeChild(current, i) ?: continue
                walk(ch, depth + 1)
            }
        }
        walk(node, 0)
    }
}