package io.github.hypercopy.ui.framework

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.hypercopy.data.settings.SettingsRepository
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** v1.94 显式解析颜色模式：System -> 按 uiMode 判定 Dark/Light（修复 HyperOS 上 ColorSchemeMode.System 不跟随） */
fun resolveAppColorMode(context: Context, value: String): AppColorMode {
    val mode = appColorModeFromValue(value)
    if (mode != AppColorMode.System) return mode
    val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return if (uiMode == Configuration.UI_MODE_NIGHT_YES) AppColorMode.Dark else AppColorMode.Light
}

/** v1.94 统一主题入口：所有 Activity 用它包裹内容（深色跟随系统） */
@Composable
fun HyperCopyTheme(context: Context, content: @Composable () -> Unit) {
    val settingsRepository = remember { SettingsRepository(context.applicationContext) }
    var colorMode by remember { mutableStateOf(resolveAppColorMode(context, settingsRepository.readColorMode())) }
    val controller = remember(colorMode) { ThemeController(colorSchemeModeOf(colorMode)) }
    MiuixTheme(controller = controller) {
        content()
    }
}
