package io.github.hypercopy.ui.pages.home

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.hypercopy.Config
import io.github.hypercopy.R
import io.github.hypercopy.clipboard.monitor.RootPermission
import io.github.hypercopy.clipboard.monitor.ShizukuPermission
import io.github.hypercopy.clipboard.monitor.ShizukuProcess
import io.github.hypercopy.data.rules.RuleRepository
import io.github.hypercopy.data.settings.SettingsRepository
import io.github.hypercopy.ui.framework.ClipboardMonitorMode
import io.github.libxposed.service.XposedService
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun HomePage(
    modifier: Modifier = Modifier,
    xposedService: XposedService?,
    clipboardMonitorMode: ClipboardMonitorMode,
    onClipboardMonitorModeChange: (ClipboardMonitorMode) -> Unit,
    topContentPadding: Dp = 12.dp,
    bottomContentPadding: Dp = 16.dp,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val systemInfo = remember { homeSystemInfo(context) }
    val ruleRepository = remember { RuleRepository(context) }
    // v1.74 新装一键配置（基于 Shizuku 自动完成通知/省电/后台弹出页面等）
    val settingsRepository = remember { SettingsRepository(context) }
    var onboardingDone by remember { mutableStateOf(settingsRepository.readOnboardingDone()) }
    var showSetupDialog by remember { mutableStateOf(false) }
    // v1.142.1g/h 配置项动态化（按系统适配）：0=待执行 1=配置中 2=已完成 3=失败
    val systemProfile = remember { detectSystemProfile().also { io.github.hypercopy.HyperLog.d("HyperCopy", "一键配置系统识别: ${it.family} | ${it.romLabel}") } }
    val setupItems = remember { buildSetupItems(systemProfile) }
    val setupStates = remember { mutableStateListOf<Int>().apply { repeat(setupItems.size) { add(0) } } }
    fun setupIndex(kind: SetupKind) = setupItems.indexOfFirst { it.kind == kind }
    var setupRunning by remember { mutableStateOf(false) }
    // v1.142.8 返回栈修复：一键配置弹窗打开时返回键先关弹窗（不直接退出 App）
    BackHandler(enabled = showSetupDialog && !setupRunning) {
        showSetupDialog = false
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        setupStates[setupIndex(SetupKind.NOTIFICATION)] = if (granted) 2 else 3
        runShellSetup(context, mainHandler, setupStates, setupItems) {
            setupRunning = false
            settingsRepository.writeOnboardingDone()
            onboardingDone = true
            Toast.makeText(context, R.string.setup_done_toast, Toast.LENGTH_SHORT).show()
        }
    }
    fun startSetup() {
        if (setupRunning) return
        setupRunning = true
        val onSetupFinished = {
            setupRunning = false
            settingsRepository.writeOnboardingDone()
            onboardingDone = true
            Toast.makeText(context, R.string.setup_done_toast, Toast.LENGTH_SHORT).show()
        }
        // ① Shizuku 授权（未授权弹系统确认框，回调后继续）
        setupStates[setupIndex(SetupKind.SHIZUKU)] = 1
        if (ShizukuPermission.isGranted()) {
            setupStates[setupIndex(SetupKind.SHIZUKU)] = 2
            requestNotificationSetup(context, notificationPermissionLauncher, setupStates, setupItems, onSetupFinished)
        } else if (ShizukuPermission.isAvailable()) {
            ShizukuPermission.requestIfNeeded { granted ->
                setupStates[setupIndex(SetupKind.SHIZUKU)] = if (granted) 2 else 3
                requestNotificationSetup(context, notificationPermissionLauncher, setupStates, setupItems, onSetupFinished)
            }
        } else {
            setupStates[setupIndex(SetupKind.SHIZUKU)] = 3
            requestNotificationSetup(context, notificationPermissionLauncher, setupStates, setupItems, onSetupFinished)
        }
    }
    // 修复：规则数量实时刷新（监听规则变更事件，新增/删除/启停后自动更新）
    var enabledRuleCount by remember { mutableStateOf(ruleRepository.readRules().count { it.enabled }) }
    // v1.142.6o D4：总命中次数（主页统计可视化，随规则变更刷新）
    var totalHitCount by remember { mutableStateOf(io.github.hypercopy.data.rules.RuleStatsRepository(context).getAll().values.sum()) }
    LaunchedEffect(ruleRepository) {
        RuleRepository.changes.collect {
            enabledRuleCount = ruleRepository.readRules().count { it.enabled }
            totalHitCount = io.github.hypercopy.data.rules.RuleStatsRepository(context).getAll().values.sum()
        }
    }
    val workMode = clipboardMonitorMode.value
    val isShizukuMode = clipboardMonitorMode == ClipboardMonitorMode.Shizuku
    val monitorModeOptions = clipboardMonitorModeOptions()
    var rootGranted by remember { mutableStateOf(false) }
    var shizukuGranted by remember { mutableStateOf(ShizukuPermission.isGranted()) }
    var batteryUnrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }

    fun refreshPermissionStatus(requestCurrentModePermission: Boolean) {
        batteryUnrestricted = isBatteryUnrestricted(context)
        if (isShizukuMode) {
            ShizukuPermission.waitForAvailable { available ->
                if (!available) {
                    shizukuGranted = false
                } else if (requestCurrentModePermission) {
                    ShizukuPermission.requestIfNeeded { granted -> shizukuGranted = granted }
                } else {
                    shizukuGranted = ShizukuPermission.isGranted()
                }
            }
        } else {
            coroutineScope.launch {
                rootGranted = withContext(Dispatchers.IO) {
                    if (requestCurrentModePermission) RootPermission.request() else RootPermission.isGranted()
                }
            }
        }
    }

    LaunchedEffect(clipboardMonitorMode) {
        refreshPermissionStatus(requestCurrentModePermission = true)
    }

    DisposableEffect(lifecycleOwner, clipboardMonitorMode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissionStatus(requestCurrentModePermission = false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionGranted = if (isShizukuMode) shizukuGranted else rootGranted
    val active = if (isShizukuMode) shizukuGranted else xposedService != null && rootGranted

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = topContentPadding, end = 12.dp, bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatusCard(
                active = active,
                batteryUnrestricted = batteryUnrestricted,
                workMode = workMode,
                enabledRuleCount = enabledRuleCount,
                // v1.142.6o D4：命中统计
                hitCount = totalHitCount,
            )
        }
        // v1.74 新装一键配置卡片 —— v1.144.3 常驻化：一级菜单（主页）常驻入口，替代设置页 rerun 入口（权限被重置后可随时重跑）
        item {
            SetupCard(onSetupClick = { showSetupDialog = true })
        }
        item {
            MonitorModeCard(
                options = monitorModeOptions,
                selectedMode = clipboardMonitorMode,
                permissionGranted = permissionGranted,
                batteryUnrestricted = batteryUnrestricted,
                onModeChange = onClipboardMonitorModeChange,
                onRequestPermission = { refreshPermissionStatus(requestCurrentModePermission = true) },
                onOpenBatterySettings = { openBatterySettings(context) },
            )
        }
        item { InfoCard(systemInfo = systemInfo, xposedService = xposedService, showLsposedVersion = !isShizukuMode) }
        item {
            Card {
                HomeLinkAction(
                    icon = MiuixIcons.Community,
                    title = stringResource(R.string.support_development),
                    summary = stringResource(R.string.support_development_summary),
                    onClick = { uriHandler.openUri(SUPPORT_URL) },
                )
                HomeLinkAction(
                    icon = MiuixIcons.Link,
                    title = stringResource(R.string.open_home_page),
                    summary = stringResource(R.string.open_home_page_summary),
                    onClick = { uriHandler.openUri(HOME_PAGE_URL) },
                )
                // v1.140.18 二改项目地址（置底；不显示具体地址，点击跳转）
                HomeLinkAction(
                    icon = MiuixIcons.Link,
                    title = stringResource(R.string.fork_project),
                    summary = stringResource(R.string.fork_project_summary),
                    onClick = { uriHandler.openUri(FORK_URL) },
                )
            }
        }
    }
    // v1.74 一键配置对话框：实时显示 5 项配置状态
    WindowDialog(
        title = stringResource(R.string.setup_dialog_title),
        summary = stringResource(R.string.setup_dialog_summary),
        show = showSetupDialog,
        onDismissRequest = { if (!setupRunning) showSetupDialog = false },
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // v1.142.1h 显示识别到的系统（用户可确认识别是否正确）
            Text(
                text = stringResource(R.string.setup_system_label, systemProfile.romLabel),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.primary,
            )
            setupItems.forEachIndexed { i, item ->
                SetupStatusRow(stringResource(item.labelRes), setupStates[i])
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                TextButton(
                    text = stringResource(R.string.setup_close),
                    enabled = !setupRunning,
                    onClick = { showSetupDialog = false },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.setup_start),
                    enabled = !setupRunning,
                    onClick = { startSetup() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun StatusCard(active: Boolean, batteryUnrestricted: Boolean, workMode: String, enabledRuleCount: Int, hitCount: Int = 0) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MainStatusCard(
                    active = active,
                    batteryUnrestricted = batteryUnrestricted,
                    modifier = Modifier.weight(1f).height(112.dp),
                )
                StatCard(
                    title = stringResource(R.string.home_work_mode),
                    content = workModeLabel(workMode),
                    modifier = Modifier.weight(1f).height(112.dp),
                )
                StatCard(
                    title = stringResource(R.string.home_rule_count),
                    content = stringResource(R.string.home_enabled_rule_count, enabledRuleCount),
                    modifier = Modifier.weight(1f).height(112.dp),
                )
                // v1.142.6o D4：命中统计（宽屏第 4 格）
                StatCard(
                    title = stringResource(R.string.home_hit_count),
                    content = hitCount.toString(),
                    modifier = Modifier.weight(1f).height(112.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MainStatusCard(
                    active = active,
                    batteryUnrestricted = batteryUnrestricted,
                    modifier = Modifier.weight(1f).aspectRatio(1f),
                )
                Column(
                    modifier = Modifier.weight(1f).aspectRatio(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard(
                        title = stringResource(R.string.home_work_mode),
                        content = workModeLabel(workMode),
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        title = stringResource(R.string.home_rule_count),
                        content = stringResource(R.string.home_enabled_rule_count, enabledRuleCount),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MainStatusCard(active: Boolean, batteryUnrestricted: Boolean, modifier: Modifier = Modifier) {
    val warning = active && !batteryUnrestricted
    val statusColor = when {
        warning -> Color(0xFFFF9F0A)
        active -> Color(0xFF36D167)
        else -> Color(0xFFFF5A52)
    }
    val statusBackground = when {
        warning -> Color(0xFFFFF1D6)
        active -> Color(0xFFDFFAE4)
        else -> Color(0xFFFFE5E3)
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = statusBackground),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize().offset(34.dp, 38.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    modifier = Modifier.size(136.dp),
                    imageVector = MiuixIcons.Copy,
                    contentDescription = null,
                    tint = statusColor.copy(alpha = 0.78f),
                )
            }
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = stringResource(if (active) R.string.status_working else R.string.status_not_active),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF101010),
                )
                Text(
                    text = stringResource(
                        when {
                            warning -> R.string.status_battery_abnormal
                            active -> R.string.status_module_connected
                            else -> R.string.status_module_disconnected
                        },
                    ),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (warning) Color(0xFFE07000) else Color(0xFF2F3A32).copy(alpha = 0.78f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun MonitorModeCard(
    options: List<ClipboardMonitorModeOption>,
    selectedMode: ClipboardMonitorMode,
    permissionGranted: Boolean,
    batteryUnrestricted: Boolean,
    onModeChange: (ClipboardMonitorMode) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    val showPermissionStatus = selectedMode == ClipboardMonitorMode.Shizuku || !permissionGranted
    val showStatusRows = showPermissionStatus || !batteryUnrestricted

    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = if (showStatusRows) 14.dp else 0.dp)) {
            OverlayDropdownPreference(
                title = stringResource(R.string.clipboard_monitor_mode),
                summary = stringResource(R.string.clipboard_monitor_mode_summary),
                items = options.map { it.label },
                selectedIndex = options.indexOfFirst { it.value == selectedMode }.coerceAtLeast(0),
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                onSelectedIndexChange = { onModeChange(options[it].value) },
            )
            if (showPermissionStatus) {
                StatusActionRow(
                    title = stringResource(if (selectedMode == ClipboardMonitorMode.Shizuku) R.string.permission_shizuku_status else R.string.permission_root_status),
                    content = stringResource(if (permissionGranted) R.string.permission_granted else R.string.permission_missing),
                    showAction = !permissionGranted,
                    actionContentDescription = stringResource(R.string.action_request_permission),
                    onActionClick = onRequestPermission,
                )
            }
            if (!batteryUnrestricted) {
                StatusActionRow(
                    title = stringResource(R.string.battery_status),
                    content = stringResource(R.string.status_battery_abnormal),
                    showAction = true,
                    actionContentDescription = stringResource(R.string.action_battery_settings),
                    onActionClick = onOpenBatterySettings,
                )
            }
        }
    }
}

@Composable
private fun HomeLinkAction(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun StatusActionRow(
    title: String,
    content: String,
    showAction: Boolean,
    actionContentDescription: String,
    onActionClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            InfoText(title = title, content = content, bottomPadding = 0.dp)
        }
        if (showAction) {
            IconButton(
                onClick = onActionClick,
                minWidth = 32.dp,
                minHeight = 32.dp,
                cornerRadius = 16.dp,
                backgroundColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.08f),
                modifier = Modifier.padding(start = 10.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = actionContentDescription,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun StatCard(title: String, content: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = content,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun InfoCard(systemInfo: HomeSystemInfo, xposedService: XposedService?, showLsposedVersion: Boolean) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            val unknown = stringResource(R.string.info_unknown)
            InfoText(title = stringResource(R.string.info_system_version), content = systemInfo.systemVersion)
            InfoText(title = stringResource(R.string.info_app_version), content = systemInfo.appVersion)
            InfoText(title = stringResource(R.string.info_android_version), content = systemInfo.androidVersion)
            if (showLsposedVersion) {
                InfoText(title = stringResource(R.string.info_lsposed_version), content = lsposedVersion(xposedService).ifBlank { unknown })
            }
            InfoText(title = stringResource(R.string.info_device_model), content = systemInfo.deviceModel.ifBlank { unknown }, bottomPadding = 0.dp)
        }
    }
}

@Composable
private fun InfoText(title: String, content: String, bottomPadding: Dp = 24.dp) {
    Text(
        text = title,
        fontSize = MiuixTheme.textStyles.headline1.fontSize,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface,
    )
    Text(
        text = content,
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 2.dp, bottom = bottomPadding),
    )
}

private data class HomeSystemInfo(
    val systemVersion: String,
    val appVersion: String,
    val androidVersion: String,
    val deviceModel: String,
)

private fun homeSystemInfo(context: Context): HomeSystemInfo {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
    val versionName = packageInfo.versionName ?: "unknown"
    return HomeSystemInfo(
        systemVersion = Build.DISPLAY,
        appVersion = "$versionName ($versionCode)",
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        deviceModel = listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim(),
    )
}

private fun lsposedVersion(service: XposedService?): String {
    if (service == null) return ""
    return runCatching {
        "${service.frameworkName} ${service.frameworkVersion} (${service.frameworkVersionCode}), API ${service.apiVersion}"
    }.getOrDefault("")
}

private fun isBatteryUnrestricted(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}
private fun openBatterySettings(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    val intents = listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in intents) {
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }.onFailure { throwable ->
            if (throwable !is ActivityNotFoundException) return@onFailure
        }
    }
}

private data class ClipboardMonitorModeOption(val label: String, val value: ClipboardMonitorMode)

@Composable
private fun clipboardMonitorModeOptions() = listOf(
    ClipboardMonitorModeOption(stringResource(R.string.clipboard_monitor_mode_lsposed), ClipboardMonitorMode.LSPosed),
    ClipboardMonitorModeOption(stringResource(R.string.clipboard_monitor_mode_shizuku), ClipboardMonitorMode.Shizuku),
)

@Composable
private fun workModeLabel(value: String): String = stringResource(
    if (value == Config.CLIPBOARD_MONITOR_MODE_SHIZUKU) {
        R.string.clipboard_monitor_mode_shizuku
    } else {
        R.string.clipboard_monitor_mode_lsposed
    },
)

private const val HOME_PAGE_URL = "https://hypercopy.1812z.top/"
private const val SUPPORT_URL = "https://hypercopy.1812z.top/donors.html"
// v1.140.18 二改项目地址（本仓库）
private const val FORK_URL = "https://github.com/snacks-yummy/HyperCopy-snack"

// ===== v1.74 新装一键配置 =====

/** 一键配置卡片：v1.144.3 常驻（一级菜单入口，替代设置页 rerun；点击打开配置弹窗） */
@Composable
private fun SetupCard(onSetupClick: () -> Unit) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.setup_card_title), style = MiuixTheme.textStyles.headline1)
            Text(
                text = stringResource(R.string.setup_card_summary),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.setup_card_action),
                    onClick = onSetupClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** 配置状态行：名称 + 状态（0待执行/1配置中/2已完成/3失败） */
@Composable
private fun SetupStatusRow(title: String, state: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = MiuixTheme.textStyles.body2)
        Text(
            text = stringResource(
                when (state) {
                    1 -> R.string.setup_item_running
                    2 -> R.string.setup_item_ok
                    3 -> R.string.setup_item_failed
                    else -> R.string.setup_item_pending
                },
            ),
            style = MiuixTheme.textStyles.body2,
            color = when (state) {
                1 -> Color(0xFF4A90D9)
                2 -> Color(0xFF00B578)
                3 -> Color(0xFFFF5A52)
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
        )
    }
}

