package io.github.hypercopy.clipboard.jump

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.hypercopy.HyperLog
import io.github.hypercopy.clipboard.privileged.ActivityLaunchStrategy
import io.github.hypercopy.clipboard.privileged.toViewIntent
import java.util.concurrent.atomic.AtomicBoolean

object HeadlessWebViewResolver {
    private const val TAG = "HyperCopy"
    private val handler = Handler(Looper.getMainLooper())

    fun resolveAndLaunch(context: Context, url: String, packageName: String, clearClipboardAfterJump: Boolean = false, userId: Int? = null) {
        handler.post {
            Resolver(context.applicationContext, url, packageName, clearClipboardAfterJump, userId, launchWhenResolved = true).start()
        }
    }

    fun preload(context: Context, url: String, packageName: String, clearClipboardAfterJump: Boolean = false): Preload {
        val preload = Preload(context.applicationContext, url, packageName, clearClipboardAfterJump)
        handler.post { preload.start() }
        return preload
    }

    class Preload internal constructor(
        private val context: Context,
        private val url: String,
        private val packageName: String,
        private val clearClipboardAfterJump: Boolean,
        private var userId: Int? = null,
    ) {
        private val confirmed = AtomicBoolean(false)
        private var resolver: Resolver? = null
        private var resolvedIntent: android.content.Intent? = null

        internal fun start() {
            resolver = Resolver(context, url, packageName, clearClipboardAfterJump, userId, launchWhenResolved = false) { intent ->
                resolvedIntent = intent
                if (confirmed.get()) {
                    PendingJumpCoordinator.launchAfterClipboardClear(context, clearClipboardAfterJump) {
                        ActivityLaunchStrategy.launch(context, intent, userId)
                    }
                }
            }.also { it.start() }
        }

        fun continueLaunch(context: Context, userId: Int? = null) {
            this.userId = userId
            confirmed.set(true)
            handler.post {
                val intent = resolvedIntent
                if (intent != null) {
                    PendingJumpCoordinator.launchAfterClipboardClear(context, clearClipboardAfterJump) {
                        ActivityLaunchStrategy.launch(context.applicationContext, intent, userId)
                    }
                }
            }
        }

        fun cancel() {
            handler.post {
                resolver?.cancel()
                resolver = null
            }
        }
    }

