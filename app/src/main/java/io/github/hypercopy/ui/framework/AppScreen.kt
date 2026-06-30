package io.github.hypercopy.ui.framework

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.hypercopy.App
import io.github.hypercopy.Config
import io.github.hypercopy.R
import io.github.hypercopy.clipboard.monitor.ClipboardMonitorController
import io.github.hypercopy.data.settings.SettingsRepository
import io.github.hypercopy.data.systemlink.AndroidUser
import io.github.hypercopy.data.systemlink.SystemLinkRepository
import io.github.hypercopy.data.update.UpdateCheckResult
import io.github.hypercopy.data.update.UpdateRepository
import io.github.hypercopy.ui.activities.AppListActivity
import io.github.hypercopy.ui.activities.ThemeSettingsActivity
import io.github.hypercopy.ui.pages.cloudrules.CloudRulesPage
import io.github.hypercopy.ui.pages.home.HomePage
import io.github.hypercopy.ui.pages.rules.RulesPage
import io.github.hypercopy.ui.pages.settings.SettingsPage
import io.github.hypercopy.ui.pages.settings.SettingsSubPage
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AppRecording
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Carrier
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayCascadingListPopup
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** v1.140.18 全局导航状态：MainActivity 兜底返回回调与 Compose 共享（Android 16 predictive back 手势返回） */
object AppNav {
    val tabFlow = MutableStateFlow(Tab.Home)
    val subPageFlow = MutableStateFlow<SettingsSubPage?>(null)
}

enum class Tab(val icon: androidx.compose.ui.graphics.vector.ImageVector, val labelRes: Int) {
    Home(MiuixIcons.Backup, R.string.tab_home),
    Copy(MiuixIcons.Carrier, R.string.tab_cloud_rules),
    Rules(MiuixIcons.AppRecording, R.string.tab_rules),
    Settings(MiuixIcons.Settings, R.string.tab_settings),
}

