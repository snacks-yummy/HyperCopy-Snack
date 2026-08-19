package io.github.hypercopy.ui.activities

import io.github.hypercopy.ui.framework.HyperCopyTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.hypercopy.ui.pages.stats.StatsPage
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 规则统计（v1.25） */
class StatsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        io.github.hypercopy.UiActionLogger.page("统计")
        setContent {
            HyperCopyTheme(this) {
                StatsPage(onBack = { finish() })
            }
        }
    }
}