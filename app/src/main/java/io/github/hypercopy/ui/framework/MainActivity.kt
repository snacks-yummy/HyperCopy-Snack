package io.github.hypercopy.ui.framework

import android.app.ActivityManager
import android.os.Bundle
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import io.github.hypercopy.data.settings.SettingsRepository
import java.util.Locale
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MainActivity : ComponentActivity() {
    companion object {
        // v1.50 通知点击后等待焦点稳定再读剪贴板
        private const val FALLBACK_READ_DELAY_MILLIS = 400L
    }
    // v1.92 深色适配：System 模式显式解析为 Dark/Light（MIUIX System 在 HyperOS 上可能不跟随）
    private fun resolveColorMode(value: String): io.github.hypercopy.ui.framework.AppColorMode {
        val mode = appColorModeFromValue(value)
        if (mode != io.github.hypercopy.ui.framework.AppColorMode.System) return mode
        val uiMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return if (uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            io.github.hypercopy.ui.framework.AppColorMode.Dark
        } else {
            io.github.hypercopy.ui.framework.AppColorMode.Light
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // v1.54 通知点击时 App 已在后台 → 走 onNewIntent（onCreate 不会执行）
        handleProcessClipboardIntent(intent)
    }

    /** v1.50/54 剪贴板读取失败兜底：用户点击"点击跳转"通知 → 前台读取成功 → 处理跳转 */
    private fun handleProcessClipboardIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(io.github.hypercopy.Config.EXTRA_PROCESS_CLIPBOARD, false) != true) return
        // 一次性：处理完移除，防止任务复用/重复触发
        intent.removeExtra(io.github.hypercopy.Config.EXTRA_PROCESS_CLIPBOARD)
        io.github.hypercopy.HyperLog.d("HyperCopy", "process clipboard requested from notification")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            io.github.hypercopy.clipboard.handling.ClipboardTextReader.readDelayed(this, source = "notification") { text ->
                if (!text.isNullOrBlank()) {
                    io.github.hypercopy.HyperLog.d("HyperCopy", "process clipboard read ok len=${text.length}")
                    // v1.139.1 后台线程处理：云端快递检测(快递100, 5s 超时)不阻塞主线程
                    kotlin.concurrent.thread(name = "HyperCopyNotificationHandle") {
                        io.github.hypercopy.clipboard.handling.ClipboardTextHandler.handle(
                            applicationContext,
                            text,
                            "",
                        )
                    }
                } else {
                    io.github.hypercopy.HyperLog.d("HyperCopy", "process clipboard read empty")
                }
            }
        }, FALLBACK_READ_DELAY_MILLIS)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.140.18 兜底返回：Android 16 predictive back 手势返回统一处理
        // v1.142.8 修复：去掉「非 Home tab → 跳首页」——页面内部层级由各页面 BackHandler 自行回退，
        // 兜底只处理 设置子页 → 退出，符合 Android 标准返回行为
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (AppNav.subPageFlow.value != null) {
                    AppNav.subPageFlow.value = null
                    return
                }
                finish()
            }
        })
        handleProcessClipboardIntent(intent)
        // v1.38 启动自动激活：Shizuku 授权 + 无障碍兜底（后台线程，幂等）
        io.github.hypercopy.clipboard.monitor.AutoActivator.activate(applicationContext)
        // v1.95 强制关闭已废弃的 AI 引擎委托开关（IntentActivity 非首次不弹窗，委托不可行）
        runCatching {
            val repo = SettingsRepository(applicationContext)
            if (!repo.readForceDelegateDisabled()) {
                repo.persistExpressDelegateAiEngine(false)
                repo.persistForceDelegateDisabled()
            }
        }
        setContent {
            val settingsRepository = remember { SettingsRepository(applicationContext) }
            var colorMode by remember { mutableStateOf(resolveColorMode(settingsRepository.readColorMode())) }
            var appLanguage by remember { mutableStateOf(appLanguageFromValue(settingsRepository.readAppLanguage())) }
            val activityResultRegistryOwner = this@MainActivity
            val activityContext = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
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
            val controller = remember(colorMode) { ThemeController(colorSchemeModeOf(colorMode)) }
            val navigationEventDispatcherOwner = remember {
                object : NavigationEventDispatcherOwner {
                    override val navigationEventDispatcher = NavigationEventDispatcher()
                }
            }

            DisposableEffect(lifecycleOwner, settingsRepository) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        colorMode = resolveColorMode(settingsRepository.readColorMode())
                        appLanguage = appLanguageFromValue(settingsRepository.readAppLanguage())
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            MiuixTheme(controller = controller) {
                CompositionLocalProvider(
                    LocalContext provides localizedContext,
                    LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
                    LocalNavigationEventDispatcherOwner provides navigationEventDispatcherOwner,
                ) {
                    AppScreen(
                        colorMode = colorMode,
                        // v1.142.6f 修复主题自动变暗：持久化 colorMode——此前只更新内存，ON_RESUME 用存储旧值覆盖（选浅色/跟随后被切回暗色）
                        onColorModeChange = {
                            colorMode = it
                            settingsRepository.persistColorMode(it.value)
                        },
                        onAppLanguageChange = { appLanguage = it },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateRecentsVisibility(SettingsRepository(applicationContext).readHideFromRecents())
    }

    fun updateRecentsVisibility(hideFromRecents: Boolean) {
        val activityManager = getSystemService(ActivityManager::class.java)
        activityManager.appTasks
            .firstOrNull { it.taskInfo?.taskId == taskId }
            ?.setExcludeFromRecents(hideFromRecents)
    }
}