@Composable
fun AppScreen(
    colorMode: AppColorMode = AppColorMode.System,
    onColorModeChange: (AppColorMode) -> Unit = {},
    onAppLanguageChange: (AppLanguage) -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val settingsRepository = remember { SettingsRepository(context.applicationContext) }
    val updateRepository = remember { UpdateRepository(context.applicationContext) }
    val systemLinkRepository = remember { SystemLinkRepository(context.applicationContext) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    val shizukuNotificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        ClipboardMonitorController.startForCurrentMode(context.applicationContext)
    }
    val tabs = remember { Tab.entries.toList() }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    val selectedTab by AppNav.tabFlow.collectAsState()
    var xposedService by remember { mutableStateOf(App.xposedService) }
    var logLevel by remember { mutableIntStateOf(settingsRepository.readLogLevel()) }
    var autoCheckUpdate by remember { mutableStateOf(settingsRepository.readAutoCheckUpdate()) }
    var hideFromRecents by remember { mutableStateOf(settingsRepository.readHideFromRecents()) }
    var desktopIconHidden by remember { mutableStateOf(settingsRepository.readDesktopIconHidden()) }
    var detectClonedApp by remember { mutableStateOf(settingsRepository.readDetectClonedApp()) }
    var clonedAppUserId by remember { mutableIntStateOf(settingsRepository.readClonedAppUserId()) }
    var clonedAppUsers by remember { mutableStateOf<List<AndroidUser>>(emptyList()) }
    var miuiIslandBypassRestriction by remember { mutableStateOf(settingsRepository.readMiuiIslandBypassRestriction()) }
    var appLanguage by remember { mutableStateOf(appLanguageFromValue(settingsRepository.readAppLanguage())) }
    var clipboardMonitorMode by remember {
        mutableStateOf(clipboardMonitorModeFromValue(settingsRepository.readClipboardMonitorMode()))
    }
    var jumpNotificationMode by remember {
        mutableStateOf(jumpNotificationModeFromValue(settingsRepository.readJumpNotificationMode()))
    }
    // v1.141 文本类("文本类"规则：取件码/验证码)独立通知渠道
    var textNotificationMode by remember {
        mutableStateOf(jumpNotificationModeFromValue(settingsRepository.readTextNotificationMode()))
    }
    var notifyUnmatched by remember { mutableStateOf(settingsRepository.readNotifyUnmatched()) }
    var notifyPickupCode by remember { mutableStateOf(settingsRepository.readNotifyPickupCode()) }
    var notifyIncludePlatform by remember { mutableStateOf(settingsRepository.readNotifyIncludePlatform()) }
    var matchDebugLog by remember { mutableStateOf(settingsRepository.readMatchDebugLog()) }
    var monitorEnabled by remember { mutableStateOf(settingsRepository.readMonitorEnabled()) }
    var showHitToast by remember { mutableStateOf(settingsRepository.readShowHitToast()) }
    var cloudExpressDetect by remember { mutableStateOf(settingsRepository.readCloudExpressDetect()) }
    var expressDirectJump by remember { mutableStateOf(settingsRepository.readExpressDirectJump()) }
    var cainiaoAutoConfirm by remember { mutableStateOf(settingsRepository.readCainiaoAutoConfirm()) }
    var cainiaoAutoExpand by remember { mutableStateOf(settingsRepository.readCainiaoAutoExpand()) }
    var schemeDirectJump by remember { mutableStateOf(settingsRepository.readSchemeDirectJump()) }
    var jumpFallbackWeb by remember { mutableStateOf(settingsRepository.readJumpFallbackWeb()) }
    var jumpPrecheck by remember { mutableStateOf(settingsRepository.readJumpPrecheck()) }
    var duplicateWindowMillis by remember { mutableStateOf(settingsRepository.readDuplicateWindowMillis()) }
    var autoActivate by remember { mutableStateOf(settingsRepository.readAutoActivate()) }
    var updateDialog by remember { mutableStateOf<UpdateDialogState?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val listener: (XposedService?) -> Unit = { service -> xposedService = service }
        App.addServiceListener(listener)
        onDispose { App.removeServiceListener(listener) }
    }

    LaunchedEffect(pagerState.settledPage) {
        AppNav.tabFlow.value = tabs[pagerState.settledPage]
    }

    LaunchedEffect(Unit) {
        if (autoCheckUpdate) {
            checkingUpdate = true
            val result = withContext(Dispatchers.IO) { updateRepository.checkLatestRelease() }
            checkingUpdate = false
            if (result is UpdateCheckResult.HasUpdate) {
                updateDialog = UpdateDialogState(
                    title = context.getString(R.string.update_new_version),
                    message = context.getString(
                        R.string.update_current_latest_version,
                        result.currentVersion,
                        result.release.version,
                    ),
                    url = result.release.url,
                    showOpenButton = true,
                )
            }
        }
    }

    LaunchedEffect(clipboardMonitorMode) {
        clonedAppUsers = withContext(Dispatchers.IO) { systemLinkRepository.readUsers() }
        if (clonedAppUserId != Config.CLONED_APP_USER_AUTO && clonedAppUsers.none { it.id == clonedAppUserId && it.id != 0 }) {
            clonedAppUserId = Config.CLONED_APP_USER_AUTO
            settingsRepository.persistClonedAppUserId(clonedAppUserId)
        }
    }

    fun checkUpdate(showNoUpdate: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) { updateRepository.checkLatestRelease() }
            checkingUpdate = false
            when (result) {
                is UpdateCheckResult.HasUpdate -> updateDialog = UpdateDialogState(
                    title = context.getString(R.string.update_new_version),
                    message = context.getString(
                        R.string.update_current_latest_version,
                        result.currentVersion,
                        result.release.version,
                    ),
                    url = result.release.url,
                    showOpenButton = true,
                )

                is UpdateCheckResult.NoUpdate -> if (showNoUpdate) {
                    updateDialog = UpdateDialogState(
                        title = context.getString(R.string.update_latest_version),
                        message = context.getString(R.string.update_current_version, result.currentVersion),
                    )
                }

                is UpdateCheckResult.Failed -> updateDialog = UpdateDialogState(
                    title = context.getString(R.string.update_check_failed),
                    message = localizedUpdateFailure(context.getString(R.string.update_check_failed), result.message),
                )
            }
        }
    }

    val backgroundColor = appBackground(colorMode)
    val settingsSubPage by AppNav.subPageFlow.collectAsState()
    // 返回统一由 MainActivity 兜底 OnBackPressedCallback 处理（读 AppNav 状态）
    LaunchedEffect(selectedTab) {
        val idx = tabs.indexOf(selectedTab)
        if (pagerState.currentPage != idx) pagerState.animateScrollToPage(idx)
    }
    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        bottomBar = {
            BottomNavigation(tabs, selectedTab) { _, tab ->
                AppNav.tabFlow.value = tab
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(paddingValues),
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), userScrollEnabled = false) { page ->
                when (tabs[page]) {
                    Tab.Home -> {
                        val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = stringResource(R.string.tab_home),
                                    largeTitle = stringResource(R.string.tab_home),
                                    scrollBehavior = scrollBehavior,
                                )
                            },
                            contentWindowInsets = WindowInsets.statusBars,
                        ) { pagePadding ->
                            HomePage(
                                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                                xposedService = xposedService,
                                clipboardMonitorMode = clipboardMonitorMode,
                                onClipboardMonitorModeChange = {
                                    clipboardMonitorMode = it
                                    settingsRepository.persistClipboardMonitorMode(it.value)
                                    if (it == ClipboardMonitorMode.Shizuku &&
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        shizukuNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        ClipboardMonitorController.onModeChanged(context.applicationContext, it.value)
                                    }
                                },
                                topContentPadding = pagePadding.calculateTopPadding() + 12.dp,
                                bottomContentPadding = pagePadding.calculateBottomPadding() + 16.dp,
                            )
                        }
                    }

                    Tab.Copy -> {
                        val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
                        var showCloudMenu by remember { mutableStateOf(false) }
                        var showInstalledOnly by remember { mutableStateOf(false) }
                        var cloudSource by remember { mutableStateOf(settingsRepository.readCloudSource()) }
                        var refreshTrigger by remember { mutableIntStateOf(0) }
                        var downloadInstalledTrigger by remember { mutableIntStateOf(0) }

                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = stringResource(R.string.tab_cloud_rules),
                                    largeTitle = stringResource(R.string.tab_cloud_rules),
                                    scrollBehavior = scrollBehavior,
                                    actions = {
                                        IconButton(onClick = { refreshTrigger++ }) {
                                            Icon(
                                                imageVector = MiuixIcons.Refresh,
                                                contentDescription = stringResource(R.string.action_refresh),
                                            )
                                        }
                                        Box {
                                            // v1.67 云规则菜单按钮改文字（ListView 图标语义不明）
                                            TextButton(text = stringResource(R.string.cloud_menu_short), onClick = { showCloudMenu = true })
                                            OverlayCascadingListPopup(
                                                show = showCloudMenu,
                                                entries = listOf(
                                                    DropdownEntry(
                                                        items = listOf(
                                                            DropdownItem(
                                                                text = stringResource(R.string.cloud_menu_show_installed_only),
                                                                selected = showInstalledOnly,
                                                                onClick = {
                                                                    showCloudMenu = false
                                                                    showInstalledOnly = !showInstalledOnly
                                                                },
                                                            ),
                                                            DropdownItem(
                                                                text = stringResource(R.string.cloud_menu_download_installed),
                                                                onClick = {
                                                                    showCloudMenu = false
                                                                    downloadInstalledTrigger++
                                                                },
                                                            ),
                                                        ),
                                                    ),
                                                ),
                                                onDismissRequest = { showCloudMenu = false },
                                            )

                                        }
                                    },
                                )
                            },
                            contentWindowInsets = WindowInsets.statusBars,
                        ) { pagePadding ->
                            CloudRulesPage(
                                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                                topContentPadding = pagePadding.calculateTopPadding(),
                                bottomContentPadding = 16.dp,
                                showInstalledOnly = showInstalledOnly,
                                cloudSource = cloudSource,
                                refreshTrigger = refreshTrigger,
                                downloadInstalledTrigger = downloadInstalledTrigger,
                                onSourceChange = { key ->
                                    settingsRepository.persistCloudSource(key)
                                    cloudSource = key
                                },
                            )
                        }
                    }

                    Tab.Rules -> {
                        val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
                        var showImportDialog by remember { mutableStateOf(false) }
                        var showRulesMenu by remember { mutableStateOf(false) }
                        var ruleSortMode by remember { mutableStateOf(false) }
                        var ruleEditMode by remember { mutableStateOf(false) }
                        var ruleActionsAvailable by remember { mutableStateOf(false) }
                        var systemLinkUserId by remember { mutableStateOf(settingsRepository.readSystemLinkUserId()) }
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = stringResource(R.string.tab_rules),
                                    largeTitle = stringResource(R.string.tab_rules),
                                    scrollBehavior = scrollBehavior,
                                    actions = {
                                        if (ruleActionsAvailable) {
                                            // v1.66 顶栏按钮带文字（纯图标用户看不懂）
                                            TextButton(text = stringResource(R.string.action_sort_short), onClick = {
                                                ruleEditMode = false
                                                ruleSortMode = true
                                            })
                                            TextButton(text = stringResource(R.string.action_edit_short), onClick = {
                                                ruleSortMode = false
                                                ruleEditMode = true
                                            })
                                        }
                                        if (!ruleActionsAvailable) {
                                            Box {
                                                TextButton(text = stringResource(R.string.system_user_short), onClick = { showRulesMenu = true })
                                                OverlayCascadingListPopup(
                                                    show = showRulesMenu,
                                                    entries = listOf(
                                                        DropdownEntry(
                                                            items = clonedAppUsers.map { user ->
                                                                DropdownItem(
                                                                    text = user.name.ifBlank { context.getString(R.string.cloned_app_user_fallback, user.id) } + " user ${user.id}",
                                                                    selected = systemLinkUserId == user.id,
                                                                    onClick = {
                                                                        showRulesMenu = false
                                                                        systemLinkUserId = user.id
                                                                        settingsRepository.persistSystemLinkUserId(user.id)
                                                                    },
                                                                )
                                                            },
                                                        ),
                                                    ),
                                                    onDismissRequest = { showRulesMenu = false },
                                                )
                                            }
                                        }
                                        TextButton(text = stringResource(R.string.action_import_short), onClick = { showImportDialog = true })
                                    },
                                )
                            },
                            contentWindowInsets = WindowInsets.statusBars,
                        ) { pagePadding ->
                            RulesPage(
                                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                                showImportDialog = showImportDialog,
                                onDismissImportDialog = { showImportDialog = false },
                                sortMode = ruleSortMode,
                                onSortModeChange = { ruleSortMode = it },
                                editMode = ruleEditMode,
                                onEditModeChange = { ruleEditMode = it },
                                onRuleActionsAvailableChange = { ruleActionsAvailable = it },
                                topContentPadding = pagePadding.calculateTopPadding(),
                                bottomContentPadding = pagePadding.calculateBottomPadding() + 16.dp,
                                systemLinkUserId = systemLinkUserId,
                            )
                        }
                    }

                    Tab.Settings -> {
                        val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
                        Scaffold(
                            topBar = {
                                // v1.140.18 子页时隐藏大标题栏（largeTitle 悬浮绘制会与子页内容重叠/残留）
                                if (settingsSubPage == null) {
                                    TopAppBar(
                                        title = stringResource(R.string.tab_settings),
                                        largeTitle = stringResource(R.string.tab_settings),
                                        scrollBehavior = scrollBehavior,
                                    )
                                }
                            },
                            contentWindowInsets = WindowInsets.statusBars,
                        ) { pagePadding ->
                            SettingsPage(
                                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                                logLevel = logLevel,
                                autoCheckUpdate = autoCheckUpdate,
                                hideFromRecents = hideFromRecents,
                                desktopIconHidden = desktopIconHidden,
                                detectClonedApp = detectClonedApp,
                                clonedAppUserId = clonedAppUserId,
                                clonedAppUsers = clonedAppUsers,
                                miuiIslandBypassRestriction = miuiIslandBypassRestriction,
                                appLanguage = appLanguage,
                                clipboardMonitorMode = clipboardMonitorMode,
                                jumpNotificationMode = jumpNotificationMode,
                                textNotificationMode = textNotificationMode,
                                notifyUnmatched = notifyUnmatched,
                                notifyPickupCode = notifyPickupCode,
                                notifyIncludePlatform = notifyIncludePlatform,
                                matchDebugLog = matchDebugLog,
                                monitorEnabled = monitorEnabled,
                                showHitToast = showHitToast,
                                cloudExpressDetect = cloudExpressDetect,
                                expressDirectJump = expressDirectJump,
                                cainiaoAutoConfirm = cainiaoAutoConfirm,
                                cainiaoAutoExpand = cainiaoAutoExpand,
                                schemeDirectJump = schemeDirectJump,
                                jumpFallbackWeb = jumpFallbackWeb,
                                jumpPrecheck = jumpPrecheck,
                                duplicateWindowMillis = duplicateWindowMillis,
                                autoActivate = autoActivate,
                                onLogLevelChange = {
                                    logLevel = it
                                    settingsRepository.persistLogLevel(it)
                                },
                                onAutoCheckUpdateChange = {
                                    autoCheckUpdate = it
                                    settingsRepository.persistAutoCheckUpdate(it)
                                },
                                onHideFromRecentsChange = {
                                    hideFromRecents = it
                                    settingsRepository.persistHideFromRecents(it)
                                    context.findMainActivity()?.updateRecentsVisibility(it)
                                },
                                onDesktopIconHiddenChange = {
                                    desktopIconHidden = it
                                    settingsRepository.persistDesktopIconHidden(it)
                                },
                                onDetectClonedAppChange = {
                                    detectClonedApp = it
                                    settingsRepository.persistDetectClonedApp(it)
                                },
                                onClonedAppUserIdChange = {
                                    clonedAppUserId = it
                                    settingsRepository.persistClonedAppUserId(it)
                                },
                                onMiuiIslandBypassRestrictionChange = {
                                    miuiIslandBypassRestriction = it
                                    settingsRepository.persistMiuiIslandBypassRestriction(it)
                                },
                                onAppLanguageChange = {
                                    appLanguage = it
                                    settingsRepository.persistAppLanguage(it.value)
                                    onAppLanguageChange(it)
                                },
                                onJumpNotificationModeChange = {
                                    jumpNotificationMode = it
                                    settingsRepository.persistJumpNotificationMode(it.value)
                                    if (it != JumpNotificationMode.None && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                onTextNotificationModeChange = {
                                    textNotificationMode = it
                                    settingsRepository.persistTextNotificationMode(it.value)
                                    if (it != JumpNotificationMode.None && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                onNotifyUnmatchedChange = {
                                    notifyUnmatched = it
                                    settingsRepository.persistNotifyUnmatched(it)
                                },
                                onNotifyPickupCodeChange = {
                                    notifyPickupCode = it
                                    settingsRepository.persistNotifyPickupCode(it)
                                },
                                onNotifyIncludePlatformChange = {
                                    notifyIncludePlatform = it
                                    settingsRepository.persistNotifyIncludePlatform(it)
                                },
                                onMatchDebugLogChange = {
                                    matchDebugLog = it
                                    settingsRepository.persistMatchDebugLog(it)
                                },
                                onMonitorEnabledChange = {
                                    monitorEnabled = it
                                    settingsRepository.persistMonitorEnabled(it)
                                },
                                onShowHitToastChange = {
                                    showHitToast = it
                                    settingsRepository.persistShowHitToast(it)
                                },
                                onCloudExpressDetectChange = {
                                    cloudExpressDetect = it
                                    settingsRepository.persistCloudExpressDetect(it)
                                },
                                onExpressDirectJumpChange = {
                                    expressDirectJump = it
                                    settingsRepository.persistExpressDirectJump(it)
                                },
                                onCainiaoAutoConfirmChange = {
                                    cainiaoAutoConfirm = it
                                    settingsRepository.persistCainiaoAutoConfirm(it)
                                },
                                onCainiaoAutoExpandChange = {
                                    cainiaoAutoExpand = it
                                    settingsRepository.persistCainiaoAutoExpand(it)
                                },
                                onSchemeDirectJumpChange = {
                                    schemeDirectJump = it
                                    settingsRepository.persistSchemeDirectJump(it)
                                },
                                onJumpFallbackWebChange = {
                                    jumpFallbackWeb = it
                                    settingsRepository.persistJumpFallbackWeb(it)
                                },
                                onJumpPrecheckChange = {
                                    jumpPrecheck = it
                                    settingsRepository.persistJumpPrecheck(it)
                                },
                                onDuplicateWindowMillisChange = {
                                    duplicateWindowMillis = it
                                    settingsRepository.persistDuplicateWindowMillis(it)
                                },
                                onAutoActivateChange = {
                                    autoActivate = it
                                    settingsRepository.persistAutoActivate(it)
                                },
                                onCheckUpdate = { checkUpdate(showNoUpdate = true) },
                                onOpenTheme = { context.startActivity(Intent(context, ThemeSettingsActivity::class.java)) },
                                onOpenAppList = { context.startActivity(Intent(context, AppListActivity::class.java)) },
                                topContentPadding = pagePadding.calculateTopPadding() + 12.dp,
                                bottomContentPadding = pagePadding.calculateBottomPadding() + 16.dp,
                                subPage = settingsSubPage,
                                onSubPageChange = { AppNav.subPageFlow.value = it },
)
                        }
                    }
                }
            }

            updateDialog?.let { dialog ->
                OverlayDialog(
                    title = dialog.title,
                    summary = dialog.message,
                    show = true,
                    onDismissRequest = { updateDialog = null },
                ) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            text = stringResource(R.string.action_close),
                            onClick = { updateDialog = null },
                            modifier = Modifier.weight(1f),
                        )

                        if (dialog.showOpenButton && dialog.url != null) {
                            Spacer(Modifier.width(20.dp))
                            TextButton(
                                text = stringResource(R.string.action_open),
                                onClick = {
                                    updateDialog = null
                                    uriHandler.openUri(dialog.url)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun localizedUpdateFailure(defaultMessage: String, message: String): String = message

private fun Context.findMainActivity(): MainActivity? = generateSequence(this) {
    (it as? ContextWrapper)?.baseContext
}.filterIsInstance<MainActivity>().firstOrNull()

private data class UpdateDialogState(
    val title: String,
    val message: String,
    val url: String? = null,
    val showOpenButton: Boolean = false,
)

@Composable
private fun BottomNavigation(
    tabs: List<Tab>,
    selectedTab: Tab,
    onTabClick: (Int, Tab) -> Unit,
) {
    NavigationBar(color = MiuixTheme.colorScheme.surface, showDivider = false) {
        tabs.forEachIndexed { index, tab ->
            NavigationBarItem(
                selected = tab == selectedTab,
                onClick = { onTabClick(index, tab) },
                icon = tab.icon,
                label = stringResource(tab.labelRes),
            )
        }
    }
}

@Composable
fun appBackground(colorMode: AppColorMode = AppColorMode.System): Color {
    val dark = when (colorMode) {
        AppColorMode.System -> isSystemInDarkTheme()
        AppColorMode.Dark -> true
        AppColorMode.Light -> false
    }
    return if (dark) Color(0xFF101010) else Color(0xFFF5F5F7)
}
