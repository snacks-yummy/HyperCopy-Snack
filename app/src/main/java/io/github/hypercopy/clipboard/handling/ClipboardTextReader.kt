package io.github.hypercopy.clipboard.handling

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.hypercopy.HyperLog

/**
 * 剪贴板文本读取器（带延迟+重试）。
 *
 * 背景：Shizuku 模式下复制内容时，HyperCopy 的透明悬浮 Activity 会抢前台焦点读取剪贴板。
 * Android13+ 剪贴板访问要求调用方处于前台且有焦点，若用户紧接着在本 App 内点"从剪贴板粘贴/添加"，
 * 悬浮 Activity 可能尚未退出/焦点未恢复，导致一次性读取返回空/旧内容。
 *
 * 解决：点击后延迟一小段时间再读（避开悬浮 Activity 窗口期），读取失败自动重试，最后统一回调主线程。
 */
object ClipboardTextReader {
    private const val TAG = "HyperCopy-ClipboardRead"

    /** 初始延迟：等待 Shizuku 悬浮 Activity 退出、主 Activity 焦点恢复 */
    private const val INITIAL_DELAY_MILLIS = 250L

    /** 单次读取失败后的重试间隔 */
    private const val RETRY_DELAY_MILLIS = 150L

    /** 重试次数（含首次） */
    private const val MAX_ATTEMPTS = 3

    /**
     * 延迟读取剪贴板文本，结果通过主线程回调。
     * @param source v1.54 调用来源（诊断日志用：notification/editor/rules/suggestion）
     * @param onResult 非空=读取成功；null=剪贴板为空或读取失败
     */
    fun readDelayed(context: Context, source: String = "", onResult: (String?) -> Unit) {
        val appContext = context.applicationContext
        Thread {
            try {
                Thread.sleep(INITIAL_DELAY_MILLIS)
                var result: String? = null
                // v1.42 修复：读到非空立即退出（原 return@repeat 只跳过本次迭代，
                // 后续失败重试会把已读到的结果覆盖为 null）
                for (attempt in 0 until MAX_ATTEMPTS) {
                    result = readOnce(appContext, source)
                    if (!result.isNullOrBlank()) break
                    if (attempt < MAX_ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MILLIS)
                }
                if (result.isNullOrBlank()) {
                    HyperLog.d(TAG, "clipboard read failed after $MAX_ATTEMPTS attempts from=$source")
                }
                Handler(Looper.getMainLooper()).post { onResult(result) }
            } catch (e: InterruptedException) {
                Handler(Looper.getMainLooper()).post { onResult(null) }
            }
        }.start()
    }
    private fun readOnce(context: Context, source: String = ""): String? {
        return runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // v1.54 诊断：记录每次读取尝试（来源+结果），定位启动时异常读取
            HyperLog.d(TAG, "read attempt from=$source")
            val clip = cm.primaryClip
            if (clip == null) {
                HyperLog.d(TAG, "clipboard read: primaryClip is null")
                return null
            }
            if (clip.itemCount <= 0) {
                HyperLog.d(TAG, "clipboard read: itemCount=0")
                return null
            }
            val item = clip.getItemAt(0)
            // 优先纯文本；非文本 item（图片/文件/URI）用 coerceToText 兜底（可能为 URI 字符串，由分析器决定是否可用）
            val text = item.text?.toString()
                ?: item.coerceToText(context).toString().takeIf { it.isNotBlank() }
            if (text.isNullOrBlank()) {
                HyperLog.d(TAG, "clipboard read: item text blank")
            }
            text
        }.getOrElse { throwable ->
            HyperLog.d(TAG, "clipboard read exception: ${throwable.message}")
            null
        }
    }
}