    private class Resolver(
        private val context: Context,
        private val url: String,
        private val packageName: String,
        private val clearClipboardAfterJump: Boolean,
        private val userId: Int?,
        private val launchWhenResolved: Boolean,
        private val onResolved: ((android.content.Intent) -> Unit)? = null,
    ) {
        private var finished = false
        private var webView: WebView? = null
        private val timeoutRunnable = Runnable { fallback() }
        // v1.141.44 分段计时基准：load 起始时间，后续每跳 URL 输出相对耗时（t=+XXms），定位 mt.cn 链路耗时分布
        private val startMs = System.currentTimeMillis()

    @SuppressLint("SetJavaScriptEnabled")
        fun start() {
            // v1.141.28 提速：复用缓存 WebView 实例（避免每次 new WebView 的初始化开销），
            // 关闭无需的 viewport 选项（减少移动端 H5 reflow），显著缩短 mt.cn→peisong→scheme 链路耗时。
            // v1.141.42 并发修复：acquireWebView 保证每个 Resolver 独享实例（缓存空闲才复用），
            // 避免双通道并发（短信监听+剪贴板）共享同一 WebView 时 loadUrl 互相打断、webViewClient 串台。
            val view = acquireWebView(context).apply {
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        return handleUrl(request.url.toString())
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                        return handleUrl(url)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        // v1.141.44 页面加载完成耗时（peisong 页 JS 执行点）
                        HyperLog.d(TAG, "webview page finished: $url (t=+${System.currentTimeMillis() - startMs}ms)")
                        // 页面加载完再执行"打开/跳转"JS 点击（peisong 页关键：页面自身也会自动 location.href 拉 scheme，此处双保险）
                        view.evaluateJavascript(AUTO_CLICK_ONCE_JS, null)
                    }
                }
            }
            webView = view
            // Bug③修复：超时可配置（默认 3000ms）
            val timeout = io.github.hypercopy.data.settings.SettingsRepository(context).readWebViewTimeoutMillis()
            handler.postDelayed(timeoutRunnable, timeout)
            HyperLog.d(TAG, "headless webview load: $url")
            view.loadUrl(url)
        }

        private fun handleUrl(nextUrl: String): Boolean {
            // v1.141.44 分段计时：HTTP 跳转（302 链）与 scheme 捕获（weixin:// 等）分别记录耗时
            val elapsed = System.currentTimeMillis() - startMs
            if (isWebUrl(nextUrl)) {
                HyperLog.d(TAG, "webview redirect: ${nextUrl.take(90)} (t=+${elapsed}ms)")
                return false
            }
            HyperLog.d(TAG, "webview scheme captured: ${nextUrl.take(90)} (t=+${elapsed}ms)")
            finishWithLaunch(nextUrl, "")
            return true
        }

        private fun fallback() {
            if (finished) return
            finishWithLaunch(url, packageName)
        }

        private fun finishWithLaunch(targetUrl: String, targetPackageName: String) {
            if (finished) return
            finished = true
            handler.removeCallbacks(timeoutRunnable)
            HyperLog.d(TAG, "headless webview resolved: ${targetUrl.take(90)} (总耗时 ${System.currentTimeMillis() - startMs}ms)")
            val intent = targetUrl.toViewIntent(targetPackageName)
            onResolved?.invoke(intent)
            if (launchWhenResolved) {
                PendingJumpCoordinator.launchAfterClipboardClear(context, clearClipboardAfterJump) {
                    ActivityLaunchStrategy.launch(context, intent, userId)
                }
            }
            webView?.stopLoading()
            releaseWebView(webView)
            webView = null
        }

        fun cancel() {
            if (finished) return
            finished = true
            handler.removeCallbacks(timeoutRunnable)
            webView?.stopLoading()
            releaseWebView(webView)
            webView = null
        }
    }

    @Volatile
    private var cachedWebView: WebView? = null
    @Volatile
    private var cachedWebViewBusy = false
    /**
     * v1.141.42 获取一个可用 WebView：缓存实例空闲则复用（提速）；忙时新建独立实例（并发安全）。
     * 每个调用方独享返回的实例，杜绝共享实例的 loadUrl 互相打断 / webViewClient 串台。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun acquireWebView(context: Context): WebView {
        synchronized(this) {
            val cached = cachedWebView
            if (cached != null && !cachedWebViewBusy) {
                cachedWebViewBusy = true
                return cached
            }
            val view = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.domStorageEnabled = true
            }
            cachedWebView = view
            cachedWebViewBusy = true
            return view
        }
    }

    /** v1.141.42 释放 WebView：若为缓存实例则标记空闲（下次复用）；非缓存实例（并发新建）直接丢弃 */
    private fun releaseWebView(view: WebView?) {
        if (view == null) return
        synchronized(this) {
            if (cachedWebView === view) {
                cachedWebViewBusy = false
            }
        }
    }
}

private fun isWebUrl(url: String): Boolean = url.startsWith("http://", true) || url.startsWith("https://", true)

private const val AUTO_CLICK_ONCE_JS = """
(function() {
  var nodes = Array.prototype.slice.call(document.querySelectorAll('a,button,[role="button"]'));
  for (var i = 0; i < nodes.length; i++) {
    var node = nodes[i];
    var text = (node.innerText || node.textContent || node.getAttribute('aria-label') || '').toLowerCase();
    var rect = node.getBoundingClientRect();
    var style = window.getComputedStyle(node);
    if (rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none') {
      if (text.indexOf('打开') >= 0 || text.indexOf('app') >= 0 || text.indexOf('open') >= 0) {
        node.click();
        return true;
      }
    }
  }
  for (var j = 0; j < nodes.length; j++) {
    var fallback = nodes[j];
    var fallbackRect = fallback.getBoundingClientRect();
    var fallbackStyle = window.getComputedStyle(fallback);
    if (fallbackRect.width > 0 && fallbackRect.height > 0 && fallbackStyle.visibility !== 'hidden' && fallbackStyle.display !== 'none') {
      fallback.click();
      return true;
    }
  }
  return false;
})();
"""
