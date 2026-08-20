package io.github.hypercopy.ui.pages.cloudrules

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import org.json.JSONArray
import io.github.hypercopy.R
import io.github.hypercopy.Config
import io.github.hypercopy.HyperLog
import io.github.hypercopy.data.rules.BuiltinRules
import io.github.hypercopy.data.rules.CloudRule
import io.github.hypercopy.data.rules.CloudRuleException
import io.github.hypercopy.data.rules.CloudRuleError
import io.github.hypercopy.data.rules.CloudRulesRepository
import io.github.hypercopy.data.rules.CloudSourceConfig
import io.github.hypercopy.data.rules.CloudSourceRegistry
import io.github.hypercopy.data.rules.displayNameText
import io.github.hypercopy.data.rules.cloudRuleFromJson
import io.github.hypercopy.data.rules.RuleRepository
import io.github.hypercopy.data.rules.RuleSaveResult
import io.github.hypercopy.data.rules.sameContentAs
import io.github.hypercopy.data.settings.SettingsRepository
import io.github.hypercopy.ui.components.HyperSearchBar
import io.github.hypercopy.ui.components.PackageIcon
import io.github.hypercopy.ui.components.RulePageCategory
import io.github.hypercopy.ui.components.CloudSourceManagerDialog
import io.github.hypercopy.ui.components.RuleCategoryTabs
import io.github.hypercopy.ui.components.folderName
import io.github.hypercopy.ui.components.titleRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import androidx.compose.foundation.clickable
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * v1.140.17 云端规则列表持久化缓存（cacheDir，卸载自动清理，按源+分类隔离）。
 * 解决国内网络下 GitHub 不可达时云端规则页白屏/失败：首次拉取成功落盘，
 * 后续打开秒开显示缓存，后台静默刷新，网络失败保留缓存展示。
 */
private object CloudRulesCacheStore {
    private fun dir(context: Context): File = File(context.cacheDir, "cloud_rules_cache")
    private fun file(context: Context, sourceKey: String, folder: String): File =
        File(dir(context), "${sourceKey}_${folder}.json")

    fun save(context: Context, sourceKey: String, folder: String, rules: List<CloudRule>) {
        runCatching {
            val json = JSONArray().apply { rules.forEach { put(it.toJson()) } }
            file(context, sourceKey, folder).apply {
                parentFile?.mkdirs()
                writeText(json.toString())
            }
        }
    }

    fun load(context: Context, sourceKey: String, folder: String): List<CloudRule>? = runCatching {
        val f = file(context, sourceKey, folder)
        if (!f.exists()) return null
        val arr = JSONArray(f.readText())
        buildList {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { add(cloudRuleFromJson(it)) }
            }
        }
    }.getOrNull()
}

