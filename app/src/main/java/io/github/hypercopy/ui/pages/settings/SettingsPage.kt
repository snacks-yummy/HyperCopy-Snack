package io.github.hypercopy.ui.pages.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collect
import io.github.hypercopy.Config
import io.github.hypercopy.R
import io.github.hypercopy.clipboard.monitor.AccessibilityUtils
import io.github.hypercopy.ui.framework.AppColorMode
import io.github.hypercopy.ui.framework.AppLanguage
import io.github.hypercopy.ui.framework.ClipboardMonitorMode
import io.github.hypercopy.ui.framework.JumpNotificationMode
import io.github.hypercopy.data.settings.SettingsRepository
import io.github.hypercopy.data.systemlink.AndroidUser
import io.github.hypercopy.ui.components.SettingsAction
import io.github.hypercopy.ui.components.CloudSourceManagerDialog
import io.github.hypercopy.ui.components.RulesBackupDialog
import io.github.hypercopy.data.rules.CloudSourceRegistry
import io.github.hypercopy.data.rules.displayNameText
import io.github.hypercopy.ui.components.SettingsActionWithArrow
import io.github.hypercopy.ui.components.SettingsIcon
import io.github.hypercopy.ui.components.SwitchAction
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AppRecording
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.icon.extended.Translate
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Unpin
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** v1.140.18 设置页二级子页（避免一级开关过多） */
enum class SettingsSubPage { KEEP_ALIVE, NOTIFY, JUMP, EXPRESS, MONITOR }

