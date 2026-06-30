package io.github.hypercopy.ui.pages.rules

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hypercopy.R
import io.github.hypercopy.HyperLog
import io.github.hypercopy.ui.components.githubRuleSubmissionUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.hypercopy.clipboard.handling.OneRedirectResolver
import io.github.hypercopy.data.rules.RuleActionMode
import io.github.hypercopy.data.rules.RuleCategory
import io.github.hypercopy.data.rules.RuleConfig
import io.github.hypercopy.data.rules.RuleRepository
import io.github.hypercopy.data.rules.RuleStatsRepository
import io.github.hypercopy.data.settings.SettingsRepository
import io.github.hypercopy.data.systemlink.SystemLinkApp
import io.github.hypercopy.data.systemlink.SystemLinkRepository
import io.github.hypercopy.data.rules.RuleTarget
import io.github.hypercopy.data.rules.RuleTargetType
import io.github.hypercopy.data.rules.directIntent
import io.github.hypercopy.data.rules.extractFirstInputUrl
import io.github.hypercopy.data.rules.extractParameters
import io.github.hypercopy.data.rules.findRule
import io.github.hypercopy.data.rules.matchRule
import io.github.hypercopy.data.rules.parseIntent
import io.github.hypercopy.data.rules.resolveInputUrl
import io.github.hypercopy.data.rules.resolveTemplate
import io.github.hypercopy.data.rules.rulesFromJson
import io.github.hypercopy.data.rules.toIntent
import io.github.hypercopy.ui.activities.RuleBrowserActivity
import io.github.hypercopy.ui.activities.RuleEditorActivity
import io.github.hypercopy.ui.activities.RuleSuggestionActivity
import io.github.hypercopy.ui.activities.SystemLinkAppDetailActivity
import io.github.hypercopy.clipboard.handling.ClipboardTextReader
import io.github.hypercopy.ui.components.AddRuleMenu
import io.github.hypercopy.ui.components.ClipboardManualInputDialog
import io.github.hypercopy.ui.components.EmptyRulesCard
import io.github.hypercopy.ui.components.HiddenWebViewResolver
import io.github.hypercopy.ui.components.HyperSearchBar
import io.github.hypercopy.ui.components.RuleCard
import io.github.hypercopy.ui.components.RuleCategoryTabs
import io.github.hypercopy.ui.components.RuleTrashBar
import io.github.hypercopy.ui.components.RuleEditBar
import io.github.hypercopy.ui.components.RulePageCategory
import io.github.hypercopy.ui.components.RuleSource
import io.github.hypercopy.ui.components.matchesRule
import io.github.hypercopy.ui.components.ruleSourceTitles
import io.github.hypercopy.ui.components.RuleSelectionBar
import io.github.hypercopy.ui.components.SystemLinkAppListCard
import io.github.hypercopy.ui.components.SystemLinkHandlingCard
import io.github.hypercopy.ui.components.TestRuleCard
import io.github.hypercopy.ui.components.ruleCategories
import io.github.hypercopy.ui.components.titleRes
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.floor

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RulesPage(
    modifier: Modifier = Modifier,
    showImportDialog: Boolean = false,
    onDismissImportDialog: () -> Unit = {},
    sortMode: Boolean = false,
    onSortModeChange: (Boolean) -> Unit = {},
    editMode: Boolean = false,
    onEditModeChange: (Boolean) -> Unit = {},
    onRuleActionsAvailableChange: (Boolean) -> Unit = {},
    topContentPadding: Dp = 12.dp,
    bottomContentPadding: Dp = 16.dp,
    systemLinkUserId: Int = 0,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = remember { RuleRepository(context.applicationContext) }
    val settingsRepository = remember { SettingsRepository(context.applicationContext) }
    val systemLinkRepository = remember { SystemLinkRepository(context.applicationContext) }
    val statsRepository = remember { RuleStatsRepository(context.applicationContext) }
    val statsVersion by statsRepository.changeSignal.collectAsState()
    var rules by remember { mutableStateOf(repository.readRules()) }
    // ===== SAF 文件导入导出（需在 rules 声明后） =====
    val exportFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val all = repository.readRules()
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(io.github.hypercopy.data.rules.rulesToJson(all).toByteArray())
                }
                Toast.makeText(context, context.getString(R.string.rule_toast_export_file_ok, all.size), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, context.getString(R.string.rule_toast_export_file_failed, it.message.orEmpty()), Toast.LENGTH_SHORT).show()
            }
        }
    }
    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                val importedRules = rulesFromJson(text)
                if (importedRules.isEmpty()) error(context.getString(R.string.rule_import_empty))
                val importedIds = importedRules.map { it.id }.toSet()
                repository.persistRules(repository.readRules().filterNot { it.id in importedIds } + importedRules)
                rules = repository.readRules()
                Toast.makeText(context, context.getString(R.string.rule_toast_import_file_ok, importedRules.size), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, context.getString(R.string.rule_toast_import_file_failed, it.message.orEmpty()), Toast.LENGTH_SHORT).show()
            }
        }
    }
    var systemLinkHandling by remember { mutableStateOf(settingsRepository.readSystemLinkHandling()) }
    var systemLinkClearClipboardAfterJump by remember { mutableStateOf(settingsRepository.readSystemLinkClearClipboardAfterJump()) }
    var selectedCategory by remember { mutableStateOf(RulePageCategory.System) }
    // v1.139.1 规则来源筛选：全部/内置(我的打包)/云端(作者仓库)/自定义(手动添加)
    var selectedSource by remember { mutableStateOf(RuleSource.All) }
    // v1.139.1c 用户修改过的内置规则 id（修改过=内置/我的；未修改的作者原版=云端）
    var modifiedBuiltinIds by remember { mutableStateOf(repository.modifiedBuiltinRuleIds()) }
    var selectedGroup by remember { mutableStateOf("") }
    var showTrash by remember { mutableStateOf(false) }
    var trashEntries by remember { mutableStateOf<List<io.github.hypercopy.data.rules.TrashEntry>>(emptyList()) }
    // v1.65 破坏性操作确认：清空回收站 / 彻底删除 / 恢复内置规则
    var showEmptyTrashConfirm by remember { mutableStateOf(false) }
    var showPurgeRuleId by remember { mutableStateOf<String?>(null) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var sortByFrequency by remember { mutableStateOf(false) }
    // v1.33 场景规则集
    var sceneGroup by remember { mutableStateOf(settingsRepository.readSceneGroup()) }
    var showSceneDialog by remember { mutableStateOf(false) }
    // v1.41 剪贴板读取失败时手动输入兜底
    var showManualInputDialog by remember { mutableStateOf(false) }
    var manualInputText by remember { mutableStateOf("") }
    var testInput by remember { mutableStateOf("") }
    var searchText by remember { mutableStateOf("") }
    val ruleResultWaiting = stringResource(R.string.rule_result_waiting)
    var resultText by remember(ruleResultWaiting) { mutableStateOf(ruleResultWaiting) }
    var systemLinkApps by remember { mutableStateOf<List<SystemLinkApp>>(emptyList()) }
    var systemLinkLoading by remember { mutableStateOf(false) }
    var resolvingUrl by remember { mutableStateOf<String?>(null) }
    var resolvingRule by remember { mutableStateOf<RuleConfig?>(null) }
    var selectedRuleIds by remember { mutableStateOf(emptySet<String>()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var sortedCategoryRules by remember { mutableStateOf<List<RuleConfig>?>(null) }
    var draggingRuleId by remember { mutableStateOf<String?>(null) }
    var dragTotalOffsetY by remember { mutableStateOf(0f) }
    var dragMovedSteps by remember { mutableStateOf(0) }
    val itemSpacingPx = remember(density) { with(density) { 12.dp.toPx() } }
    val fallbackRuleItemStepPx = remember(density) { with(density) { 84.dp.toPx() } }
    var ruleItemStepPx by remember { mutableStateOf(0f) }

    DisposableEffect(lifecycleOwner, repository) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                rules = repository.readRules()
                // v1.31 审计修复：清理孤儿统计（既不在规则库也不在回收站）
                val trashIds = repository.readTrash().map { it.rule.id }.toSet()
                statsRepository.prune(rules.map { it.id }.toSet() + trashIds)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(repository) {
        RuleRepository.changes.collect {
            rules = repository.readRules()
            trashEntries = repository.readTrash()
            modifiedBuiltinIds = repository.modifiedBuiltinRuleIds()
        }
    }
    LaunchedEffect(showTrash) {
        if (showTrash) trashEntries = repository.readTrash()
    }

    val categoryRules = rules.filter {
        it.category in selectedCategory.ruleCategories() && (selectedGroup.isBlank() || it.group == selectedGroup) &&
            selectedSource.matchesRule(it.id, modifiedBuiltinIds)
    }.let { list ->
        if (sortByFrequency && !sortMode && !editMode && !showTrash) {
            list.sortedByDescending { statsRepository.get(it.id) }
        } else list
    }
    val ruleGroups = remember(rules) {
        rules.mapNotNull { it.group.takeIf { g -> g.isNotBlank() } }.distinct().sorted()
    }
    val displayedCategoryRules = if (sortMode) sortedCategoryRules ?: categoryRules else categoryRules
    val filteredCategoryRules = if (sortMode || editMode) {
        displayedCategoryRules
    } else {
        displayedCategoryRules.filter { rule ->
            searchText.isBlank() || rule.name.contains(searchText, ignoreCase = true) ||
                rule.target.packageName.contains(searchText, ignoreCase = true)
        }
    }
    val filteredSystemLinkApps = systemLinkApps.filter { app ->
        searchText.isBlank() || app.label.contains(searchText, ignoreCase = true) ||
            app.packageName.contains(searchText, ignoreCase = true)
    }
    val categoryRuleIds = categoryRules.map { it.id }.toSet()
    val selectionMode = selectedRuleIds.isNotEmpty() || editMode

    BackHandler(enabled = sortMode && selectedCategory != RulePageCategory.System) {
        onSortModeChange(false)
    }

    BackHandler(enabled = editMode && selectedCategory != RulePageCategory.System) {
        onEditModeChange(false)
    }

    BackHandler(enabled = selectedRuleIds.isNotEmpty() && !showDeleteDialog && !editMode && !sortMode) {
        selectedRuleIds = emptySet()
    }
    // v1.38 修复：回收站内按返回键 → 先退出回收站回到规则列表，而不是直接退出页面
    BackHandler(enabled = showTrash) {
        showTrash = false
    }

    LaunchedEffect(selectedCategory, categoryRuleIds) {
        selectedRuleIds = selectedRuleIds.intersect(categoryRuleIds)
        if (selectedCategory == RulePageCategory.System) {
            onSortModeChange(false)
            onEditModeChange(false)
        }
        onRuleActionsAvailableChange(selectedCategory != RulePageCategory.System)
    }

    LaunchedEffect(sortMode, selectedCategory, categoryRuleIds) {
        if (sortMode) {
            selectedRuleIds = emptySet()
            sortedCategoryRules = categoryRules
            if (selectedCategory == RulePageCategory.System) onSortModeChange(false)
        } else {
            sortedCategoryRules = null
            draggingRuleId = null
            dragTotalOffsetY = 0f
            dragMovedSteps = 0
        }
    }

    LaunchedEffect(editMode, selectedCategory) {
        if (editMode) {
            selectedRuleIds = emptySet()
            if (selectedCategory == RulePageCategory.System) onEditModeChange(false)
        }
    }

    fun moveSortingRule(ruleId: String, direction: Int): Boolean {
        val currentRules = sortedCategoryRules ?: categoryRules
        val fromIndex = currentRules.indexOfFirst { it.id == ruleId }
        if (fromIndex < 0) return false
        val toIndex = (fromIndex + direction).coerceIn(currentRules.indices)
        if (fromIndex == toIndex) return false
        sortedCategoryRules = currentRules.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        return true
    }

    fun dragRule(ruleId: String, deltaY: Float) {
        val step = ruleItemStepPx.takeIf { it > 0f } ?: fallbackRuleItemStepPx
        dragTotalOffsetY += deltaY
        val targetSteps = if (dragTotalOffsetY >= 0f) {
            floor(dragTotalOffsetY / step).toInt()
        } else {
            ceil(dragTotalOffsetY / step).toInt()
        }
        while (dragMovedSteps < targetSteps && moveSortingRule(ruleId, 1)) {
            dragMovedSteps++
        }
        while (dragMovedSteps > targetSteps && moveSortingRule(ruleId, -1)) {
            dragMovedSteps--
        }
    }

    fun persistSorting() {
        val sortedRules = sortedCategoryRules ?: return
        repository.reorderRules(selectedCategory.ruleCategories(), sortedRules.map { it.id })
        rules = repository.readRules()
        sortedCategoryRules = rules.filter { it.category in selectedCategory.ruleCategories() }
    }

    fun loadSystemLinks() {
        systemLinkLoading = true
        thread(name = "HyperCopySystemLinks") {
            val apps = runCatching { systemLinkRepository.readApps(systemLinkUserId) }
                .getOrElse { throwable ->
                    HyperLog.d("HyperCopy", "load system links failed", throwable)
                    (context as? android.app.Activity)?.runOnUiThread {
                        resultText = context.getString(R.string.rule_system_load_failed, throwable.message.orEmpty())
                    }
                    emptyList()
                }
            (context as? android.app.Activity)?.runOnUiThread {
                systemLinkApps = apps
                systemLinkLoading = false
            }
        }
    }

    LaunchedEffect(systemLinkUserId) {
        if (selectedCategory == RulePageCategory.System) loadSystemLinks()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            when {
                sortMode && selectedCategory != RulePageCategory.System -> RuleEditBar(
                    modifier = Modifier.padding(start = 12.dp, top = topContentPadding, end = 12.dp, bottom = 4.dp),
                    onCloseClick = { onSortModeChange(false) },
                )

                selectionMode && selectedCategory != RulePageCategory.System -> RuleSelectionBar(
                    selectedCount = selectedRuleIds.size,
                    allSelected = selectedRuleIds.size == categoryRules.size,
                    modifier = Modifier.padding(start = 12.dp, top = topContentPadding, end = 12.dp, bottom = 4.dp),
                    onCloseClick = {
                        selectedRuleIds = emptySet()
                        if (editMode) onEditModeChange(false)
                    },
                    onSelectAllClick = {
                        selectedRuleIds = if (selectedRuleIds.size == categoryRules.size) emptySet() else categoryRuleIds
                    },
                    onDeleteClick = {
                        if (selectedRuleIds.isNotEmpty()) showDeleteDialog = true
                    },
                    onEnableClick = {
                        repository.setRulesEnabled(selectedRuleIds, true)
                        selectedRuleIds = emptySet()
                    },
                    onDisableClick = {
                        repository.setRulesEnabled(selectedRuleIds, false)
                        selectedRuleIds = emptySet()
                    },
                )

                // v1.63 回收站模式：顶栏替换为「返回 + 回收站标题」，一键返回规则列表
                showTrash -> RuleTrashBar(
                    modifier = Modifier.padding(start = 12.dp, top = topContentPadding, end = 12.dp, bottom = 4.dp),
                    count = trashEntries.size,
                    onBackClick = { showTrash = false },
                )
                else -> RuleCategoryTabs(
                    selectedCategory = selectedCategory,
                    includeSystem = true,
                    onSelected = {
                        selectedCategory = it
                        resultText = ruleResultWaiting
                        selectedRuleIds = emptySet()
                        if (it == RulePageCategory.System) loadSystemLinks()
                    },
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = topContentPadding, end = 12.dp, bottom = 4.dp),
                    colors = TabRowDefaults.tabRowColors(
                        backgroundColor = MiuixTheme.colorScheme.surface,
                        contentColor = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                        selectedBackgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                        selectedContentColor = MiuixTheme.colorScheme.onSurface,
                    ),
                    // v1.76 分类 Tab 显示数量（系统=应用数；链接/文本=规则数，全量不含组筛选）
                    counts = listOf(
                        systemLinkApps.size,
                        rules.count { it.category in RulePageCategory.Link.ruleCategories() },
                        rules.count { it.category in RulePageCategory.Text.ruleCategories() },
                    ),
                )
            }
            // v1.139.1 来源筛选 Tab（仅规则分类显示；System=系统应用列表无来源概念）
            if (selectedCategory != RulePageCategory.System && !showTrash) {
                TabRowWithContour(
                    tabs = ruleSourceTitles.mapIndexed { index, res ->
                        val title = stringResource(res)
                        if (index == 0) title else {
                            val src = RuleSource.entries[index]
                            val count = rules.count {
                                it.category in selectedCategory.ruleCategories() && src.matchesRule(it.id, modifiedBuiltinIds)
                            }
                            "$title ($count)"
                        }
                    },
                    selectedTabIndex = selectedSource.ordinal,
                    onTabSelected = {
                        selectedSource = RuleSource.entries[it]
                        selectedRuleIds = emptySet()
                    },
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                    colors = TabRowDefaults.tabRowColors(
                        backgroundColor = MiuixTheme.colorScheme.surface,
                        contentColor = MiuixTheme.colorScheme.onSurfaceContainerHigh,
                        selectedBackgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                        selectedContentColor = MiuixTheme.colorScheme.onSurface,
                    ),
                )
            }
            if (selectedCategory != RulePageCategory.System && !sortMode && !selectionMode) {
                // v1.68 修复恒真条件 bug：无分组无回收站时不再硬显示 3 个 chip
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        GroupFilterChip(
                            text = stringResource(R.string.rule_sort_frequency),
                            selected = sortByFrequency,
                            onClick = { sortByFrequency = !sortByFrequency },
                        )
                    }
                    item {
                        GroupFilterChip(
                            text = stringResource(R.string.rule_group_all),
                            selected = selectedGroup.isBlank(),
                            onClick = { selectedGroup = "" },
                        )
                    }
                    items(ruleGroups) { group ->
                        GroupFilterChip(
                            text = group,
                            selected = selectedGroup == group,
                            onClick = { selectedGroup = group },
                        )
                    }
                    if (trashEntries.isNotEmpty()) {
                        item {
                            GroupFilterChip(
                                text = stringResource(R.string.rule_trash) + if (trashEntries.isNotEmpty()) " (${trashEntries.size})" else "",
                                selected = showTrash,
                                onClick = { showTrash = !showTrash },
                            )
                        }
                    }
                    item {
                        GroupFilterChip(
                            text = stringResource(R.string.rule_scene_button),
                            selected = sceneGroup.isNotEmpty(),
                            onClick = { showSceneDialog = true },
                        )
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = bottomContentPadding + 84.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
// v1.33 场景激活横幅
if (sceneGroup.isNotEmpty()) {
    item {
        Card {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                // v1.131 修复：weight(1f) 吃光空间后 SpaceBetween 失效导致按钮紧贴文字 → 改固定间距
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.rule_scene_active, sceneGroup),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.rule_scene_exit),
                    onClick = {
                    repository.exitScene()
                    sceneGroup = ""
                    },
                )
            }
        }
    }
}
            if (showTrash) {
                item {
                    Card {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            // v1.131 修复：weight(1f) 吃光空间后 SpaceBetween 失效导致按钮紧贴文字 → 改固定间距
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.rule_trash_summary, trashEntries.size),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.weight(1f),
                            )
                            // v1.63 摘要卡加"返回"按钮（双保险：顶栏返回 + 此处返回）
                            TextButton(
                                text = stringResource(R.string.rule_trash_back),
                                onClick = { showTrash = false },
                            )
                            TextButton(
                                text = stringResource(R.string.rule_trash_empty),
                                onClick = { showEmptyTrashConfirm = true },
                            )
                        }
                    }
                }
                if (trashEntries.isEmpty()) {
                    item { EmptyRulesCard(selectedCategory, onAddClick = { openEditorForCategory(selectedCategory, context) }) }
                } else {
                    items(trashEntries, key = { it.rule.id }) { entry ->
                        Card {
                            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(text = entry.rule.name, style = MiuixTheme.textStyles.headline1)
                                Text(
                                    text = "${entry.rule.target.packageName.ifBlank { entry.rule.category.value }} · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.deletedAt))} ${stringResource(R.string.deleted)}",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    TextButton(
                                        text = stringResource(R.string.rule_trash_restore),
                                        onClick = {
                                            repository.restoreFromTrash(setOf(entry.rule.id))
                                            trashEntries = repository.readTrash()
                                            rules = repository.readRules()
                                        },
                                    )
                                    TextButton(
                                        text = stringResource(R.string.rule_trash_purge),
                                        onClick = { showPurgeRuleId = entry.rule.id },
                                        colors = ButtonDefaults.textButtonColors(textColor = Color(0xFFFF5A52)),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
            if (selectedCategory == RulePageCategory.System) {
                item {
                    SystemLinkHandlingCard(
                        checked = systemLinkHandling,
                        clearClipboardAfterJump = systemLinkClearClipboardAfterJump,
                        onCheckedChange = {
                            systemLinkHandling = it
                            settingsRepository.persistSystemLinkHandling(it)
                        },
                        onClearClipboardAfterJumpChange = {
                            systemLinkClearClipboardAfterJump = it
                            settingsRepository.persistSystemLinkClearClipboardAfterJump(it)
                        },
                    )
                }
            }
            if (!sortMode && !selectionMode) {
                item {
                    TestRuleCard(
                        category = selectedCategory,
                        value = testInput,
                        resultText = resultText,
                        onValueChange = { testInput = it },
                        onExecute = {
                            if (selectedCategory == RulePageCategory.System) {
                                resultText = context.getString(R.string.rule_system_test_running, systemLinkUserId)
                                thread(name = "HyperCopySystemLinkTest") {
                                    val inputUrl = extractFirstInputUrl(testInput)
                                    val success = inputUrl?.let { systemLinkRepository.openLink(systemLinkUserId, it) } == true
                                    (context as? android.app.Activity)?.runOnUiThread {
                                        resultText = context.getString(
                                            if (success) R.string.rule_system_test_started else R.string.rule_result_launch_failed,
                                            if (success) systemLinkUserId.toString() else "no url found",
                                        )
                                    }
                                }
                            } else {
                                resultText = executeRuleTest(
                                    context = context,
                                    input = testInput,
                                    rules = categoryRules,
                                    category = selectedCategory,
                                    onStartWebViewResolve = { url, rule ->
                                        resolvingUrl = url
                                        resolvingRule = rule
                                    },
                                    onStartRedirectParse = { url, rule ->
                                        resultText = context.getString(R.string.rule_result_match_redirect_parse, rule.name)
                                        thread(name = "HyperCopyRedirectTest") {
                                            val redirectedUrl = OneRedirectResolver.resolve(url)
                                            val intent = rule.parseIntent(
                                                redirectedUrl,
                                                requireMatch = false,
                                                extraParameters = mapOf("input" to testInput.trim(), "redirectUrl" to redirectedUrl),
                                            )
                                            (context as? android.app.Activity)?.runOnUiThread {
                                                if (intent == null) {
                                                    resultText = context.getString(R.string.rule_result_redirect_parse_no_param, redirectedUrl)
                                                } else {
                                                    resultText = runCatching { context.startActivity(intent) }
                                                        .fold(
                                                            onSuccess = { context.getString(R.string.rule_result_match_parse_open, rule.name, intent.data) },
                                                            onFailure = { context.getString(R.string.rule_result_launch_failed, it.message) },
                                                        )
                                                }
                                            }
                                        }
                                    },
                                    onStartActivity = { context.startActivity(it) },
                                )
                            }
                        },
                        // v1.65 测试输入清空
                        onClear = { testInput = "" },
                    )
                }
                item {
                    HyperSearchBar(
                        query = searchText,
                        onQueryChange = { searchText = it },
                        label = stringResource(R.string.rule_search_hint),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // v1.76 列表数量：当前分类（含组筛选/搜索）下的规则数或应用数
                item {
                    Text(
                        text = stringResource(
                            if (selectedCategory == RulePageCategory.System) R.string.rule_list_count_apps
                            else R.string.rule_list_count,
                            if (selectedCategory == RulePageCategory.System) filteredSystemLinkApps.size else filteredCategoryRules.size,
                        ),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            if (selectedCategory == RulePageCategory.System) {
                when {
                    systemLinkLoading -> item { EmptyRulesCard(RulePageCategory.System) }
                    filteredSystemLinkApps.isEmpty() -> item { EmptyRulesCard(RulePageCategory.System) }
                    else -> items(filteredSystemLinkApps, key = { it.packageName }) { app ->
                        SystemLinkAppListCard(
                            app = app,
                            onClick = {
                                context.startActivity(
                                    Intent(context, SystemLinkAppDetailActivity::class.java)
                                        .putExtra(SystemLinkAppDetailActivity.EXTRA_PACKAGE_NAME, app.packageName)
                                        .putExtra(SystemLinkAppDetailActivity.EXTRA_USER_ID, systemLinkUserId)
                                        .putExtra(SystemLinkAppDetailActivity.EXTRA_APP_LABEL, app.label),
                                )
                            },
                            onAppEnabledChange = { enabled ->
                                toggleSystemLinkApp(context, systemLinkRepository, systemLinkUserId, app, enabled) { apps ->
                                    systemLinkApps = apps
                                }
                            },
                        )
                    }
                }
            } else if (filteredCategoryRules.isEmpty()) {
                item { EmptyRulesCard(selectedCategory, onAddClick = { openEditorForCategory(selectedCategory, context) }) }
            } else {
                items(filteredCategoryRules, key = { it.id }) { rule ->
                    RuleCard(
                        rule = rule,
                        selected = rule.id in selectedRuleIds,
                        selectionMode = selectionMode,
                        sortMode = sortMode,
                        dragging = draggingRuleId == rule.id,
                        hitCount = statsRepository.get(rule.id),
                        dragOffsetY = if (draggingRuleId == rule.id) {
                            dragTotalOffsetY - dragMovedSteps * (ruleItemStepPx.takeIf { it > 0f } ?: fallbackRuleItemStepPx)
                        } else {
                            0f
                        },
                        modifiedBuiltinIds = modifiedBuiltinIds,
                        modifier = Modifier
                            .onGloballyPositioned { coordinates ->
                                if (coordinates.size.height > 0) ruleItemStepPx = coordinates.size.height + itemSpacingPx
                            }
                            .let { cardModifier -> if (draggingRuleId == rule.id) cardModifier else cardModifier.animateItem() },
                        onEnabledChange = { enabled ->
                            repository.setRuleEnabled(rule.id, enabled)
                            rules = repository.readRules()
                        },
                        onEditClick = {
                            context.startActivity(
                                Intent(context, RuleEditorActivity::class.java)
                                    .putExtra(RuleEditorActivity.EXTRA_RULE_ID, rule.id)
                                    .putExtra(RuleEditorActivity.EXTRA_CATEGORY, rule.category.value),
                            )
                        },
                        onLongClick = {
                            selectedRuleIds = selectedRuleIds + rule.id
                        },
                        onSelectionToggle = {
                            selectedRuleIds = if (rule.id in selectedRuleIds) {
                                selectedRuleIds - rule.id
                            } else {
                                selectedRuleIds + rule.id
                            }
                        },
                        onDragStart = {
                            draggingRuleId = rule.id
                            dragTotalOffsetY = 0f
                            dragMovedSteps = 0
                        },
                        onDrag = { deltaY -> dragRule(rule.id, deltaY) },
                        onDragEnd = {
                            persistSorting()
                            draggingRuleId = null
                            dragTotalOffsetY = 0f
                            dragMovedSteps = 0
                        },
                    )
                }
            }
            }
            }
        }

        if (selectedCategory != RulePageCategory.System && !sortMode && !selectionMode) {
            AddRuleMenu(
                category = selectedCategory,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = bottomContentPadding + 24.dp),
                onBrowserClick = { context.startActivity(Intent(context, RuleBrowserActivity::class.java)) },
                onLinkRuleClick = {
                    context.startActivity(
                        Intent(context, RuleEditorActivity::class.java)
                            .putExtra(RuleEditorActivity.EXTRA_CATEGORY, RuleCategory.Link.value),
                    )
                },
                onExpressRuleClick = {
                    context.startActivity(
                        Intent(context, RuleEditorActivity::class.java)
                            .putExtra(RuleEditorActivity.EXTRA_CATEGORY, RuleCategory.Express.value),
                    )
                },
                onClipboardClick = {
                    // 延迟+重试读取（避开 Shizuku 悬浮 Activity 抢焦点竞态），结果主线程回调
                    ClipboardTextReader.readDelayed(context, source = "rules") { clipText ->
                        // v1.40 兜底：剪贴板为空/被清理时，用最近一次处理过的文本
                        val finalText = clipText?.takeIf { it.isNotBlank() }
                            ?: io.github.hypercopy.clipboard.handling.ClipboardTextHandler.lastProcessedText
                        if (finalText.isNullOrBlank()) {
                            // v1.41 终极兜底：弹手动输入框，不再卡死
                            manualInputText = ""
                            showManualInputDialog = true
                        } else {
                            context.startActivity(
                                Intent(context, RuleSuggestionActivity::class.java)
                                    .putExtra(io.github.hypercopy.Config.EXTRA_SUGGESTION_TEXT, finalText),
                            )
                        }
                    }
                },
                onMergeDuplicateClick = {
                    val removed = repository.mergeDuplicateRules()
                    Toast.makeText(
                        context,
                        context.getString(R.string.rule_toast_merged, removed),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onExportAllClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "HyperCopyRules",
                            io.github.hypercopy.data.rules.rulesToJson(repository.readRules()),
                        ),
                    )
                    Toast.makeText(context, R.string.rule_toast_export_all, Toast.LENGTH_SHORT).show()
                },
                onExportFileClick = { exportFileLauncher.launch("HyperCopyRules.json") },
                onImportFileClick = { importFileLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                onRestoreBuiltinClick = { showRestoreConfirm = true },
                onShareRuleClick = { showShareDialog = true },
            )
        }

        resolvingUrl?.let { url ->
            HiddenWebViewResolver(
                url = url,
                onResolved = { resolvedUrl ->
                    resolvingUrl = null
                    val rule = resolvingRule
                    resolvingRule = null
                    val intent = RuleTarget(
                        type = if (resolvedUrl.startsWith("intent://", true)) RuleTargetType.Intent else RuleTargetType.Url,
                        template = resolvedUrl,
                        packageName = if (resolvedUrl.startsWith("intent://", true)) "" else rule?.target?.packageName.orEmpty(),
                    ).toIntent(emptyMap())
                    resultText = context.getString(R.string.rule_result_webview_resolved, resolvedUrl)
                    runCatching { context.startActivity(intent) }
                        .onFailure { resultText = context.getString(R.string.rule_result_launch_failed, it.message) }
                },
                onTimeout = {
                    val rule = resolvingRule
                    resolvingUrl = null
                    resolvingRule = null
                    if (rule == null) {
                        resultText = context.getString(R.string.rule_result_webview_no_jump)
                    } else {
                        val intent = RuleTarget(
                            type = RuleTargetType.Url,
                            template = url,
                            packageName = rule.target.packageName,
                        ).toIntent(emptyMap())
                        resultText = context.getString(R.string.rule_result_webview_fallback, intent.data)
                        runCatching { context.startActivity(intent) }
                            .onFailure { resultText = context.getString(R.string.rule_result_launch_failed, it.message) }
                    }
                },
                onPageLoaded = {
                    Toast.makeText(context, R.string.rule_toast_page_loaded, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(320.dp),
            )
        }

        WindowDialog(
            title = stringResource(R.string.rule_dialog_share_title),
            summary = stringResource(R.string.rule_dialog_share_summary),
            show = showShareDialog,
            onDismissRequest = { showShareDialog = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rules.filter { it.category in selectedCategory.ruleCategories() }.take(50).forEach { rule ->
                    Text(
                        text = "${rule.name}  ·  ${rule.target.packageName.ifBlank { rule.category.value }}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showShareDialog = false
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, githubRuleSubmissionUri(rule)))
                                }.onFailure {
                                    Toast.makeText(context, it.message.orEmpty(), Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
        ClipboardManualInputDialog(
            show = showManualInputDialog,
            onDismiss = { showManualInputDialog = false },
            onConfirm = { input ->
                showManualInputDialog = false
                context.startActivity(
                    Intent(context, RuleSuggestionActivity::class.java)
                        .putExtra(io.github.hypercopy.Config.EXTRA_SUGGESTION_TEXT, input),
                )
            },
        )
        WindowDialog(
            title = stringResource(R.string.rule_scene_dialog_title),
            summary = stringResource(R.string.rule_scene_dialog_summary),
            show = showSceneDialog,
            onDismissRequest = { showSceneDialog = false },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ruleGroups.forEach { group ->
                    Text(
                        text = group,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showSceneDialog = false
                                if (repository.applyScene(group)) {
                                    sceneGroup = group
                                    Toast.makeText(context, R.string.rule_scene_applied, Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(vertical = 8.dp),
                    )
                }
                if (ruleGroups.isEmpty()) {
                    Text(
                        text = stringResource(R.string.rule_scene_no_group),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
        WindowDialog(
            title = stringResource(R.string.rule_dialog_delete_title),
            summary = stringResource(R.string.rule_dialog_delete_summary, selectedRuleIds.size),
            show = showDeleteDialog,
            onDismissRequest = { showDeleteDialog = false },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showDeleteDialog = false },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.action_confirm),
                    onClick = {
                        repository.moveToTrash(selectedRuleIds)
                        selectedRuleIds = emptySet()
                        rules = repository.readRules()
                        trashEntries = repository.readTrash()
                        showDeleteDialog = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(textColor = Color(0xFFFF5A52)),
                )
            }
        }
    }

    // v1.65 清空回收站确认（永久删除所有回收站规则）
    WindowDialog(
        title = stringResource(R.string.trash_empty_confirm_title),
        summary = stringResource(R.string.trash_empty_confirm_summary, trashEntries.size),
        show = showEmptyTrashConfirm,
        onDismissRequest = { showEmptyTrashConfirm = false },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = stringResource(R.string.action_cancel),
                onClick = { showEmptyTrashConfirm = false },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.action_confirm),
                onClick = {
                    showEmptyTrashConfirm = false
                    trashEntries.forEach { statsRepository.clear(it.rule.id) }
                    repository.emptyTrash()
                    trashEntries = emptyList()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(textColor = Color(0xFFFF5A52)),
            )
        }
    }

    // v1.65 彻底删除单条确认（永久删除，不可恢复）
    showPurgeRuleId?.let { purgeId ->
        WindowDialog(
            title = stringResource(R.string.trash_purge_confirm_title),
            summary = stringResource(R.string.trash_purge_confirm_summary),
            show = true,
            onDismissRequest = { showPurgeRuleId = null },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showPurgeRuleId = null },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.action_confirm),
                    onClick = {
                        statsRepository.clear(purgeId)
                        repository.purgeTrash(setOf(purgeId))
                        trashEntries = repository.readTrash()
                        showPurgeRuleId = null
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(textColor = Color(0xFFFF5A52)),
                )
            }
        }
    }

    // v1.65 恢复内置规则确认（会覆盖对内置规则的修改）
    WindowDialog(
        title = stringResource(R.string.restore_builtin_confirm_title),
        summary = stringResource(R.string.restore_builtin_confirm_summary),
        show = showRestoreConfirm,
        onDismissRequest = { showRestoreConfirm = false },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = stringResource(R.string.action_cancel),
                onClick = { showRestoreConfirm = false },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.action_confirm),
                onClick = {
                    showRestoreConfirm = false
                    val restored = repository.restoreBuiltinRules()
                    rules = repository.readRules()
                    Toast.makeText(context, context.getString(R.string.rule_toast_restored_builtin, restored), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }

    WindowDialog(
        title = stringResource(R.string.rule_dialog_import_title),
        summary = stringResource(R.string.rule_dialog_import_summary),
        show = showImportDialog,
        onDismissRequest = onDismissImportDialog,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = importText,
                onValueChange = { importText = it },
                label = stringResource(R.string.rule_dialog_import_hint),
                maxLines = 15,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismissImportDialog,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.action_import_rule),
                    onClick = {
                        runCatching {
                            val importedRules = rulesFromJson(importText)
                            if (importedRules.isEmpty()) error(context.getString(R.string.rule_import_empty))
                            val importedIds = importedRules.map { it.id }.toSet()
                            repository.persistRules(repository.readRules().filterNot { it.id in importedIds } + importedRules)
                            rules = repository.readRules()
                            importText = ""
                            onDismissImportDialog()
                            Toast.makeText(context, context.getString(R.string.rule_toast_imported, importedRules.size), Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(context, context.getString(R.string.rule_toast_import_failed, it.message.orEmpty()), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

private fun toggleSystemLinkDomain(
    context: android.content.Context,
    repository: SystemLinkRepository,
    userId: Int,
    app: SystemLinkApp,
    host: String,
    enabled: Boolean,
    onReloaded: (List<SystemLinkApp>) -> Unit,
) {
    thread(name = "HyperCopySystemLinkToggle") {
        if (!Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+").matches(app.packageName)) {
            HyperLog.d("HyperCopy", "invalid system link package: ${app.packageName}")
            return@thread
        }
        runCatching { repository.setDomainEnabled(userId, app.packageName, host, enabled) }
            .onFailure { HyperLog.d("HyperCopy", "toggle system link failed", it) }
        val apps = runCatching { repository.readApps(userId) }
            .getOrElse { throwable ->
                HyperLog.d("HyperCopy", "reload system links failed", throwable)
                listOf(app)
            }
        (context as? android.app.Activity)?.runOnUiThread { onReloaded(apps) }
    }
}

private fun toggleSystemLinkApp(
    context: android.content.Context,
    repository: SystemLinkRepository,
    userId: Int,
    app: SystemLinkApp,
    enabled: Boolean,
    onReloaded: (List<SystemLinkApp>) -> Unit,
) {
    thread(name = "HyperCopySystemLinkAppToggle") {
        runCatching { repository.setLinkHandlingAllowed(userId, app.packageName, enabled) }
            .onFailure { HyperLog.d("HyperCopy", "toggle app system link failed", it) }
        val apps = runCatching { repository.readApps(userId) }
            .getOrElse { throwable ->
                HyperLog.d("HyperCopy", "reload system links failed", throwable)
                listOf(app)
            }
        (context as? android.app.Activity)?.runOnUiThread { onReloaded(apps) }
    }
}

private fun executeRuleTest(
    context: android.content.Context,
    input: String,
    rules: List<RuleConfig>,
    category: RulePageCategory,
    onStartWebViewResolve: (String, RuleConfig) -> Unit,
    onStartRedirectParse: (String, RuleConfig) -> Unit,
    onStartActivity: (Intent) -> Unit,
): String {
    val value = input.trim()
    val categoryTitle = context.getString(category.titleRes())
    if (value.isBlank()) return context.getString(R.string.rule_result_input_required, categoryTitle)
    val rule = findRule(value, rules) ?: return context.getString(R.string.rule_result_no_match, categoryTitle)
    return when (rule.actionMode) {
        RuleActionMode.ParseAndOpen -> {
            val match = matchRule(value, listOf(rule)) ?: return context.getString(R.string.rule_result_match_no_param, rule.name)
            runCatching { onStartActivity(match.intent) }
                .fold(
                    onSuccess = { context.getString(R.string.rule_result_match_parse_open, rule.name, match.intent.data) },
                    onFailure = { context.getString(R.string.rule_result_launch_failed, it.message) },
                )
        }

        RuleActionMode.DirectOpen -> {
            val intent = rule.directIntent(value, context.packageManager)
            runCatching { onStartActivity(intent) }
                .fold(
                    onSuccess = { context.getString(R.string.rule_result_match_direct_open, rule.name, intent.data) },
                    onFailure = { context.getString(R.string.rule_result_launch_failed, it.message) },
                )
        }

        RuleActionMode.WebViewResolveAndOpen -> {
            val resolveUrl = rule.resolveInputUrl(value)
            if (rule.parseAfterRedirect) {
                onStartRedirectParse(resolveUrl, rule)
                return context.getString(R.string.rule_result_match_redirect_parse, rule.name)
            }
            onStartWebViewResolve(resolveUrl, rule)
            context.getString(R.string.rule_result_match_webview, rule.name)
        }
        // v1.79 测试：ClipboardWrite 渲染预览（不真正写剪贴板）
        RuleActionMode.ClipboardWrite -> {
            val parameters = rule.extractParameters(value).toMutableMap()
            parameters["input"] = value
            val rendered = runCatching { rule.target.resolveTemplate(parameters) }.getOrDefault(value)
            context.getString(R.string.rule_result_match_clipboard_write, rule.name, rendered)
        }
        // v1.138 测试：NotifyOnly 渲染预览（不真正发通知）
        RuleActionMode.NotifyOnly -> {
            val parameters = rule.extractParameters(value).toMutableMap()
            parameters["input"] = value
            val rendered = runCatching { rule.target.resolveTemplate(parameters) }.getOrDefault(value)
            context.getString(R.string.rule_result_notify_only, rule.name, rendered)
        }
    }
}

@Composable
private fun GroupFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** v1.65 空态引导：按当前分类打开新建规则编辑器 */
private fun openEditorForCategory(category: RulePageCategory, context: android.content.Context) {
    val ruleCategory = category.ruleCategories().firstOrNull() ?: RuleCategory.Link
    context.startActivity(
        Intent(context, RuleEditorActivity::class.java)
            .putExtra(RuleEditorActivity.EXTRA_CATEGORY, ruleCategory.value),
    )
}