@Composable
fun CloudRulesPage(
    modifier: Modifier = Modifier,
    topContentPadding: Dp = 12.dp,
    bottomContentPadding: Dp = 16.dp,
    showInstalledOnly: Boolean = false,
    cloudSource: String = Config.CLOUD_SOURCE_ACCELERATED,
    refreshTrigger: Int = 0,
    downloadInstalledTrigger: Int = 0,
    onSourceChange: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository = remember { SettingsRepository(context.applicationContext) }
    // v1.139.1 换源：cloudSource 为源 key，解析为源配置
    val sourceConfig = remember(cloudSource) {
        CloudSourceRegistry.byKey(context, cloudSource) ?: CloudSourceRegistry.AUTHOR
    }
    val cloudRepository = remember(sourceConfig) { CloudRulesRepository(sourceConfig) }
    // v1.139.1 源选择器状态
    var showSourceDialog by remember { mutableStateOf(false) }
    val localRepository = remember { RuleRepository(context.applicationContext) }

    var selectedCategory by remember { mutableStateOf(RulePageCategory.Link) }
    var searchQuery by remember { mutableStateOf("") }
    var cloudRules by remember { mutableStateOf<List<CloudRule>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installedPackageNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    val rulesCache = remember { mutableStateMapOf<RulePageCategory, List<CloudRule>>() }
    val downloadingIds = remember { mutableStateMapOf<String, Boolean>() }

    fun refreshDownloadedIds() {
        downloadedIds = localRepository.readRules().map { it.id }.toSet()
    }

    /** 保存云端下载的规则：
     *  - 本地已有同源规则(cloud_ 或 builtin_cloud_)：内容相同 → 不更新(NoChange)；云端有变化 → 更新原规则(保留id/启用状态)
     *  - 本地没有：新增(Added)
     */
    fun saveDownloadedRule(cloudRule: CloudRule, config: io.github.hypercopy.data.rules.RuleConfig): DownloadResult {
        val local = localRepository.readRules()
        val stableId = cloudRule.stableId()
        val builtinId = "${BuiltinRules.ID_PREFIX}$stableId"
        val existing = local.firstOrNull { it.id == stableId || it.id == builtinId }
        if (existing != null) {
            if (existing.sameContentAs(config)) return DownloadResult.NoChange
            localRepository.saveRule(
                config.copy(id = existing.id, enabled = existing.enabled, createdAt = existing.createdAt),
            )
            return DownloadResult.Updated
        }
        // 新增：saveRule 内部做内容级去重（与本地已有规则功能内容相同则不重复添加）
        return when (localRepository.saveRule(config)) {
            RuleSaveResult.Duplicate -> DownloadResult.NoChange
            RuleSaveResult.Rejected -> DownloadResult.NoChange
            else -> DownloadResult.Added
        }
    }

    fun loadRules(category: RulePageCategory, forceRefresh: Boolean = false, showSuccessToast: Boolean = false) {
        if (!forceRefresh) {
            // ① 内存缓存
            rulesCache[category]?.let {
                cloudRules = it
                error = null
                refreshDownloadedIds()
                return
            }
            // ② v1.140.17 磁盘持久化缓存：先显示（秒开/离线可用），后台再静默刷新
            CloudRulesCacheStore.load(context, sourceConfig.key, category.folderName())?.let { cached ->
                rulesCache[category] = cached
                cloudRules = cached
                error = null
                refreshDownloadedIds()
            }
        } else if (cloudRules.isEmpty()) {
            // v1.140.18 修复：强制刷新且当前列表为空（如刚切源）时预读当前源磁盘缓存，
            // 避免网络失败时残留旧源数据（切源后旧列表不消失的 bug）
            CloudRulesCacheStore.load(context, sourceConfig.key, category.folderName())?.let { cached ->
                rulesCache[category] = cached
                cloudRules = cached
                error = null
                refreshDownloadedIds()
            }
        }
        scope.launch {
            loading = true
            if (!forceRefresh && cloudRules.isEmpty()) error = null
            runCatching { cloudRepository.listRules(category.folderName()) }
                .onSuccess {
                    rulesCache[category] = it
                    cloudRules = it
                    // v1.140.17 持久化：网络成功覆盖磁盘缓存
                    CloudRulesCacheStore.save(context, sourceConfig.key, category.folderName(), it)
                    if (showSuccessToast) {
                        Toast.makeText(context, R.string.cloud_toast_refresh_success, Toast.LENGTH_SHORT).show()
                    }
                }
                .onFailure {
                    // v1.140.17 有缓存时保留显示（离线模式），无缓存才报错
                    if (cloudRules.isEmpty()) {
                        error = (it as? CloudRuleException)?.message ?: context.getString(R.string.cloud_error_load)
                    }
                }
            loading = false
            refreshDownloadedIds()
        }
    }

    fun downloadInstalledRules() {
        val installedRules = cloudRules.filter { it.packageName in installedPackageNames }
        val rules = installedRules.filter { !it.isDownloaded(downloadedIds) && downloadingIds[it.fileName] != true }
        if (rules.isEmpty()) {
            Toast.makeText(context, R.string.cloud_toast_no_installed_rules_to_download, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            var successCount = 0
            rules.forEach { rule ->
                downloadingIds[rule.fileName] = true
                runCatching { cloudRepository.downloadRule(rule) }
                    .onSuccess { config ->
                        val result = saveDownloadedRule(rule, config)
                        if (result != DownloadResult.NoChange) successCount++
                    }
                downloadingIds[rule.fileName] = false
            }
            refreshDownloadedIds()
            Toast.makeText(context, context.getString(R.string.cloud_toast_batch_added, successCount), Toast.LENGTH_SHORT).show()
        }
    }

    // v1.139.1 修复：切源后强制刷新（cloudSource 变化 → 清缓存重载新源列表）
    LaunchedEffect(selectedCategory, cloudSource) {
        rulesCache.clear()
        // v1.140.18 修复：切源时同步清空当前列表/错误，避免新源加载失败时残留旧源数据（30 条不消失 bug）
        cloudRules = emptyList()
        error = null
        loadRules(selectedCategory, forceRefresh = true)
        // v1.140.18 探测最快下载通道并提示。直接用 LaunchedEffect 挂起（页面离开/切源时 effect 取消→探测中断），
        // 避免 scope.launch 在切走页面后仍延迟弹 Toast
        val channel = cloudRepository.probeFastestDownloadChannel()
        val label = when (channel) {
            "ghfast" -> context.getString(R.string.channel_ghfast)
            "ghproxy" -> context.getString(R.string.channel_ghproxy)
            "accel" -> context.getString(R.string.channel_accel)
            else -> context.getString(R.string.channel_github)
        }
        Toast.makeText(
            context.applicationContext,
            context.getString(R.string.cloud_toast_channel, label),
            Toast.LENGTH_SHORT,
        ).show()
    }
    LaunchedEffect(Unit) {
        installedPackageNames = withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            // v1.142.6t 修复：getInstalledApplications 在 HyperOS 受包可见性限制只返回自身（installedCount=1），
            // 改用 launcher intent 查询（与 AppListPage 已验证方案一致）+ getInstalledApplications 并集兜底
            val fromLauncher = runCatching {
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                packageManager.queryIntentActivities(launcherIntent, 0)
                    .mapNotNull { it.activityInfo?.packageName }
                    .toSet()
            }.getOrDefault(emptySet())
            val fromInstalled = runCatching {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                    .map { it.packageName }
                    .toSet()
            }.getOrDefault(emptySet())
            val merged = fromLauncher + fromInstalled
            HyperLog.d("HyperCopy-CloudRules", "installedPackages: launcher=${fromLauncher.size}, installed=${
                fromInstalled.size
            }, merged=${merged.size}")
            merged
        }
    }

    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            rulesCache.clear()
            loadRules(selectedCategory, forceRefresh = true, showSuccessToast = true)
        }
    }

    LaunchedEffect(downloadInstalledTrigger) {
        if (downloadInstalledTrigger > 0) {
            downloadInstalledRules()
        }
    }

    val filteredRules by remember(cloudRules, searchQuery, showInstalledOnly, installedPackageNames) {
        derivedStateOf {
            val query = searchQuery.trim()
            // v1.142.6t 诊断：记录筛选入参，定位"仅已安装"无效问题
            HyperLog.d(
                "HyperCopy-CloudRules",
                "filter: showInstalledOnly=$showInstalledOnly, installedCount=${installedPackageNames.size}, " +
                    "total=${cloudRules.size}, emptyPkg=${cloudRules.count { it.packageName.isBlank() }}",
            )
            cloudRules.filter { rule ->
                // v1.142.6t 修复：无包名规则（文件名未含包名，如零食仓库 淘宝·口令/美团·小程序）不参与"仅已安装"过滤，
                // 避免已安装应用对应规则被误过滤（"" 不在 installedPackageNames 中）
                val matchesInstalled = !showInstalledOnly || rule.packageName.isBlank() ||
                    rule.packageName in installedPackageNames
                val matchesQuery = query.isEmpty() ||
                    rule.name.contains(query, ignoreCase = true) ||
                    rule.packageName.contains(query, ignoreCase = true)
                matchesInstalled && matchesQuery
            }
        }
    }

    fun handleDownload(rule: CloudRule) {
        if (downloadingIds[rule.fileName] == true) return
        downloadingIds[rule.fileName] = true
        scope.launch {
            runCatching { cloudRepository.downloadRule(rule) }
                .onSuccess { config ->
                    val result = saveDownloadedRule(rule, config)
                    refreshDownloadedIds()
                    val message = when (result) {
                        DownloadResult.Added -> context.getString(R.string.cloud_toast_added, config.name)
                        DownloadResult.Updated -> context.getString(R.string.cloud_toast_updated, config.name)
                        DownloadResult.NoChange -> context.getString(R.string.cloud_toast_latest, config.name)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(
                        context,
                        context.getString(R.string.cloud_toast_download_failed, (it as? CloudRuleException)?.message ?: it.message),
                        Toast.LENGTH_SHORT,
                    ).show()
            }
            downloadingIds[rule.fileName] = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        CloudRulesHeader(
            topContentPadding = topContentPadding,
            selectedCategory = selectedCategory,
            onSelected = {
                selectedCategory = it
                searchQuery = ""
            },
        )
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                loading && cloudRules.isEmpty() -> CloudRulesLoading()
                error != null && cloudRules.isEmpty() -> CloudRulesError(
                    message = error!!,
                    onRetry = { loadRules(selectedCategory) },
                    bottomContentPadding = bottomContentPadding,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = 4.dp,
                        end = 12.dp,
                        bottom = bottomContentPadding,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        // v1.139.1 源选择器（Card 条目样式，显眼可点击）
                        Card {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showSourceDialog = true }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.cloud_source_picker, sourceConfig.displayNameText()),
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "▾",
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    item {
                        SmallTitle(
                            text = stringResource(
                                R.string.cloud_rules_count,
                                filteredRules.size,
                                stringResource(selectedCategory.titleRes()),
                            ),
                        )
                    }
                    item {
                        HyperSearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            label = stringResource(R.string.app_list_search_hint),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (filteredRules.isEmpty()) {
                        item { CloudRulesEmptyCard(isSearching = searchQuery.isNotBlank()) }
                    } else {
                        items(filteredRules, key = { it.fileName }) { rule ->
                            CloudRuleCard(
                                rule = rule,
                                downloaded = rule.isDownloaded(downloadedIds),
                                downloading = downloadingIds[rule.fileName] == true,
                                onDownload = { handleDownload(rule) },
                            )
                        }
                    }
                }
            }
        }
    }
        // v1.139.1 源管理对话框（公共组件：切换/添加/删除）
        CloudSourceManagerDialog(
            show = showSourceDialog,
            onDismiss = { showSourceDialog = false },
            currentSourceKey = sourceConfig.key,
            settingsRepository = settingsRepository,
            onSourceChange = { key ->
                onSourceChange(key)
                rulesCache.clear()
            },
        )}