@Composable
fun SettingsPage(
    modifier: Modifier = Modifier,
    logLevel: Int,
    updateCheckFrequency: String,
    // v1.145.12 云规则自动检测开关 + TTL 小时数
    cloudRulesAutoCheck: Boolean,
    cloudRulesTtlHours: Int,
    hideFromRecents: Boolean,
    desktopIconHidden: Boolean,
    detectClonedApp: Boolean,
    clonedAppUserId: Int,
    clonedAppUsers: List<AndroidUser>,
    miuiIslandBypassRestriction: Boolean,
    appLanguage: AppLanguage,
    clipboardMonitorMode: ClipboardMonitorMode,
    jumpNotificationMode: JumpNotificationMode,
    // v1.141 文本类("文本类"规则：取件码/验证码)独立通知渠道，不随跳转渠道
    textNotificationMode: JumpNotificationMode,
    notifyUnmatched: Boolean,
    notifyPickupCode: Boolean,
    notifyIncludePlatform: Boolean,
    matchDebugLog: Boolean,
    monitorEnabled: Boolean,
    showHitToast: Boolean,
    cloudExpressDetect: Boolean,
    expressDirectJump: Boolean,
    cainiaoAutoConfirm: Boolean,
    cainiaoAutoExpand: Boolean,
    schemeDirectJump: Boolean,
    jumpFallbackWeb: Boolean,
    jumpPrecheck: Boolean,
    duplicateWindowMillis: Long,
    autoActivate: Boolean,
    // v1.141.39 日志缓冲条数（内存环形缓冲上限，日志 UI 展示窗口）
    logBufferMax: Int = Config.DEFAULT_LOG_BUFFER_MAX,
    onLogLevelChange: (Int) -> Unit,
    onLogBufferMaxChange: (Int) -> Unit = {},
    // v1.145.12 更新频率与云规则设置回调（原 onAutoCheckUpdateChange 已升级为频率选择）
    onUpdateCheckFrequencyChange: (String) -> Unit = {},
    onCloudRulesAutoCheckChange: (Boolean) -> Unit = {},
    onCloudRulesTtlHoursChange: (Int) -> Unit = {},
    onHideFromRecentsChange: (Boolean) -> Unit,
    onDesktopIconHiddenChange: (Boolean) -> Unit,
    onDetectClonedAppChange: (Boolean) -> Unit,
    onClonedAppUserIdChange: (Int) -> Unit,
    onMiuiIslandBypassRestrictionChange: (Boolean) -> Unit,
    onAppLanguageChange: (AppLanguage) -> Unit,
    onJumpNotificationModeChange: (JumpNotificationMode) -> Unit,
    onTextNotificationModeChange: (JumpNotificationMode) -> Unit = {},
    onNotifyUnmatchedChange: (Boolean) -> Unit,
    onNotifyPickupCodeChange: (Boolean) -> Unit = {},
    onNotifyIncludePlatformChange: (Boolean) -> Unit = {},
    onMatchDebugLogChange: (Boolean) -> Unit,
    onMonitorEnabledChange: (Boolean) -> Unit = {},
    onShowHitToastChange: (Boolean) -> Unit = {},
    onCloudExpressDetectChange: (Boolean) -> Unit = {},
    onExpressDirectJumpChange: (Boolean) -> Unit = {},
    onCainiaoAutoConfirmChange: (Boolean) -> Unit = {},
    onCainiaoAutoExpandChange: (Boolean) -> Unit = {},
    onSchemeDirectJumpChange: (Boolean) -> Unit = {},
    onJumpFallbackWebChange: (Boolean) -> Unit = {},
    onJumpPrecheckChange: (Boolean) -> Unit = {},
    onDuplicateWindowMillisChange: (Long) -> Unit = {},
    onAutoActivateChange: (Boolean) -> Unit = {},
    onCheckUpdate: () -> Unit,
    // v1.142.6d 主题切换改设置页直选（与语言同操作逻辑，不再进子页面）
    colorMode: AppColorMode,
    onColorModeChange: (AppColorMode) -> Unit,
    onOpenAppList: () -> Unit,
    topContentPadding: Dp = 12.dp,
    bottomContentPadding: Dp = 16.dp,
    // v1.140.18 二级子页：状态提升到 AppScreen（顶层 BackHandler 统一返回栈）
    subPage: SettingsSubPage? = null,
    onSubPageChange: (SettingsSubPage?) -> Unit = {},
) {
    val logLevelOptions = logLevelOptions()
    val bufferMaxOptions = logBufferMaxOptions()
    val languageOptions = languageOptions()
    val jumpNotificationModeOptions = jumpNotificationModeOptions()
    val clonedAppUserOptions = clonedAppUserOptions(clonedAppUsers)
    // v1.145.12 云规则 TTL / 更新检测频率选项
    val cloudRulesTtlOptions = cloudRulesTtlOptions()
    val updateCheckFrequencyOptions = updateCheckFrequencyOptions()
    val context = LocalContext.current
    // v1.139.1 云端规则源管理（设置页入口）
    val settingsRepository = remember { SettingsRepository(context.applicationContext) }
    var showCloudSourceDialog by remember { mutableStateOf(false) }
    // v1.145.16 规则备份对话框状态
    var showRulesBackupDialog by remember { mutableStateOf(false) }
    // v1.142.8 返回栈修复：云规则源弹窗打开时返回键先关弹窗（不直接退出 App）
    BackHandler(enabled = showCloudSourceDialog || showRulesBackupDialog) {
        showCloudSourceDialog = false
        showRulesBackupDialog = false
    }
    // v1.140.18 子页滚动位置记忆：返回后再次进入恢复上次位置
    val subListState = rememberLazyListState()
    // v1.140.18 一级列表滚动位置记忆：子页返回后恢复原位置
    val mainListState = rememberLazyListState()
    val subScrollPositions = remember { mutableStateMapOf<SettingsSubPage, Pair<Int, Int>>() }
    LaunchedEffect(subPage) {
        val sp = subPage ?: return@LaunchedEffect
        subScrollPositions[sp]?.let { (idx, off) -> subListState.scrollToItem(idx, off) }
        snapshotFlow { subListState.firstVisibleItemIndex to subListState.firstVisibleItemScrollOffset }
            .collect { (idx, off) -> subScrollPositions[sp] = idx to off }
    }
    var cloudSourceKey by remember { mutableStateOf(settingsRepository.readCloudSource()) }
    // v1.140.18 设置页入口显示源 displayName + 原作者说明（不再只显示 key）
    val currentCloudSource = remember(cloudSourceKey) {
        CloudSourceRegistry.byKey(context, cloudSourceKey) ?: CloudSourceRegistry.AUTHOR
    }
    var accessibilityEnabled by remember { mutableStateOf(AccessibilityUtils.isServiceEnabled(context)) }
    val duplicateWindowOptionList = duplicateWindowOptions()
    // 从系统设置页返回（或外部变更）时刷新无障碍开关状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = AccessibilityUtils.isServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // v1.140.18 二级子页导航：null=一级列表
    if (subPage != null) {
        Column(modifier = modifier.fillMaxSize().padding(top = topContentPadding)) {
            // v1.140.18 固定返回行：不随内容滚动，始终可见
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSubPageChange(null) }
                    .padding(vertical = 10.dp, horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "← " + stringResource(R.string.action_back),
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = when (subPage) {
                        SettingsSubPage.KEEP_ALIVE -> stringResource(R.string.keep_alive_title)
                        SettingsSubPage.NOTIFY -> stringResource(R.string.settings_sub_notify)
                        SettingsSubPage.JUMP -> stringResource(R.string.settings_sub_jump)
                        SettingsSubPage.EXPRESS -> stringResource(R.string.settings_sub_express)
                        SettingsSubPage.MONITOR -> stringResource(R.string.settings_sub_monitor)
                    },
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            LazyColumn(
                state = subListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                item {
                    Card {
                        when (subPage) {
                    SettingsSubPage.KEEP_ALIVE -> {
                                    SwitchAction(
                                        icon = MiuixIcons.Unpin,
                                        title = stringResource(R.string.keep_alive_auto_activate),
                                        summary = stringResource(R.string.keep_alive_auto_activate_summary),
                                        checked = autoActivate,
                                        onCheckedChange = { onAutoActivateChange(!autoActivate) },
                                    )
                                    // v1.114 立即自愈：手动触发 KeepAliveMonitor 检查（无障碍绑定 + Shizuku 授权）
                                    SettingsActionWithArrow(
                                        icon = MiuixIcons.Tune,
                                        title = stringResource(R.string.keep_alive_self_heal),
                                        summary = stringResource(R.string.keep_alive_self_heal_summary),
                                        onClick = {
                                            io.github.hypercopy.clipboard.monitor.KeepAliveMonitor.checkAndHeal(context)
                                            Toast.makeText(context, R.string.keep_alive_self_heal_done, Toast.LENGTH_SHORT).show()
                                        },
                                    )
                                    // v1.144.3 移除「电池优化白名单」手动项：一键配置/自愈已覆盖（deviceidle + miui_power_save_whitelist 双写），避免重复入口
                                    SettingsActionWithArrow(
                                        icon = MiuixIcons.AppRecording,
                                        title = stringResource(R.string.keep_alive_app_detail),
                                        summary = stringResource(R.string.keep_alive_app_detail_summary),
                                        onClick = {
                                            runCatching {
                                                val intent = android.content.Intent(
                                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    android.net.Uri.parse("package:${context.packageName}"),
                                                )
                                                context.startActivity(intent)
                                            }
                                        },
                                    )
                    }
                    SettingsSubPage.NOTIFY -> {
                                    OverlayDropdownPreference(
                                        title = stringResource(R.string.jump_notification_mode),
                                        summary = stringResource(R.string.jump_notification_mode_summary),
                                        items = jumpNotificationModeOptions.map { it.label },
                                        selectedIndex = jumpNotificationModeOptions.indexOfFirst { it.value == jumpNotificationMode }.coerceAtLeast(0),
                                        startAction = { SettingsIcon(imageVector = MiuixIcons.Community) },
                                        insideMargin = SettingsItemMargin,
                                        onSelectedIndexChange = { onJumpNotificationModeChange(jumpNotificationModeOptions[it].value) },
                                    )
                                    // v1.141 文本类("文本类"规则：取件码/验证码)独立通知渠道，与跳转通知相互独立
                                    OverlayDropdownPreference(
                                        title = stringResource(R.string.text_notification_mode),
                                        summary = stringResource(R.string.text_notification_mode_summary),
                                        items = jumpNotificationModeOptions.map { it.label },
                                        selectedIndex = jumpNotificationModeOptions.indexOfFirst { it.value == textNotificationMode }.coerceAtLeast(0),
                                        startAction = { SettingsIcon(imageVector = MiuixIcons.Community) },
                                        insideMargin = SettingsItemMargin,
                                        onSelectedIndexChange = { onTextNotificationModeChange(jumpNotificationModeOptions[it].value) },
                                    )
                                    // 灵动岛 bypass 开关：只要 Shizuku 即可显示（文本类规则也可能选灵动岛，不依赖全局通知方式）
                                    if (clipboardMonitorMode == ClipboardMonitorMode.Shizuku) {
                                        SwitchAction(
                                            icon = MiuixIcons.Community,
                                            title = stringResource(R.string.miui_island_bypass_restriction),
                                            summary = stringResource(R.string.miui_island_bypass_restriction_summary),
                                            checked = miuiIslandBypassRestriction,
                                            onCheckedChange = { onMiuiIslandBypassRestrictionChange(!miuiIslandBypassRestriction) },
                                        )
                                    }
                                    SwitchAction(
                                        icon = MiuixIcons.Copy,
                                        title = stringResource(R.string.setting_notify_unmatched),
                                        summary = stringResource(R.string.setting_notify_unmatched_summary),
                                        checked = notifyUnmatched,
                                        onCheckedChange = { onNotifyUnmatchedChange(!notifyUnmatched) },
                                    )
                                    // v1.138 取件码通知：总开关 + 是否显示平台名
                                    SwitchAction(
                                        icon = MiuixIcons.Community,
                                        title = stringResource(R.string.setting_notify_pickup_code),
                                        summary = stringResource(R.string.setting_notify_pickup_code_summary),
                                        checked = notifyPickupCode,
                                        onCheckedChange = { onNotifyPickupCodeChange(!notifyPickupCode) },
                                    )
                                    if (notifyPickupCode) {
                                        SwitchAction(
                                            icon = MiuixIcons.Community,
                                            title = stringResource(R.string.setting_notify_include_platform),
                                            summary = stringResource(R.string.setting_notify_include_platform_summary),
                                            checked = notifyIncludePlatform,
                                            onCheckedChange = { onNotifyIncludePlatformChange(!notifyIncludePlatform) },
                                        )
                                    }
                                    SwitchAction(
                                        icon = MiuixIcons.File,
                                        title = stringResource(R.string.setting_show_hit_toast),
                                        summary = stringResource(R.string.setting_show_hit_toast_summary),
                                        checked = showHitToast,
                                        onCheckedChange = { onShowHitToastChange(!showHitToast) },
                                    )
                    }
                    SettingsSubPage.JUMP -> {
                                    SwitchAction(
                                        icon = MiuixIcons.File,
                                        title = stringResource(R.string.setting_scheme_direct_jump),
                                        summary = stringResource(R.string.setting_scheme_direct_jump_summary),
                                        checked = schemeDirectJump,
                                        onCheckedChange = { onSchemeDirectJumpChange(!schemeDirectJump) },
                                    )
                                    SwitchAction(
                                        icon = MiuixIcons.File,
                                        title = stringResource(R.string.setting_jump_fallback_web),
                                        summary = stringResource(R.string.setting_jump_fallback_web_summary),
                                        checked = jumpFallbackWeb,
                                        onCheckedChange = { onJumpFallbackWebChange(!jumpFallbackWeb) },
                                    )
                                    SwitchAction(
                                        icon = MiuixIcons.File,
                                        title = stringResource(R.string.setting_jump_precheck),
                                        summary = stringResource(R.string.setting_jump_precheck_summary),
                                        checked = jumpPrecheck,
                                        onCheckedChange = { onJumpPrecheckChange(!jumpPrecheck) },
                                    )
                    }
                    SettingsSubPage.EXPRESS -> {
                                    SwitchAction(
                                        icon = MiuixIcons.File,
                                        title = stringResource(R.string.setting_cloud_express_detect),
                                        summary = stringResource(R.string.setting_cloud_express_detect_summary),
                                        checked = cloudExpressDetect,
                                        onCheckedChange = { onCloudExpressDetectChange(!cloudExpressDetect) },
                                    )
                                    SwitchAction(
                                        icon = MiuixIcons.File,
                                        title = stringResource(R.string.setting_express_direct_jump),
                                        summary = stringResource(R.string.setting_express_direct_jump_summary),
                                        checked = expressDirectJump,
                                        onCheckedChange = { onExpressDirectJumpChange(!expressDirectJump) },
                                    )
                                    SwitchAction(
                                        icon = MiuixIcons.File,
                                        title = stringResource(R.string.setting_cainiao_auto_confirm),
                                        summary = stringResource(R.string.setting_cainiao_auto_confirm_summary),
                                        checked = cainiaoAutoConfirm,
                                        onCheckedChange = { onCainiaoAutoConfirmChange(!cainiaoAutoConfirm) },
                                    )
                                    SwitchAction(
                                        icon = MiuixIcons.File,
                                        title = stringResource(R.string.setting_cainiao_auto_expand),
                                        summary = stringResource(R.string.setting_cainiao_auto_expand_summary),
                                        checked = cainiaoAutoExpand,
                                        onCheckedChange = { onCainiaoAutoExpandChange(!cainiaoAutoExpand) },
                                    )
                                    // v1.126 跳转增强：scheme 直达优先 / 失败网页兜底 / 跳转前预检
                    }
                    SettingsSubPage.MONITOR -> {
                                    SwitchAction(
                                        icon = MiuixIcons.File,
                                        title = stringResource(R.string.setting_monitor_enabled),
                                        summary = stringResource(R.string.setting_monitor_enabled_summary),
                                        checked = monitorEnabled,
                                        onCheckedChange = { onMonitorEnabledChange(!monitorEnabled) },
                                    )
                                    SwitchAction(
                                        icon = MiuixIcons.AppRecording,
                                        title = stringResource(R.string.setting_accessibility_clipboard),
                                        summary = stringResource(
                                            if (accessibilityEnabled) R.string.setting_accessibility_clipboard_summary_on
                                            else R.string.setting_accessibility_clipboard_summary_off,
                                        ),
                                        checked = accessibilityEnabled,
                                        onCheckedChange = {
                                            val enable = !accessibilityEnabled
                                            val ok = AccessibilityUtils.setEnabledViaShizuku(context, enable)
                                            accessibilityEnabled = AccessibilityUtils.isServiceEnabled(context)
                                            if (ok) {
                                                Toast.makeText(
                                                    context,
                                                    if (enable) R.string.accessibility_enabled_ok else R.string.accessibility_disabled_ok,
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            } else {
                                                // Shizuku 未授权/失败 → 回退跳系统设置手动操作
                                                AccessibilityUtils.openAccessibilitySettings(context)
                                            }
                                        },
                                    )
                                    OverlayDropdownPreference(
                                        title = stringResource(R.string.setting_duplicate_window),
                                        summary = stringResource(R.string.setting_duplicate_window_summary),
                                        items = duplicateWindowOptionList.map { it.label },
                                        selectedIndex = duplicateWindowOptionList.indexOfFirst { it.value == duplicateWindowMillis }.coerceAtLeast(0),
                                        startAction = { SettingsIcon(imageVector = MiuixIcons.Copy) },
                                        insideMargin = SettingsItemMargin,
                                        onSelectedIndexChange = { onDuplicateWindowMillisChange(duplicateWindowOptionList[it].value) },
                                    )
                                    SwitchAction(
                                        icon = MiuixIcons.Copy,
                                        title = stringResource(R.string.detect_cloned_app),
                                        summary = stringResource(R.string.detect_cloned_app_summary),
                                        checked = detectClonedApp,
                                        onCheckedChange = { onDetectClonedAppChange(!detectClonedApp) },
                                    )
                                    if (detectClonedApp) {
                                        OverlayDropdownPreference(
                                            title = stringResource(R.string.cloned_app_user),
                                            summary = stringResource(R.string.cloned_app_user_summary),
                                            items = clonedAppUserOptions.map { it.label },
                                            selectedIndex = clonedAppUserOptions.indexOfFirst { it.userId == clonedAppUserId }.coerceAtLeast(0),
                                            startAction = { SettingsIcon(imageVector = MiuixIcons.Copy) },
                                            insideMargin = SettingsItemMargin,
                                            onSelectedIndexChange = { onClonedAppUserIdChange(clonedAppUserOptions[it].userId) },
                                        )
                                    }
                                    SettingsActionWithArrow(
                                        icon = MiuixIcons.ListView,
                                        title = stringResource(R.string.app_list),
                                        summary = stringResource(R.string.app_list_summary),
                                        onClick = onOpenAppList,
                                    )
                    }
                    }
                }
            }
        }
    }
    } else {
        LazyColumn(
            state = mainListState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = topContentPadding, end = 16.dp, bottom = bottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item { SmallTitle(text = stringResource(R.string.appearance)) }
        item {
            Card {
                OverlayDropdownPreference(
                    title = stringResource(R.string.language),
                    summary = stringResource(R.string.language_summary),
                    items = languageOptions.map { it.label },
                    selectedIndex = languageOptions.indexOfFirst { it.value == appLanguage }.coerceAtLeast(0),
                    startAction = { SettingsIcon(imageVector = MiuixIcons.Translate) },
                    insideMargin = SettingsItemMargin,
                    onSelectedIndexChange = { onAppLanguageChange(languageOptions[it].value) },
                )
                // v1.142.6d 主题改 OverlayDropdownPreference（与语言同款：设置页直选，点击弹出跟随系统/深色/浅色）
                val themeOptions = colorModeOptions()
                OverlayDropdownPreference(
                    title = stringResource(R.string.theme),
                    summary = stringResource(R.string.theme_summary),
                    items = themeOptions.map { it.label },
                    selectedIndex = themeOptions.indexOfFirst { it.value == colorMode }.coerceAtLeast(0),
                    startAction = { SettingsIcon(imageVector = MiuixIcons.Theme) },
                    insideMargin = SettingsItemMargin,
                    onSelectedIndexChange = { onColorModeChange(themeOptions[it].value) },
                )
            }
        }

        item { SmallTitle(text = stringResource(R.string.monitor_status_title)) }
        item {
            MonitorStatusCard(
                clipboardMonitorMode = clipboardMonitorMode,
            )
        }
            item { SmallTitle(text = stringResource(R.string.keep_alive_title)) }
            item {
                Card {
                    SettingsActionWithArrow(
                        icon = MiuixIcons.Unpin,
                        title = stringResource(R.string.keep_alive_title),
                        summary = stringResource(R.string.settings_sub_keep_alive_summary),
                        onClick = { onSubPageChange(SettingsSubPage.KEEP_ALIVE) },
                    )
                }
            }
        item { SmallTitle(text = stringResource(R.string.software_settings)) }
        // v1.139.1 云端规则源管理（换源入口，与云端规则页联动）
        item {
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCloudSourceDialog = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.setting_cloud_source_title),
                            style = MiuixTheme.textStyles.body1,
                        )
                        Text(
                            text = stringResource(R.string.setting_cloud_source_summary, currentCloudSource.displayNameText()),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        // v1.140.18 源说明：注明原作者 / 授权信息
                        if (currentCloudSource.description.isNotBlank()) {
                            Text(
                                text = currentCloudSource.description,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1,
                            )
                        }
                    }
                    Text(
                        text = "▾",
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        // v1.145.12 云规则自动检测开关 + 检测频率（TTL 可配置，关闭=打开页面纯缓存零网络）
        item {
            Card {
                SwitchAction(
                    icon = MiuixIcons.CloudFill,
                    title = stringResource(R.string.cloud_rules_auto_check),
                    summary = stringResource(R.string.cloud_rules_auto_check_summary),
                    checked = cloudRulesAutoCheck,
                    onCheckedChange = { onCloudRulesAutoCheckChange(!cloudRulesAutoCheck) },
                )
                if (cloudRulesAutoCheck) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.cloud_rules_ttl),
                        summary = stringResource(R.string.cloud_rules_ttl_summary),
                        items = cloudRulesTtlOptions.map { it.label },
                        selectedIndex = cloudRulesTtlOptions.indexOfFirst { it.value == cloudRulesTtlHours }.coerceAtLeast(0),
                        startAction = { SettingsIcon(imageVector = MiuixIcons.Timer) },
                        insideMargin = SettingsItemMargin,
                        onSelectedIndexChange = { onCloudRulesTtlHoursChange(cloudRulesTtlOptions[it].value) },
                    )
                }
            }
        }
        // v1.145.16 规则备份管理：导出到工作区 / 从备份恢复（防清除数据/卸载丢失）
        item {
            Card {
                SettingsActionWithArrow(
                    icon = MiuixIcons.File,
                    title = stringResource(R.string.rules_backup_title),
                    summary = stringResource(R.string.rules_backup_summary),
                    onClick = { showRulesBackupDialog = true },
                )
            }
        }
        // v1.68 软件设置拆 4 个子分组（原 18 项塞一个 Card 找设置困难）
            item { SmallTitle(text = stringResource(R.string.settings_group_notification)) }
            item {
                Card {
                    SettingsActionWithArrow(
                        icon = MiuixIcons.Community,
                        title = stringResource(R.string.settings_sub_notify),
                        summary = stringResource(R.string.settings_sub_notify_summary),
                        onClick = { onSubPageChange(SettingsSubPage.NOTIFY) },
                    )
                    SettingsActionWithArrow(
                        icon = MiuixIcons.Tune,
                        title = stringResource(R.string.settings_sub_jump),
                        summary = stringResource(R.string.settings_sub_jump_summary),
                        onClick = { onSubPageChange(SettingsSubPage.JUMP) },
                    )
                    SettingsActionWithArrow(
                        icon = MiuixIcons.File,
                        title = stringResource(R.string.settings_sub_express),
                        summary = stringResource(R.string.settings_sub_express_summary),
                        onClick = { onSubPageChange(SettingsSubPage.EXPRESS) },
                    )
                }
            }
            item { SmallTitle(text = stringResource(R.string.settings_group_monitor)) }
            item {
                Card {
                    SettingsActionWithArrow(
                        icon = MiuixIcons.Copy,
                        title = stringResource(R.string.settings_sub_monitor),
                        summary = stringResource(R.string.settings_sub_monitor_summary),
                        onClick = { onSubPageChange(SettingsSubPage.MONITOR) },
                    )
                }
            }
            // v1.141.87n 更新/隐私设置放回一级菜单（原 UPDATE_PRIVACY 二级页移除）
            item { SmallTitle(text = stringResource(R.string.settings_group_update_privacy)) }
            item {
                Card {
                    SettingsAction(
                        icon = MiuixIcons.Download,
                        title = stringResource(R.string.check_update),
                        summary = stringResource(R.string.check_update_summary),
                        onClick = onCheckUpdate,
                    )
                                        // v1.145.12 布尔开关升级为频率选择（不更新/每次启动/每天/每周），手动按钮保留
                    OverlayDropdownPreference(
                        title = stringResource(R.string.update_check_frequency),
                        summary = stringResource(R.string.update_check_frequency_summary),
                        items = updateCheckFrequencyOptions.map { it.label },
                        selectedIndex = updateCheckFrequencyOptions.indexOfFirst { it.value == updateCheckFrequency }.coerceAtLeast(0),
                        startAction = { SettingsIcon(imageVector = MiuixIcons.Update) },
                        insideMargin = SettingsItemMargin,
                        onSelectedIndexChange = { onUpdateCheckFrequencyChange(updateCheckFrequencyOptions[it].value) },
                    )
                    SwitchAction(
                        icon = MiuixIcons.Unpin,
                        title = stringResource(R.string.hide_from_recents),
                        summary = stringResource(R.string.hide_from_recents_summary),
                        checked = hideFromRecents,
                        onCheckedChange = { onHideFromRecentsChange(!hideFromRecents) },
                    )
                    SwitchAction(
                        icon = MiuixIcons.AppRecording,
                        title = stringResource(R.string.hide_desktop_icon),
                        summary = stringResource(R.string.hide_desktop_icon_summary),
                        checked = desktopIconHidden,
                        onCheckedChange = { onDesktopIconHiddenChange(!desktopIconHidden) },
                    )
                }
            }
        item { SmallTitle(text = stringResource(R.string.settings_group_log)) }
        item {
            Card {
                OverlayDropdownPreference(
                    title = stringResource(R.string.log_level),
                    summary = stringResource(R.string.log_level_summary),
                    items = logLevelOptions.map { it.label },
                    selectedIndex = logLevelOptions.indexOfFirst { it.value == logLevel }.coerceAtLeast(0),
                    startAction = { SettingsIcon(imageVector = MiuixIcons.File) },
                    insideMargin = SettingsItemMargin,
                    onSelectedIndexChange = { onLogLevelChange(logLevelOptions[it].value) },
                )
                // v1.141.39 日志缓冲条数（内存环形缓冲上限）：日志 UI 展示窗口大小
                OverlayDropdownPreference(
                    title = stringResource(R.string.log_buffer_max),
                    summary = stringResource(R.string.log_buffer_max_summary),
                    items = bufferMaxOptions.map { it.label },
                    selectedIndex = bufferMaxOptions.indexOfFirst { it.value == logBufferMax }.coerceAtLeast(0),
                    startAction = { SettingsIcon(imageVector = MiuixIcons.ListView) },
                    insideMargin = SettingsItemMargin,
                    onSelectedIndexChange = { onLogBufferMaxChange(bufferMaxOptions[it].value) },
                )
                SwitchAction(
                    icon = MiuixIcons.File,
                    title = stringResource(R.string.setting_match_debug_log),
                    summary = stringResource(R.string.setting_match_debug_log_summary),
                    checked = matchDebugLog,
                    onCheckedChange = { onMatchDebugLogChange(!matchDebugLog) },
                )
                SettingsActionWithArrow(
                    icon = MiuixIcons.ListView,
                    title = stringResource(R.string.setting_view_log),
                    // v1.141.40 动态显示实际配置的缓冲条数（原写死 3000）
                    summary = stringResource(R.string.setting_view_log_summary, logBufferMax),
                    onClick = {
                        context.startActivity(
                            android.content.Intent(context, io.github.hypercopy.ui.activities.LogViewerActivity::class.java),
                        )
                    },
                )
                SettingsActionWithArrow(
                    icon = MiuixIcons.Tune,
                    title = stringResource(R.string.setting_view_stats),
                    summary = stringResource(R.string.setting_view_stats_summary),
                    onClick = {
                        context.startActivity(
                            android.content.Intent(context, io.github.hypercopy.ui.activities.StatsActivity::class.java),
                        )
                    },
                )
            }
        }
        }
    }

        // v1.139.1 云端规则源管理对话框（设置页入口，与云端规则页共用）
        CloudSourceManagerDialog(
            show = showCloudSourceDialog,
            onDismiss = { showCloudSourceDialog = false },
            currentSourceKey = cloudSourceKey,
            settingsRepository = settingsRepository,
            onSourceChange = { key ->
                cloudSourceKey = key
                settingsRepository.persistCloudSource(key)
            },
        )
        // v1.145.16 规则备份对话框（导出到工作区 / 从备份恢复）
        RulesBackupDialog(
            show = showRulesBackupDialog,
            onDismiss = { showRulesBackupDialog = false },
        )
}

