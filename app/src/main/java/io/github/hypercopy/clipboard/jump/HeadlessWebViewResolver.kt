package io.github.hypercopy.clipboard.jump

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
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

    /**
     * v1.144.9 WebView 预热：启动后主线程空闲时预创建缓存实例（消化内核初始化开销，首个跳转省 ~1s）。
     * 已有实例则跳过；被系统回收时下次 acquire 自动重建（天然兜底）；失败静默（下次用时再建）。
     */
    fun warmUp(context: Context) {
        synchronized(this) {
            if (cachedWebView != null) return
            // v1.145.5 低内存自适应：HyperOS 内存压力大（MemFree 常 170MB）时预创建 WebView 会多一个
            // 常驻渲染进程 → LMK 杀宿主风险。MemAvailable < 1.5GB 时跳过预热（走链时现建，仅首个跳转变慢）
            if (lowMemory()) {
                HyperLog.d(TAG, "webview warmUp skipped: low memory")
                return
            }
            runCatching {
                cachedWebView = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.domStorageEnabled = true
                    // v1.145.4 预热实例兜底：空闲期渲染进程崩溃（低内存 LMK 回收渲染进程，08:04:53 crashpad 实证）
                    // 无 WebViewClient → 默认杀主进程（App 重启、跳转中断）。覆写 return true + 清缓存 + destroy，
                    // 下次 acquire 自动重建（自愈）；与 Resolver 走链实例兜底一致
                    webViewClient = object : WebViewClient() {
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            HyperLog.w(TAG, "webview warmUp instance render process gone: crashed=${detail.didCrash()}")
                            synchronized(this@HeadlessWebViewResolver) {
                                if (cachedWebView === view) {
                                    cachedWebView = null
                                    cachedWebViewBusy = false
                                }
                            }
                            view.destroy()
                            return true
                        }
                    }
                }
            }.onFailure {
                cachedWebView = null
                HyperLog.d(TAG, "webview warmUp failed: ${it.message}")
            }
        }
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
                        // v1.145.5 JS 点击延迟 1.5s 执行：低内存（HyperOS MemFree 常 170MB）下 page finished 后立即
                        // evaluateJavascript 大脚本 → 渲染进程崩溃（crashpad 实证：08:04:53/08:09:19 两次
                        // page finished 后 1~1.3s 崩溃，且 HyperOS 渲染崩溃不回调 onRenderProcessGone → 宿主被杀）。
                        // scheme 捕获走 shouldOverrideUrlLoading 实时拦截（页面自动 location.href，07:56 实证
                        // scheme captured +1390ms 早于 page finished +1509ms），JS 仅为点击兜底 → 延迟执行无副作用
                        handler.postDelayed({
                            if (finished) return@postDelayed
                            view.evaluateJavascript(AUTO_CLICK_ONCE_JS, null)
                        }, 1_500L)
                        // v1.145.1 快速兜底：页面加载完成 4s 内无 scheme 捕获 → 提前 fallback（原等满 8s 超时）。
                        // 安全边界：mt.cn 快链路 <600ms 已捕获；peisong 慢链路历史实测 3.4s < 4s；dpurl 静态壳等 4s 即兜底。
                        handler.postDelayed({
                            if (!finished) {
                                HyperLog.d(TAG, "webview page finished but no scheme in 4s, early fallback (t=+${System.currentTimeMillis() - startMs}ms)")
                                fallback()
                            }
                        }, 4000L)
                    }

                    // v1.145.0 渲染进程崩溃兜底：默认行为是杀主进程（闪退→监听/无障碍全停→跳转中断）。
                    // 覆写自行处理：僵尸实例销毁+清缓存（防复用再次崩溃），回退系统 Intent 打开原始 URL，跳转不中断。
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        handleRenderProcessGone(view)
                        return true
                    }
                }
            }
            webView = view
            // Bug③修复：超时可配置（默认 3000ms）
            val timeout = io.github.hypercopy.data.settings.SettingsRepository(context).readWebViewTimeoutMillis()
            handler.postDelayed(timeoutRunnable, timeout)
            // v1.145.1 dpurl.cn 特判：美团服务端按 UA 分流——WebView 默认 UA 返回静态壳页（无 302/无 scheme，
            // 实测 8s 干等 fallback）；强制移动 Chrome UA 触发 302 美团/微信链路（v1.143.3 验证方向有效，
            // 实验线废弃后本线重实施；mt.cn 不受影响仍走默认 UA）
            if (url.contains("dpurl.cn", ignoreCase = true)) {
                view.settings.userAgentString = MOBILE_CHROME_UA
                HyperLog.d(TAG, "dpurl.cn 特判: 强制移动 Chrome UA")
            }
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

        // v1.145.0 渲染进程崩溃处理（Resolver 级方法，匿名 WebViewClient 内调用，与 fallback 同模式）
        private fun handleRenderProcessGone(view: WebView) {
            HyperLog.w(TAG, "webview render process gone: view=$view")
            synchronized(this@HeadlessWebViewResolver) {
                if (cachedWebView === view) {
                    cachedWebView = null
                    cachedWebViewBusy = false
                }
            }
            finishWithLaunch(url, packageName)
            view.destroy()
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

// v1.145.5 低内存检测：读 /proc/meminfo MemAvailable，< 1.5GB 视为低内存（HyperOS 激进 LMK 场景跳过预热）
private fun lowMemory(): Boolean = runCatching {
    val memAvailableKb = java.io.File("/proc/meminfo").readText()
        .lineSequence()
        .firstOrNull { it.startsWith("MemAvailable:") }
        ?.substringAfter(':')?.trim()?.substringBefore(" kB")?.trim()?.toLongOrNull()
        ?: return@runCatching false
    memAvailableKb < 1_500_000L
}.getOrDefault(false)

// v1.145.1 dpurl.cn UA 特判常量：移动 Chrome 标准 UA（与系统浏览器一致，触发美团服务端 302 分流）
private const val MOBILE_CHROME_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

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