private fun CloudRule.stableId(): String =
    if (sourceKey.isNotBlank()) "cloud_${sourceKey}_${folder}_${fileNameWithoutExt()}"
    else "cloud_${folder}_${fileNameWithoutExt()}"

/** 已下载判断：兼容 builtin_ 前缀的内置规则，避免内置规则在云规则页显示为可下载 */
private fun CloudRule.isDownloaded(downloadedIds: Set<String>): Boolean =
    stableId() in downloadedIds || "${BuiltinRules.ID_PREFIX}${stableId()}" in downloadedIds

/** 下载保存结果 */
private enum class DownloadResult { Added, Updated, NoChange }

@Composable
private fun CloudRulesHeader(
    topContentPadding: Dp,
    selectedCategory: RulePageCategory,
    onSelected: (RulePageCategory) -> Unit,
) {
    RuleCategoryTabs(
        selectedCategory = selectedCategory,
        includeSystem = false,
        onSelected = onSelected,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = topContentPadding, end = 12.dp, bottom = 4.dp),
        colors = TabRowDefaults.tabRowColors(
            backgroundColor = MiuixTheme.colorScheme.surface,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainerHigh,
            selectedBackgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
            selectedContentColor = MiuixTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun CloudRulesLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.cloud_loading),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun CloudRulesError(message: String, onRetry: () -> Unit, bottomContentPadding: Dp) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 24.dp, end = 16.dp, bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(text = stringResource(R.string.cloud_load_failed), style = MiuixTheme.textStyles.title3)
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    TextButton(
                        text = stringResource(R.string.action_retry),
                        onClick = onRetry,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudRulesEmptyCard(isSearching: Boolean) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(if (isSearching) R.string.cloud_no_match else R.string.cloud_empty),
                style = MiuixTheme.textStyles.title3,
            )
            Text(
                text = if (isSearching) {
                    stringResource(R.string.cloud_no_match_hint)
                } else {
                    stringResource(R.string.cloud_empty_hint)
                },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun CloudRuleCard(
    rule: CloudRule,
    downloaded: Boolean,
    downloading: Boolean,
    onDownload: () -> Unit,
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PackageIcon(
                packageName = rule.packageName,
                fallbackText = rule.name,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = rule.name, style = MiuixTheme.textStyles.headline1)
                Text(
                    text = rule.packageName.ifBlank { stringResource(R.string.cloud_generic_rule) },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (downloading) {
                CircularProgressIndicator(
                    size = 20.dp,
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(
                    onClick = onDownload,
                    minWidth = 36.dp,
                    minHeight = 36.dp,
                    cornerRadius = 18.dp,
                    backgroundColor = if (downloaded) {
                        Color(0xFF36D167).copy(alpha = 0.12f)
                    } else {
                        MiuixTheme.colorScheme.primary.copy(alpha = 0.08f)
                    },
                ) {
                    Icon(
                        imageVector = if (downloaded) MiuixIcons.Basic.Check else MiuixIcons.Download,
                        contentDescription = stringResource(if (downloaded) R.string.action_update_rule else R.string.action_download_rule),
                        tint = if (downloaded) Color(0xFF36D167) else MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
