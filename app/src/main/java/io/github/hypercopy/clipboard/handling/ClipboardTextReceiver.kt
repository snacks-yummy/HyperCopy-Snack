package io.github.hypercopy.clipboard.handling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.hypercopy.Config
import io.github.hypercopy.data.settings.SettingsRepository

class ClipboardTextReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val isTest = intent.action == Config.ACTION_TEST_CLIPBOARD
        if (intent.action != Config.ACTION_HANDLE_CLIPBOARD_TEXT && !isTest) return
        // v1.140.10 去掉模式检查: Xposed 广播到达即处理(默认模式 lsposed, 兼容已存 shizuku 设置)
        // (LSPOSED 广播仅模块启用时由系统进程 Hook 发出, 无需模式开关过滤)
        val text = intent.getStringExtra(Config.EXTRA_CLIPBOARD_TEXT)
        if (text == null) {
            io.github.hypercopy.HyperLog.d("HyperCopy", "参数缺失: intent 无 ${Config.EXTRA_CLIPBOARD_TEXT}（检查广播 extra key）action=${intent.action}")
            return
        }
        val source = intent.getStringExtra(Config.EXTRA_CLIPBOARD_SOURCE) ?: "test-broadcast"
        // v1.102 测试广播同步真实写入剪贴板（模拟真实复制场景，目标 App 的剪贴板检测才能读到新单号）
        if (isTest && text.isNotBlank()) {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("hypercopy-test", text))
            }
        }
        ClipboardTextHandler.handle(context, text, source)
    }
}
