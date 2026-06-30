package io.github.hypercopy.ui.activities

import io.github.hypercopy.ui.framework.HyperCopyTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.hypercopy.ui.pages.log.LogViewerPage
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 日志查看器 */
class LogViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperCopyTheme(this) {
                LogViewerPage(onBack = { finish() })
            }
        }
    }
}