/** ② 通知权限：Shizuku 已授权时静默授予（无系统弹窗）；否则弹系统授权框；已授予直接进入 shell 配置 */
private fun requestNotificationSetup(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<String>,
    states: androidx.compose.runtime.snapshots.SnapshotStateList<Int>,
    items: List<SetupItem>,
    onDone: () -> Unit,
) {
    val idx = items.indexOfFirst { it.kind == SetupKind.NOTIFICATION }
    states[idx] = 1
    // v1.144.3 静默授予优先（Shizuku 已授权时 appops set，无系统弹窗；系统忽略则回退弹窗）
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || tryGrantNotificationSilently(context)) {
        states[idx] = 2
        runShellSetup(context, Handler(Looper.getMainLooper()), states, items, onDone)
        return
    }
    runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        .onFailure {
            states[idx] = 3
            runShellSetup(context, Handler(Looper.getMainLooper()), states, items, onDone)
        }
}

/**
 * v1.144.3 通知权限静默授予（一键配置/模式切换/通知开关共用）：
 * Shizuku 已授权 → PrivilegedShell 静默 appops set POST_NOTIFICATION allow（无系统弹窗）；
 * 已授予或 < Android 13 直接成功；Shizuku 未授权或系统静默忽略（set 后仍未授予）→ false，调用方回退系统弹窗。
 */