private data class LogLevelOption(val label: String, val value: Int)

private data class LanguageOption(val label: String, val value: AppLanguage)

private data class JumpNotificationModeOption(val label: String, val value: JumpNotificationMode)
private data class ClonedAppUserOption(val label: String, val userId: Int)
// v1.145.12 云规则 TTL 档位 + App 更新检测频率档位
private data class CloudRulesTtlOption(val label: String, val value: Int)
private data class UpdateCheckFrequencyOption(val label: String, val value: String)
@Composable
private fun cloudRulesTtlOptions() = listOf(
    CloudRulesTtlOption(stringResource(R.string.cloud_rules_ttl_1h), 1),
    CloudRulesTtlOption(stringResource(R.string.cloud_rules_ttl_6h), 6),
    CloudRulesTtlOption(stringResource(R.string.cloud_rules_ttl_24h), 24),
    CloudRulesTtlOption(stringResource(R.string.cloud_rules_ttl_7d), 168),
)
@Composable
private fun updateCheckFrequencyOptions() = listOf(
    UpdateCheckFrequencyOption(stringResource(R.string.update_check_frequency_off), Config.UPDATE_CHECK_FREQUENCY_OFF),
    UpdateCheckFrequencyOption(stringResource(R.string.update_check_frequency_launch), Config.UPDATE_CHECK_FREQUENCY_LAUNCH),
    UpdateCheckFrequencyOption(stringResource(R.string.update_check_frequency_daily), Config.UPDATE_CHECK_FREQUENCY_DAILY),
    UpdateCheckFrequencyOption(stringResource(R.string.update_check_frequency_weekly), Config.UPDATE_CHECK_FREQUENCY_WEEKLY),
)

