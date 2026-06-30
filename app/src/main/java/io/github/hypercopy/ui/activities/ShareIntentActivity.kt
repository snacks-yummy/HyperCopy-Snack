package io.github.hypercopy.ui.activities
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import io.github.hypercopy.HyperLog
import io.github.hypercopy.R
import io.github.hypercopy.clipboard.handling.ClipboardTextHandler

/**
 * 分享面板直达入口（v1.33）：
 * 其他 App 选中文字 → 系统分享面板 → HyperCopy → 直接解析+跳转，绕过剪贴板。
 * 适用于禁复制 App、或复制通道不可用的场景。
 */
class ShareIntentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (sharedText.isEmpty()) {
            Toast.makeText(this, R.string.share_empty, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        HyperLog.d("ShareEntry", "share text len=${sharedText.length}")
        // 走标准处理链路：匹配规则 → 跳转/未命中提示
        // v1.139.1 后台线程处理：云端快递检测(快递100, 5s 超时)不阻塞主线程
        kotlin.concurrent.thread(name = "HyperCopyShareHandle") {
            ClipboardTextHandler.handle(applicationContext, sharedText, "share")
        }
        Toast.makeText(this, R.string.share_handled, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (sharedText.isNotEmpty()) {
            kotlin.concurrent.thread(name = "HyperCopyShareHandle") {
                ClipboardTextHandler.handle(applicationContext, sharedText, "share")
            }
        }
        finish()
    }
}