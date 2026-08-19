package io.github.hypercopy.ui.activities

import io.github.hypercopy.ui.framework.HyperCopyTheme
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import io.github.hypercopy.Config
import io.github.hypercopy.data.settings.SettingsRepository
import io.github.hypercopy.ui.framework.AppLanguage
import io.github.hypercopy.ui.framework.appLanguageFromValue
import io.github.hypercopy.ui.framework.appColorModeFromValue
import io.github.hypercopy.ui.framework.colorSchemeModeOf
import io.github.hypercopy.ui.pages.rules.RuleSuggestionPage
import java.util.Locale
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 规则建议页 Activity：对未命中的剪贴板内容自动分析并一键添加规则。
 * 由"未命中通知"或"从剪贴板添加"入口打开。
 */
class RuleSuggestionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        io.github.hypercopy.UiActionLogger.page("规则建议页")
        setContent {
            val settingsRepository = remember { SettingsRepository(applicationContext) }
            val colorMode = remember { appColorModeFromValue(settingsRepository.readColorMode()) }
            val appLanguage = appLanguageFromValue(settingsRepository.readAppLanguage())
            val activityContext = LocalContext.current
            val configuration = LocalConfiguration.current
            val localizedContext = remember(appLanguage, activityContext, configuration) {
                if (appLanguage == AppLanguage.System) {
                    activityContext
                } else {
                    val config = android.content.res.Configuration(configuration)
                    config.setLocale(Locale.forLanguageTag(appLanguage.value))
                    val localeContext = activityContext.createConfigurationContext(config)
                    object : ContextWrapper(activityContext) {
                        override fun getAssets() = localeContext.assets
                        override fun getResources() = localeContext.resources
                    }
                }
            }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalActivityResultRegistryOwner provides this@RuleSuggestionActivity,
            ) {
                HyperCopyTheme(this) {
                    RuleSuggestionPage(
                        initialText = intent.getStringExtra(Config.EXTRA_SUGGESTION_TEXT).orEmpty(),
                        onBack = ::finish,
                    )
                }
            }
        }
    }
}