package io.github.hypercopy.clipboard.monitor

import android.content.ClipboardManager
import android.content.Context
import io.github.hypercopy.HyperLog

class ClipboardChangeProbe(context: Context) {
    private val appContext = context.applicationContext
    private val clipboardManager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        HyperLog.d(TAG, "剪贴板变化探针已通知")
        // v1.140.11 恢复原版行为：读取 primaryClip 制造 ClipboardService 日志信号
        // （后台读取被拒 → 系统打 Denying E 日志 → logcat 流式检测器捕获 → 抢焦点跳转）
        // 仅变化时读一次，无持续噪音；读成功(前台)亦无害
        runCatching { clipboardManager.primaryClip }
    }

    fun start() {
        clipboardManager.addPrimaryClipChangedListener(listener)
    }

    fun stop() {
        clipboardManager.removePrimaryClipChangedListener(listener)
    }

    private companion object {
        const val TAG = "HyperCopy"
    }
}