@Composable
private fun logLevelOptions() = listOf(
    LogLevelOption(stringResource(R.string.log_off), Config.LOG_LEVEL_OFF),
    LogLevelOption(stringResource(R.string.log_basic), Config.LOG_LEVEL_BASIC),
    LogLevelOption(stringResource(R.string.log_debug), Config.LOG_LEVEL_DEBUG),
)

// v1.141.39 日志缓冲条数档位（内存环形缓冲上限）：3000~50000，默认 10000
private data class BufferMaxOption(val label: String, val value: Int)
@Composable
private fun logBufferMaxOptions() = listOf(
    BufferMaxOption(stringResource(R.string.log_buffer_max_option, 3_000), 3_000),
    BufferMaxOption(stringResource(R.string.log_buffer_max_option, 5_000), 5_000),
    BufferMaxOption(stringResource(R.string.log_buffer_max_option, 10_000), 10_000),
    BufferMaxOption(stringResource(R.string.log_buffer_max_option, 20_000), 20_000),
    BufferMaxOption(stringResource(R.string.log_buffer_max_option, 50_000), 50_000),
)

@Composable
private fun languageOptions() = listOf(
    LanguageOption(stringResource(R.string.language_system), AppLanguage.System),
    LanguageOption(stringResource(R.string.language_chinese), AppLanguage.Chinese),
    LanguageOption(stringResource(R.string.language_english), AppLanguage.English),
)