internal fun tryGrantNotificationSilently(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return true
    if (!ShizukuPermission.isGranted()) return false
    val result = io.github.hypercopy.clipboard.privileged.PrivilegedShell.run(
        io.github.hypercopy.data.settings.SettingsRepository(context),
        "cmd appops set ${context.packageName} POST_NOTIFICATION allow",
    )
    if (result.exitCode != 0) {
        io.github.hypercopy.HyperLog.d("HyperCopy", "通知权限静默授予失败 exit=${result.exitCode}: ${result.output.take(120)}")
        return false
    }
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

/** ③④⑤⑥ 省电无限制 + 后台弹出页面 + 自启动 + 获取应用列表：Shizuku shell 静默执行（后台线程），
 *  按 items 动态执行（v1.142.1g 按系统适配），完成后回调主线程 */
private fun runShellSetup(
    context: Context,
    mainHandler: Handler,
    states: androidx.compose.runtime.snapshots.SnapshotStateList<Int>,
    items: List<SetupItem>,
    onDone: () -> Unit,
) {
    thread(name = "HyperCopyOneTapSetup") {
        val pkg = context.packageName
        val shell = io.github.hypercopy.clipboard.privileged.PrivilegedShell
        val settingsRepo = io.github.hypercopy.data.settings.SettingsRepository(context)
        fun update(kind: SetupKind, value: Int) {
            val idx = items.indexOfFirst { it.kind == kind }
            if (idx >= 0) mainHandler.post { states[idx] = value }
        }
        // 查询命令：exit 0 且（可选）输出含关键字 → 已授权
        fun granted(query: String, outputContains: String? = null): Boolean {
            val r = shell.run(settingsRepo, query)
            return r.exitCode == 0 && (outputContains == null || r.output.contains(outputContains))
        }
        fun grant(set: String, fallback: String? = null): Boolean {
            return runShellCommand(context, set) || (fallback != null && runShellCommand(context, fallback))
        }
        // 省电无限制（电池优化白名单）——已授权自动跳过
        if (items.any { it.kind == SetupKind.BATTERY }) {
            update(SetupKind.BATTERY, 1)
            val batteryOk = granted("dumpsys deviceidle whitelist | grep -q $pkg") ||
                granted("cmd deviceidle whitelist | grep -q $pkg") ||
                grant("dumpsys deviceidle whitelist +$pkg", "cmd deviceidle whitelist +$pkg")
            // v1.142.1f HyperOS3 省电策略界面数据源：miui_power_save_whitelist（system/secure 双写追加）
            val miuiPowerOk = grantMiuiPowerWhitelist(context, pkg)
            update(SetupKind.BATTERY, if (batteryOk && miuiPowerOk) 2 else 3)
        }
        // 后台弹出页面：标准 op 10021 + MIUI 私有 10024 双保险——已授权自动跳过
        if (items.any { it.kind == SetupKind.BACKGROUND }) {
            update(SetupKind.BACKGROUND, 1)
            val backgroundOk = granted("appops get $pkg 10021", "allow") ||
                granted("appops get $pkg 10024", "allow") ||
                grant("appops set $pkg 10021 allow", "appops set $pkg 10024 allow")
            update(SetupKind.BACKGROUND, if (backgroundOk) 2 else 3)
        }
        // 自启动：MIUI 私有 op 10050 + 10051 兜底——已授权自动跳过
        if (items.any { it.kind == SetupKind.AUTOSTART }) {
            update(SetupKind.AUTOSTART, 1)
            val autostartOk = granted("appops get $pkg 10050", "allow") ||
                granted("appops get $pkg 10051", "allow") ||
                grant("appops set $pkg 10050 allow", "appops set $pkg 10051 allow")
            update(SetupKind.AUTOSTART, if (autostartOk) 2 else 3)
        }
        // 获取应用列表：MIUIOP(10045)（实测手动开启后 ignore→allow）
        if (items.any { it.kind == SetupKind.APPLIST }) {
            update(SetupKind.APPLIST, 1)
            val appListOk = granted("appops get $pkg 10045", "allow") ||
                runShellCommand(context, "appops set $pkg 10045 allow")
            update(SetupKind.APPLIST, if (appListOk) 2 else 3)
        }
        // HyperOS3「设置相关」UI 权限（用户实测手动全开后的 appops 状态复刻）：
        // 10004/10008/10017/10020/10053=allow（桌面快捷方式/锁屏显示/动态壁纸等），10022=foreground
        if (items.any { it.kind == SetupKind.MIUI_EXTRA }) {
            update(SetupKind.MIUI_EXTRA, 1)
            val extraUiOk = listOf("10004", "10008", "10017", "10020", "10053").all {
                granted("appops get $pkg $it", "allow") || runShellCommand(context, "appops set $pkg $it allow")
            } && (granted("appops get $pkg 10022", "foreground") ||
                runShellCommand(context, "appops set $pkg 10022 foreground"))
            update(SetupKind.MIUI_EXTRA, if (extraUiOk) 2 else 3)
        }
        mainHandler.post(onDone)
    }
}
/** v1.142.1g 一键配置权限项（按系统动态适配） */
enum class SetupKind { SHIZUKU, NOTIFICATION, BATTERY, BACKGROUND, AUTOSTART, APPLIST, MIUI_EXTRA }

data class SetupItem(val labelRes: Int, val kind: SetupKind)

/** v1.142.1h ROM 家族识别：HyperOS（小米新）/ MIUI（小米旧）/ 其他（原生或非小米 ROM） */
enum class RomFamily { HYPEROS, MIUI, AOSP_OTHER }

data class SystemProfile(
    val family: RomFamily,
    val romLabel: String,   // 展示：HyperOS 3.0 · Android 16
    val androidSdk: Int,
)

/** 识别当前系统：优先 fingerprint 的 OSx.y（HyperOS），其次 Vxx（旧 MIUI），厂商非小米则其他 */
private fun detectSystemProfile(): SystemProfile {
    val manufacturer = "${android.os.Build.MANUFACTURER} ${android.os.Build.BRAND}".lowercase()
    val isXiaomi = manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")
    if (!isXiaomi) {
        return SystemProfile(RomFamily.AOSP_OTHER, "Android ${android.os.Build.VERSION.RELEASE}", android.os.Build.VERSION.SDK_INT)
    }
    val fp = android.os.Build.FINGERPRINT.lowercase()
    val hyperVer = Regex("os(\\d+\\.\\d+)").find(fp)?.groupValues?.get(1)
    val miuiVer = Regex("v(\\d{1,2}(\\.\\d+){0,3})").find(fp)?.groupValues?.get(1)
    return if (hyperVer != null) {
        SystemProfile(RomFamily.HYPEROS, "HyperOS $hyperVer · Android ${android.os.Build.VERSION.RELEASE}", android.os.Build.VERSION.SDK_INT)
    } else {
        SystemProfile(RomFamily.MIUI, "MIUI ${miuiVer.orEmpty()} · Android ${android.os.Build.VERSION.RELEASE}", android.os.Build.VERSION.SDK_INT)
    }
}

/** 按识别到的系统构建一键配置项（v1.142.1h）：HyperOS/MIUI 全量 8 项；其他 ROM 仅通用 3 项 */
private fun buildSetupItems(profile: SystemProfile): List<SetupItem> {
    val items = mutableListOf(
        SetupItem(R.string.setup_item_shizuku, SetupKind.SHIZUKU),
        SetupItem(R.string.setup_item_notification, SetupKind.NOTIFICATION),
        SetupItem(R.string.setup_item_battery, SetupKind.BATTERY),
    )
    if (profile.family == RomFamily.HYPEROS || profile.family == RomFamily.MIUI) {
        items += SetupItem(R.string.setup_item_background, SetupKind.BACKGROUND)
        items += SetupItem(R.string.setup_item_autostart, SetupKind.AUTOSTART)
        items += SetupItem(R.string.setup_item_applist, SetupKind.APPLIST)
        items += SetupItem(R.string.setup_item_miui_extra, SetupKind.MIUI_EXTRA)
    }
    return items
}
/** v1.142.1f HyperOS3 省电策略界面数据源双写：settings system/secure 的 miui_power_save_whitelist 追加包名
 *  （保留原值、幂等——已含则跳过，避免重复逗号） */
private fun grantMiuiPowerWhitelist(context: Context, pkg: String): Boolean {
    fun addTo(namespace: String): Boolean {
        return runShellCommand(
            context,
            "if ! settings get $namespace miui_power_save_whitelist | grep -q $pkg; then " +
                "settings put $namespace miui_power_save_whitelist \"\$(settings get $namespace miui_power_save_whitelist),$pkg\"; fi",
        )
    }
    return addTo("system") && addTo("secure")
}
/** Shizuku shell 执行（v1.142.1d 重构）：委托 PrivilegedShell（其 waitForExit 用 try exitValue 轮询，
 *  兼容 ShizukuProcess 的异常语义——此前直接 waitFor/isAlive 判定在 ShizukuProcess 上均不可靠，
 *  导致 appops 命令假失败/随机失败）。成功=exit 0。 */
private fun runShellCommand(context: Context, command: String): Boolean {
    if (!ShizukuPermission.isGranted()) {
        io.github.hypercopy.HyperLog.d("HyperCopy", "一键配置跳过: Shizuku 未授权: $command")
        return false
    }
    val result = io.github.hypercopy.clipboard.privileged.PrivilegedShell.run(
        io.github.hypercopy.data.settings.SettingsRepository(context),
        command,
    )
    if (result.exitCode != 0) {
        io.github.hypercopy.HyperLog.d("HyperCopy", "一键配置失败 exit=${result.exitCode}: $command → ${result.output.take(120)}")
    }
    return result.exitCode == 0
}
