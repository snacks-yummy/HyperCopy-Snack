package io.github.hypercopy.clipboard.handling

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import io.github.hypercopy.Config
import io.github.hypercopy.HyperLog
import io.github.hypercopy.clipboard.monitor.ClipboardFocusRequester
import io.github.hypercopy.clipboard.privileged.ShizukuClipboardReader
import io.github.hypercopy.data.settings.SettingsRepository

class ClipboardFloatingActivity : Activity() {
    private var handled = false
    private var readAttempts = 0
    private val handler = Handler(Looper.getMainLooper())
    // v1.139 键盘保护：记录启动前输入法是否活跃（键盘打开），finish 后尝试恢复
    private var wasImeActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HyperLog.d(TAG, "浮动窗口创建")
        if (!consumeStartToken()) {
            finish()
            return
        }
        if (SettingsRepository(applicationContext).readClipboardMonitorMode() != Config.CLIPBOARD_MONITOR_MODE_SHIZUKU) {
            finish()
            return
        }
        // v1.139.1 键盘保护：读取 request 时（焦点在用户 App）记录的输入法状态
        //（onCreate 时焦点已切换，imm.isActive 不可靠 → 由 ClipboardFocusRequester 前置记录）
        wasImeActive = ClipboardFocusRequester.wasImeActiveBeforeRequest()
        HyperLog.d(TAG, "启动前键盘活跃=$wasImeActive")
        window.setBackgroundDrawableResource(android.R.color.transparent)
        val params = window.attributes
        params.dimAmount = 0f
        params.flags = params.flags or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        window.attributes = params
        // v1.139.3 超时兜底：onWindowFocusChanged 可能被系统打断不触发（版本重绑/焦点竞态），
        // 3s 未处理 → Shizuku 特权读剪贴板直接处理（不依赖前台焦点）
        handler.postDelayed({
            if (handled) return@postDelayed
            HyperLog.d(TAG, "浮动窗口超时兜底触发(3s)")
            if (fallbackShizukuRead()) return@postDelayed
            // v1.140.4 重试机制（最多2次）：HyperOS 弹窗限制常为时机性偶发，
            // 无焦点时重新抢焦点读取（次数+1），2次后仍失败才通知用户
            val retryCount = intent.getIntExtra(ClipboardFocusRequester.EXTRA_RETRY, 0)
            if (retryCount < 2) {
                handled = true
                finishWithoutAnimation()
                HyperLog.d(TAG, "浮动窗口无焦点(第${retryCount + 1}次), 1s 后重试抢焦点读取")
                handler.postDelayed({
                    ClipboardFocusRequester.request(applicationContext, force = true, retry = retryCount + 1)
                }, 1000L)
                return@postDelayed
            }
            // 重试耗尽 → 通知用户点击处理
            handled = true
            ClipboardFocusRequester.markFailed()
            finishWithoutAnimation()
            HyperLog.d(TAG, "浮动窗口3次尝试均无焦点, 通知用户点击处理")
            runCatching { ClipboardFallbackNotifier.notify(applicationContext) }
                .onFailure { HyperLog.d(TAG, "兜底通知失败", it) }
        }, FALLBACK_TIMEOUT_MILLIS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        HyperLog.d(TAG, "浮动窗口焦点=$hasFocus")
        if (!hasFocus) return
        when (intent.getStringExtra(ClipboardFocusRequester.EXTRA_ACTION)) {
            ClipboardFocusRequester.ACTION_CLEAR_CLIPBOARD -> clearClipboardAndFinish()
            else -> readClipboardAndFinish()
        }
    }
    private fun consumeStartToken(): Boolean {
        val token = intent.getStringExtra(ClipboardFocusRequester.EXTRA_START_TOKEN)
        return when (intent.getStringExtra(ClipboardFocusRequester.EXTRA_ACTION)) {
            ClipboardFocusRequester.ACTION_CLEAR_CLIPBOARD -> ClipboardFocusRequester.isPendingClearToken(token)
            else -> ClipboardFocusRequester.consumeToken(token)
        }
    }
    private fun readClipboardAndFinish() {
        if (handled) return
        val text = readPrimaryText()
        if (!text.isNullOrBlank()) {
            handled = true
            HyperLog.d(TAG, "浮动读取成功 len=${text.length}")
            finishWithoutAnimation()
            restoreImeIfWasActive()
            Handler(Looper.getMainLooper()).postDelayed({
                ClipboardTextHandler.handle(applicationContext, text, intent.getStringExtra(ClipboardFocusRequester.EXTRA_SOURCE_PACKAGE).orEmpty())
            }, HANDLE_AFTER_FINISH_DELAY_MILLIS)
            return
        }
        // v1.48 HyperOS 焦点读取竞态：onWindowFocusChanged 时 WindowManager 的
        // focused 状态可能尚未更新，getPrimaryClip 被拒（Denying）→ 延迟重试等状态稳定
        readAttempts++
        HyperLog.d(TAG, "浮动读取第${readAttempts}次为空")
        if (readAttempts < MAX_READ_ATTEMPTS) {
            handler.postDelayed({ readClipboardAndFinish() }, RETRY_DELAY_MILLIS)
            return
        }
        handled = true
        HyperLog.d(TAG, "浮动读取失败(尝试${readAttempts}次)")
        // v1.139.3 兜底升级：先 Shizuku 特权读剪贴板，成功直接处理；失败再通知点击
        if (fallbackShizukuRead()) return
        // v1.80 误触修复：读取彻底失败 → 记录失败冷却，阻止 Denying 自我循环反复抢焦点
        ClipboardFocusRequester.markFailed()
        finishWithoutAnimation()
        // v1.50 免 root 兜底：抢焦点读取彻底失败 → 通知用户点击处理
        // （点击后 MainActivity 前台读取成功，焦点规则允许）
        runCatching {
            ClipboardFallbackNotifier.notify(applicationContext)
        }.onFailure { HyperLog.d(TAG, "兜底通知失败", it) }
    }
    private fun clearClipboardAndFinish() {
        if (handled) return
        handled = true
        val cleared = runCatching {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }.isSuccess
        finishWithoutAnimation()
        // v1.141.62 修复：先 finish 再延迟回调（回调里 launch 目标 App），
        // 避免 FloatingActivity 前台时 startActivity(目标) 被随后 finish 打断 → 启动被吞卡住
        //（00:41 淘宝链接场景实锤：清剪贴板→launch→"启动成功"但画面卡住未跳转）
        Handler(Looper.getMainLooper()).postDelayed({
            ClipboardFocusRequester.consumeClearToken(
                intent.getStringExtra(ClipboardFocusRequester.EXTRA_START_TOKEN),
                cleared,
            )
        }, LAUNCH_AFTER_FINISH_DELAY_MILLIS)
    }