@Composable
private fun jumpNotificationModeOptions() = listOf(
    JumpNotificationModeOption(stringResource(R.string.jump_notification_mode_none), JumpNotificationMode.None),
    // The three jump notification choices shown in Settings.
    JumpNotificationModeOption(stringResource(R.string.jump_notification_mode_normal), JumpNotificationMode.Normal),
    JumpNotificationModeOption(stringResource(R.string.jump_notification_mode_live), JumpNotificationMode.Live),
    JumpNotificationModeOption(stringResource(R.string.jump_notification_mode_miui_island), JumpNotificationMode.MiuiIsland),
)

@Composable
private fun clonedAppUserOptions(users: List<AndroidUser>): List<ClonedAppUserOption> {
    return listOf(ClonedAppUserOption(stringResource(R.string.cloned_app_user_auto), Config.CLONED_APP_USER_AUTO)) +
        users.filter { it.id != 0 }.map { user ->
            val name = user.name.ifBlank { stringResource(R.string.cloned_app_user_fallback, user.id) }
            ClonedAppUserOption(stringResource(R.string.cloned_app_user_format, name, user.id), user.id)
        }
}

private val SettingsItemMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp)

/** v1.33 去重窗口选项：0.5s ~ 10s */
private data class DuplicateWindowOption(val label: String, val value: Long)

@Composable
private fun duplicateWindowOptions() = listOf(
    DuplicateWindowOption(stringResource(R.string.duplicate_window_0_5s), 500L),
    DuplicateWindowOption(stringResource(R.string.duplicate_window_1s), 1_000L),
    DuplicateWindowOption(stringResource(R.string.duplicate_window_1_5s), 1_500L),
    DuplicateWindowOption(stringResource(R.string.duplicate_window_2s), 2_000L),
    DuplicateWindowOption(stringResource(R.string.duplicate_window_3s), 3_000L),
    DuplicateWindowOption(stringResource(R.string.duplicate_window_5s), 5_000L),
    DuplicateWindowOption(stringResource(R.string.duplicate_window_10s), 10_000L),
)

/** v1.33 监听状态自检卡：Shizuku/无障碍失效检测 + 一键恢复 */
@Composable
private fun MonitorStatusCard(
    clipboardMonitorMode: ClipboardMonitorMode,
) {
    val context = LocalContext.current
    // Shizuku 模式：pingBinder 检测服务是否可用（无需权限）
    val shizukuAlive = remember(clipboardMonitorMode) {
        if (clipboardMonitorMode == ClipboardMonitorMode.Shizuku) {
            runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
        } else false
    }
    val healthy = when (clipboardMonitorMode) {
        ClipboardMonitorMode.Shizuku -> shizukuAlive
        ClipboardMonitorMode.LSPosed -> true // LSPosed 模块状态无法运行时检测，默认正常
    }
    val okColor = Color(0xFF4CAF50)
    val badColor = Color(0xFFF44336)
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (healthy) okColor else badColor),
                )
                Text(
                    text = stringResource(if (healthy) R.string.monitor_status_ok else R.string.monitor_status_broken),
                    style = MiuixTheme.textStyles.title3,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = stringResource(
                    when (clipboardMonitorMode) {
                        ClipboardMonitorMode.Shizuku -> if (healthy) R.string.monitor_status_shizuku_ok else R.string.monitor_status_shizuku_broken
                        ClipboardMonitorMode.LSPosed -> R.string.monitor_status_lsposed
                    }
                ),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (!healthy) {
                TextButton(
                    text = stringResource(R.string.monitor_status_fix),
                    onClick = {
                        when (clipboardMonitorMode) {
                            ClipboardMonitorMode.Shizuku -> {
                                val shizukuIntent = runCatching {
                                    context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                }.getOrNull()
                                if (shizukuIntent != null) {
                                    shizukuIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(shizukuIntent)
                                } else {
                                    Toast.makeText(context, R.string.monitor_status_no_shizuku_app, Toast.LENGTH_SHORT).show()
                                }
                            }
                            ClipboardMonitorMode.LSPosed -> Unit
                        }
                    },
                )
            }
        }
    }
}

/** v1.142.6d 主题选项（与语言操作逻辑一致，设置页直选） */
private data class ColorModeOption(val label: String, val value: AppColorMode)

@Composable
private fun colorModeOptions() = listOf(
    ColorModeOption(stringResource(R.string.color_mode_system), AppColorMode.System),
    ColorModeOption(stringResource(R.string.color_mode_dark), AppColorMode.Dark),
    ColorModeOption(stringResource(R.string.color_mode_light), AppColorMode.Light),
)