    private fun finishWithoutAnimation() {
        overridePendingTransition(0, 0)
        moveTaskToBack(true)
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }

    /** v1.139 键盘保护：浮动窗口抢焦点导致输入法收起，读取完成后尝试恢复键盘 */
    private fun restoreImeIfWasActive() {
        if (!wasImeActive) return
        handler.postDelayed({
            runCatching {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                // v1.139.1 状态判断：键盘已自动恢复则不动（避免 toggleSoftInput 反向关闭）
                if (imm.isActive) {
                    HyperLog.d(TAG, "键盘仍活跃,无需恢复")
                    return@runCatching
                }
                // 焦点已回到原 App（输入框），SHOW_IMPLICIT 温和弹出键盘
                imm.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT, 0)
                HyperLog.d(TAG, "已尝试恢复输入法(键盘)")
            }.onFailure { HyperLog.d(TAG, "恢复输入法失败: ${it.message}") }
        }, RESTORE_IME_DELAY_MILLIS)
    }

    /**
     * v1.139.3 Shizuku 特权读剪贴板兜底；成功=已处理（含 finish），失败返回 false
     */
    private fun fallbackShizukuRead(): Boolean {
        val shizukuText = ShizukuClipboardReader.read()
        if (shizukuText.isNullOrBlank()) return false
        handled = true
        val sourcePackage = intent.getStringExtra(ClipboardFocusRequester.EXTRA_SOURCE_PACKAGE).orEmpty()
        finishWithoutAnimation()
        restoreImeIfWasActive()
        Handler(Looper.getMainLooper()).postDelayed({
            ClipboardTextHandler.handle(applicationContext, shizukuText, sourcePackage)
        }, HANDLE_AFTER_FINISH_DELAY_MILLIS)
        HyperLog.d(TAG, "Shizuku 兜底读取成功, 已提交处理 len=${shizukuText.length}")
        return true
    }

    private fun readPrimaryText(): String? {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip ?: return null
        return clip.firstTextItem()?.coerceToText(this)?.toString()
    }

    private fun ClipData.firstTextItem(): ClipData.Item? {
        if (itemCount <= 0) return null
        return getItemAt(0)
    }

    private companion object {
        const val TAG = "悬浮窗"
        // v1.139.3 读取超时兜底阈值
        const val FALLBACK_TIMEOUT_MILLIS = 3000L
        // v1.140.14 优化：120→80ms。实测读取成功→焦点释放回调 ~60ms（finish 完成），
        // 80ms 保留 20ms 余量确保 Activity stop 完成后再提交处理，全链路 -40ms；
        // 若真机出现跳转后目标 App 未置前/闪屏，回滚 120ms 即可
        const val HANDLE_AFTER_FINISH_DELAY_MILLIS = 80L
        // v1.141.62 清剪贴板后延迟 launch 目标 App 的等待时间（等 FloatingActivity 完全 finish，
        // 焦点回到源 App 后再启动目标，避免前台竞态吞启动；< 超时保护 1200ms）
        const val LAUNCH_AFTER_FINISH_DELAY_MILLIS = 300L
        // v1.48 焦点读取竞态重试：间隔 300ms 等 WindowManager 焦点状态稳定
        const val MAX_READ_ATTEMPTS = 4
        const val RETRY_DELAY_MILLIS = 300L
        // v1.139 键盘恢复延迟：等 Activity 切换动画/焦点交还原 App 后再弹键盘
        const val RESTORE_IME_DELAY_MILLIS = 350L
    }
}
