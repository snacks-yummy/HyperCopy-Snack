package io.github.hypercopy.ui.pages.rules

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hypercopy.R
import io.github.hypercopy.Config
import io.github.hypercopy.clipboard.jump.PendingJump
import io.github.hypercopy.clipboard.jump.PendingJumpCoordinator
import io.github.hypercopy.data.rules.RuleActionMode
import io.github.hypercopy.ui.components.githubRuleSubmissionUri
import io.github.hypercopy.data.rules.RuleCategory
import io.github.hypercopy.data.rules.RuleConfig
import io.github.hypercopy.data.rules.RuleRepository
import io.github.hypercopy.data.rules.RulePatterns
import io.github.hypercopy.data.rules.RuleTarget
import io.github.hypercopy.data.rules.RuleTargetType
import io.github.hypercopy.data.rules.cachedRegex
import io.github.hypercopy.data.rules.extractionPatterns
import io.github.hypercopy.data.rules.parseIntent
import io.github.hypercopy.data.rules.resolveTemplate
import io.github.hypercopy.data.rules.ruleConfigFromJson
import io.github.hypercopy.data.rules.toJson
import io.github.hypercopy.data.rules.triggerPatterns
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.UUID

@Composable
fun RuleEditorPage(
    ruleId: String,
    initialCategory: RuleCategory,
    initialSourceUrl: String,
    initialTargetUrl: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { RuleRepository(context.applicationContext) }
    val editingRule = remember(ruleId) { repository.readRules().firstOrNull { it.id == ruleId } }
    val category = editingRule?.category ?: initialCategory
    val defaults = remember(category, initialSourceUrl, initialTargetUrl) {
        defaultEditorValues(context, category, initialSourceUrl, initialTargetUrl)
    }
    var name by remember { mutableStateOf(editingRule?.name ?: defaults.name) }
    // v1.37 修复：手动新增规则（无来源 URL）时触发器不预填默认模板，避免"什么都没输入也能保存"
    val hasInitialInput = initialSourceUrl.isNotBlank() || initialTargetUrl.isNotBlank()
    val triggerRegexes = remember(editingRule?.id) { mutableStateListOf(*(editingRule?.triggerPatterns() ?: if (hasInitialInput) listOf(defaults.matchRegex) else emptyList()).toTypedArray()) }
    val extractionRegexes = remember(editingRule?.id) { mutableStateListOf(*(editingRule?.extractionPatterns() ?: if (hasInitialInput) listOf(defaults.parameterRegex) else emptyList()).toTypedArray()) }
    var targetTemplate by remember { mutableStateOf(editingRule?.target?.template ?: defaults.targetTemplate) }
    var packageName by remember { mutableStateOf(editingRule?.target?.packageName ?: defaults.packageName) }
    var openMode by remember { mutableStateOf(openModeFromRule(editingRule, category)) }
    var actionMode by remember { mutableStateOf(editingRule?.actionMode ?: defaults.actionMode) }
    var parseAfterRedirect by remember { mutableStateOf(editingRule?.parseAfterRedirect ?: false) }
    var clearClipboardAfterJump by remember { mutableStateOf(editingRule?.clearClipboardAfterJump ?: false) }
    // v1.21 新增字段
    var priorityText by remember { mutableStateOf(editingRule?.priority?.takeIf { it != 0 }?.toString() ?: "") }
    var group by remember { mutableStateOf(editingRule?.group ?: "") }
    var excludeRegex by remember { mutableStateOf(editingRule?.excludeRegex ?: "") }
    var regexOptions by remember { mutableStateOf(editingRule?.regexOptions ?: "") }
    // v1.24 新增字段：生效条件
    var sourcePackages by remember { mutableStateOf(editingRule?.sourcePackages ?: "") }
    var activeTimeStart by remember { mutableStateOf(editingRule?.activeTimeStart ?: "") }
    var activeTimeEnd by remember { mutableStateOf(editingRule?.activeTimeEnd ?: "") }
    // v1.39 规则级通知模式（"" = 跟随全局）
    var ruleNotificationMode by remember { mutableStateOf(editingRule?.notificationMode ?: "") }
    // v1.79 触发条件 AND + 延迟跳转
    var matchAllTriggers by remember { mutableStateOf(editingRule?.matchAllTriggers ?: false) }
    var delayMillisText by remember { mutableStateOf((editingRule?.delayMillis ?: 0).takeIf { it > 0 }?.toString() ?: "") }
    var showTemplatePicker by remember { mutableStateOf(false) }
    // v1.62 高级选项默认折叠（普通用户不被正则技术字段淹没）
    var showAdvanced by remember { mutableStateOf(false) }
    // v1.77 未保存修改保护：快照对比初始值，返回时提示
    val initialSnapshot = remember(editingRule?.id, category) {
        EditorSnapshot(
            name = name,
            triggerRegexes = triggerRegexes.toList(),
            extractionRegexes = extractionRegexes.toList(),
            targetTemplate = targetTemplate,
            packageName = packageName,
            actionMode = actionMode,
            parseAfterRedirect = parseAfterRedirect,
            clearClipboardAfterJump = clearClipboardAfterJump,
            priorityText = priorityText,
            group = group,
            excludeRegex = excludeRegex,
            regexOptions = regexOptions,
            sourcePackages = sourcePackages,
            activeTimeStart = activeTimeStart,
            activeTimeEnd = activeTimeEnd,
            ruleNotificationMode = ruleNotificationMode,
            matchAllTriggers = matchAllTriggers,
            delayMillisText = delayMillisText,
        )
    }
    val isDirty = initialSnapshot != EditorSnapshot(
        name = name,
        triggerRegexes = triggerRegexes.toList(),
        extractionRegexes = extractionRegexes.toList(),
        targetTemplate = targetTemplate,
        packageName = packageName,
        actionMode = actionMode,
        parseAfterRedirect = parseAfterRedirect,
        clearClipboardAfterJump = clearClipboardAfterJump,
        priorityText = priorityText,
        group = group,
        excludeRegex = excludeRegex,
        regexOptions = regexOptions,
        sourcePackages = sourcePackages,
        activeTimeStart = activeTimeStart,
        activeTimeEnd = activeTimeEnd,
        ruleNotificationMode = ruleNotificationMode,
        matchAllTriggers = matchAllTriggers,
        delayMillisText = delayMillisText,
    )
    var showDiscardConfirm by remember { mutableStateOf(false) }
    // v1.77 编辑已有规则时顶栏可删除（破坏性操作+确认）
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // v1.77 返回保护：有未保存修改时先确认
    BackHandler(enabled = isDirty) { showDiscardConfirm = true }
    // v1.42 智能识别读取失败时手动输入兜底
    var showManualInput by remember { mutableStateOf(false) }
    // v1.141.87u 已装应用选择器（包名点选，避免手输错误）
    var showAppPicker by remember { mutableStateOf(false) }
    // v1.142.1 编辑页操作菜单（导出/分享/复制/删除收进「更多」，标题完整显示）
    var showMoreMenu by remember { mutableStateOf(false) }
    val isLinkDirectOpen = category == RuleCategory.Link && actionMode == RuleActionMode.DirectOpen
    val isCategoryDirectAppOpen = category != RuleCategory.Link && openMode == CategoryOpenMode.DirectApp
    val isCategoryUrlOpen = category != RuleCategory.Link && openMode == CategoryOpenMode.Url
    val usesExtraction = when {
        category == RuleCategory.Link -> actionMode == RuleActionMode.ParseAndOpen
            || (actionMode == RuleActionMode.WebViewResolveAndOpen && parseAfterRedirect)
            // v1.44 DirectOpen 仅当模板含占位符（需要参数）时才需要提取正则；纯打开 App 主页不需要
            || (actionMode == RuleActionMode.DirectOpen && targetTemplate.contains("\${"))
        else -> openMode == CategoryOpenMode.Url
    }
    val usesTemplate = usesExtraction || (category == RuleCategory.Link && actionMode == RuleActionMode.DirectOpen && targetTemplate.isNotBlank())
    // v1.44 上移测试输入 state：供智能识别成功后自动填入（识别→立即看到匹配/提取结果）
    var testText by remember { mutableStateOf("") }

    // v1.141.56 正则测试实时记录：testText 变化时输出命中/提取结果
    androidx.compose.runtime.LaunchedEffect(testText, triggerRegexes.toList(), extractionRegexes.toList()) {
        if (testText.isNotBlank()) {
            val hit = RulePatterns.matchesAny(triggerRegexes.toList(), testText)
            val ext = extractionRegexes.filter { it.isNotBlank() }.mapNotNull { pattern ->
                runCatching { Regex(pattern).find(testText) }.getOrNull()?.let { m ->
                    val groups = m.groups.drop(1).mapNotNull { it?.value }
                    if (groups.isEmpty()) null else "$pattern → ${groups.joinToString(",")}"
                }
            }.joinToString("; ")
            io.github.hypercopy.UiActionLogger.regexTest(triggerRegexes.filter { it.isNotBlank() }.joinToString(" | "), testText, hit, ext)
        }
    }

    Scaffold { paddingValues ->
        // v1.68 保存按钮固定底部：内容区可滚动，底部常驻保存栏
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
        Column(
            modifier = Modifier.fillMaxSize().weight(1f).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.action_back))
                }
                Text(
                    text = stringResource(
                        if (editingRule == null) R.string.editor_title_add else R.string.editor_title_edit,
                        stringResource(category.labelRes()),
                    ),
                    style = MiuixTheme.textStyles.title1,
                    modifier = Modifier.weight(1f),
                )
                if (editingRule != null) {
                    // v1.142.1 操作按钮收进「更多」菜单（标题完整显示不再被按钮挤压省略）
                    TextButton(text = stringResource(R.string.editor_more_menu), onClick = { showMoreMenu = true })
                } else {
                    // v1.141.87u 新建模式：从剪贴板导入规则 JSON（一键填充所有字段）
                    TextButton(text = stringResource(R.string.action_import_json), onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
                        if (clip.isBlank()) {
                            Toast.makeText(context, R.string.rule_toast_import_invalid, Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        val imported = runCatching {
                            ruleConfigFromJson(JSONObject(clip))
                        }.getOrNull() ?: runCatching {
                            JSONObject(clip).optJSONArray("rules")?.optJSONObject(0)?.let { ruleConfigFromJson(it) }
                        }.getOrNull()
                        if (imported == null) {
                            Toast.makeText(context, R.string.rule_toast_import_invalid, Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        if (name.isBlank()) name = imported.name
                        triggerRegexes.clear()
                        triggerRegexes.addAll(imported.triggerRegexes.ifEmpty { listOf(imported.matchRegex) }.filter { it.isNotBlank() })
                        extractionRegexes.clear()
                        extractionRegexes.addAll(imported.extractionRegexes.ifEmpty { listOf(imported.parameterRegex) }.filter { it.isNotBlank() })
                        targetTemplate = imported.target.template
                        packageName = imported.target.packageName
                        actionMode = imported.actionMode
                        parseAfterRedirect = imported.parseAfterRedirect
                        clearClipboardAfterJump = imported.clearClipboardAfterJump
                        priorityText = imported.priority.takeIf { it != 0 }?.toString() ?: ""
                        group = imported.group
                        excludeRegex = imported.excludeRegex
                        regexOptions = imported.regexOptions
                        sourcePackages = imported.sourcePackages
                        activeTimeStart = imported.activeTimeStart
                        activeTimeEnd = imported.activeTimeEnd
                        ruleNotificationMode = imported.notificationMode.orEmpty()
                        matchAllTriggers = imported.matchAllTriggers
                        delayMillisText = imported.delayMillis.takeIf { it > 0 }?.toString() ?: ""
                        Toast.makeText(context, R.string.rule_toast_import_editor, Toast.LENGTH_SHORT).show()
                    })
                }
            }

            Card {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // v1.62 分区标题：基础信息
                    SectionTitle(R.string.editor_section_basic)
                    TextField(value = name, onValueChange = { name = it }, label = stringResource(R.string.editor_label_name), singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (category == RuleCategory.Link) {
                        // v1.62 分区标题：匹配与跳转（模式/开关/正则/智能识别）
                        SectionTitle(R.string.editor_section_match)
                        ActionModeSelector(selected = actionMode, onSelected = { actionMode = it })
                        if (actionMode == RuleActionMode.WebViewResolveAndOpen) {
                            ParseAfterRedirectSwitch(checked = parseAfterRedirect, onCheckedChange = { parseAfterRedirect = it })
                        }
                    }
                    ClearClipboardAfterJumpSwitch(checked = clearClipboardAfterJump, onCheckedChange = { clearClipboardAfterJump = it })
                    // v1.62 智能识别上移+强化：主按钮样式放正则列表前，先识别再看到正则
                    TextButton(text = stringResource(R.string.editor_auto_recognize), onClick = {
                        io.github.hypercopy.clipboard.handling.ClipboardTextReader.readDelayed(context, source = "editor") { clipText ->
                            val finalText = clipText?.takeIf { it.isNotBlank() }
                                ?: io.github.hypercopy.clipboard.handling.ClipboardTextHandler.lastProcessedText
                                ?: io.github.hypercopy.clipboard.handling.ClipboardTextHandler.readPersistedLastProcessed(context)
                            if (finalText.isNullOrBlank()) {
                                // v1.42 弹手动输入框（不再只提示已空）
                                showManualInput = true
                                return@readDelayed
                            }
                            val suggestions = io.github.hypercopy.data.rules.RuleAnalyzer.analyze(finalText)
                            if (suggestions.isEmpty()) {
                                Toast.makeText(context, R.string.editor_auto_recognize_none, Toast.LENGTH_SHORT).show()
                            } else {
                                val s = suggestions.first()
                                // 填入触发器（新规则场景直接填充；已有触发器则追加不覆盖）
                                if (triggerRegexes.size == 1 && triggerRegexes[0].isBlank()) {
                                    triggerRegexes[0] = s.matchRegex
                                } else if (triggerRegexes.none { it == s.matchRegex }) {
                                    triggerRegexes += s.matchRegex
                                }
                                // v1.44 补填提取正则（带捕获组）：与建议页 toRuleConfig 对齐，触发/提取两轨完整
                                if (extractionRegexes.size == 1 && extractionRegexes[0].isBlank()) {
                                    extractionRegexes[0] = s.extractionRegex
                                } else if (extractionRegexes.none { it == s.extractionRegex }) {
                                    extractionRegexes += s.extractionRegex
                                }
                                if (name.isBlank()) name = s.platform
                                if (packageName.isBlank()) packageName = s.packageName
                                if (targetTemplate.isBlank()) targetTemplate = s.template
                                // v1.46 同步执行模式（短链识别为网页解析后打开，与官方规范对齐）
                                actionMode = s.actionMode
                                // v1.57 同步重定向后解析（短链规则：重定向后提取参数拼 App scheme）
                                parseAfterRedirect = s.parseAfterRedirect
                                // v1.58 场景联动：场景激活时新建规则自动归入场景组（避免被禁用）
                                if (group.isBlank()) group = io.github.hypercopy.data.settings.SettingsRepository(context.applicationContext).readSceneGroup()
                                // v1.44 识别成功自动填入测试输入：立即看到"命中/提取"结果，确认识别正确
                                testText = finalText
                                io.github.hypercopy.UiActionLogger.autoRecognize(finalText, "识别为「" + s.platform + "」 分类=" + category.name + " 动作=" + s.actionMode.name + " 包=" + s.packageName.ifBlank { "-" } + " 模板=" + s.template.ifBlank { "-" })
                                Toast.makeText(context, context.getString(R.string.editor_auto_recognize_ok, s.platform), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
                    RegexListEditor(
                        title = stringResource(R.string.editor_label_trigger_regexes),
                        values = triggerRegexes,
                        onChange = { index, value -> triggerRegexes[index] = value },
                        onAdd = { triggerRegexes += "" },
                        onRemove = { index -> if (triggerRegexes.size > 1) triggerRegexes.removeAt(index) },
                        // v1.71 触发正则说明
                        description = stringResource(R.string.editor_trigger_regexes_hint),
                    )
                    // v1.79 触发条件 AND 模式：所有触发正则都需匹配
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.editor_label_match_all_triggers),
                                style = MiuixTheme.textStyles.body1,
                            )
                            Text(
                                text = stringResource(R.string.editor_label_match_all_triggers_hint),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Switch(checked = matchAllTriggers, onCheckedChange = { matchAllTriggers = it })
                    }
                                        TextButton(text = stringResource(R.string.editor_template_insert), onClick = { showTemplatePicker = true })
                    if (usesExtraction) {
                        RegexListEditor(
                            title = stringResource(R.string.editor_label_extraction_regexes),
                            values = extractionRegexes,
                            onChange = { index, value -> extractionRegexes[index] = value },
                            onAdd = { extractionRegexes += "" },
                            onRemove = { index -> if (extractionRegexes.size > 1) extractionRegexes.removeAt(index) },
                            // v1.71 提取正则说明
                            description = stringResource(R.string.editor_extraction_regexes_hint),
                        )
                    }
                    if (usesTemplate) {
                        TextField(
                            value = targetTemplate,
                            onValueChange = { targetTemplate = it },
                            label = stringResource(if (category == RuleCategory.Link) R.string.editor_label_target_template else R.string.editor_label_open_content_template),
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (category == RuleCategory.Link) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextField(
                                value = packageName,
                                onValueChange = { packageName = it },
                                label = stringResource(R.string.editor_label_package_name_optional),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            // v1.141.87u 已装应用选择器
                            TextButton(text = stringResource(R.string.action_pick_app), onClick = { showAppPicker = true })
                        }
                    } else {
                        CategoryOpenModeSelector(selected = openMode, onSelected = { openMode = it })
                        if (openMode == CategoryOpenMode.DirectApp) {
                            TextField(value = packageName, onValueChange = { packageName = it }, label = stringResource(R.string.editor_label_package_name_required), singleLine = true, modifier = Modifier.fillMaxWidth())
                        } else {
                            TextField(value = packageName, onValueChange = { packageName = it }, label = stringResource(R.string.editor_label_package_name_optional), singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    // ===== v1.126 跳转增强：跳转方式徽标 + 预检 + 测试跳转 =====
                    val pm = context.packageManager
                    // v1.127b 纯文字徽标（去 emoji 防豆腐块渲染；v1.142.6e 资源化）
                    val jumpModeText = when {
                        actionMode == RuleActionMode.ClipboardWrite -> stringResource(R.string.editor_mode_clipboard_write)
                        actionMode == RuleActionMode.NotifyOnly -> stringResource(R.string.badge_notify_only)
                        targetTemplate.isBlank() && packageName.isNotBlank() -> stringResource(R.string.editor_mode_direct_open)
                        targetTemplate.startsWith("http", ignoreCase = true) -> stringResource(R.string.editor_mode_web)
                        targetTemplate.isNotBlank() -> stringResource(R.string.editor_mode_scheme)
                        else -> "—"
                    }
                    val appInstalled = packageName.isNotBlank() &&
                        runCatching { pm.getPackageInfo(packageName, 0) }.isSuccess
                    // v1.126b 修复：与运行时预检一致——有包名时只判断包是否安装
                    //（resolveActivity 在 MIUI 对显式 intent 误判 null）；仅无包名时用 resolveActivity
                    val templateResolvable = if (targetTemplate.isNotBlank() && packageName.isNotBlank()) {
                        appInstalled
                    } else if (targetTemplate.isNotBlank()) {
                        runCatching {
                            val sample = targetTemplate.replace(Regex("\\$\\{[^}]+}"), "test")
                            Intent(Intent.ACTION_VIEW, Uri.parse(sample)).resolveActivity(pm) != null
                        }.getOrDefault(false)
                    } else false
                    val precheckOk = when {
                        actionMode == RuleActionMode.ClipboardWrite -> true
                        actionMode == RuleActionMode.NotifyOnly -> true
                        targetTemplate.isNotBlank() -> templateResolvable || appInstalled
                        packageName.isNotBlank() -> appInstalled
                        else -> true
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(
                            text = jumpModeText,
                            style = MiuixTheme.textStyles.body1,
                            color = if (precheckOk) Color(0xFF00B578) else Color(0xFFFF5A52),
                            modifier = Modifier
                                .background(
                                    color = (if (precheckOk) Color(0xFF00B578) else Color(0xFFFF5A52)).copy(alpha = 0.10f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (precheckOk) stringResource(R.string.editor_jump_precheck_ok)
                            else stringResource(R.string.editor_jump_precheck_fail),
                            style = MiuixTheme.textStyles.body2,
                            color = if (precheckOk) Color(0xFF00B578) else Color(0xFFFF5A52),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 测试跳转：用当前规则配置 + 测试文本构建 intent 立即跳转
                        val testJumpSentText = stringResource(R.string.editor_test_jump_sent)
                        val testJumpFailedText = stringResource(R.string.editor_test_jump_failed)
                        TextButton(
                            text = stringResource(R.string.editor_test_jump),
                            onClick = {
                                val testRule = RuleConfig(
                                    name = name.ifBlank { context.getString(R.string.editor_test_rule_name) },
                                    category = category,
                                    actionMode = actionMode,
                                    matchRegex = triggerRegexes.firstOrNull().orEmpty(),
                                    parameterRegex = extractionRegexes.firstOrNull().orEmpty(),
                                    triggerRegexes = triggerRegexes.toList(),
                                    extractionRegexes = extractionRegexes.toList(),
                                    parseAfterRedirect = parseAfterRedirect,
                                    clearClipboardAfterJump = clearClipboardAfterJump,
                                    target = RuleTarget(
                                        type = if (targetTemplate.startsWith("intent:")) RuleTargetType.Intent else RuleTargetType.Url,
                                        template = targetTemplate,
                                        packageName = packageName,
                                    ),
                                )
                                val sampleInput = testText.ifBlank { name.ifBlank { context.getString(R.string.editor_test_text_default) } }
                                val intent = testRule.parseIntent(sampleInput, requireMatch = false)
                                if (intent != null) {
                                    PendingJumpCoordinator.submit(
                                        context.applicationContext,
                                        PendingJump.IntentJump(title = name.ifBlank { context.getString(R.string.editor_test_jump) }, intent = intent, packageName = packageName),
                                        clearClipboardAfterJump = false,
                                        notificationModeOverride = Config.JUMP_NOTIFICATION_MODE_NONE,
                                    )
                                    io.github.hypercopy.UiActionLogger.jumpTest(name.ifBlank { "未命名" }, "已提交跳转", "目标=包[" + packageName + "] 模板[" + targetTemplate + "]")
                                    Toast.makeText(context, testJumpSentText, Toast.LENGTH_SHORT).show()
                                } else {
                                    io.github.hypercopy.UiActionLogger.jumpTest(name.ifBlank { "未命名" }, "跳转失败(未生成intent)", "目标=包[" + packageName + "] 模板[" + targetTemplate + "]")
                                    Toast.makeText(context, testJumpFailedText, Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                        // Scheme 大全：打开国内 App URL scheme 参考
                        TextButton(
                            text = stringResource(R.string.editor_scheme_help),
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/github-xiaogang/URL-Schemes/blob/master/url-schemes.md"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            },
                        )
                    }
                    // ===== 正则校验 + 测试匹配（实时） =====
                    val allRegexPatterns = triggerRegexes.toList() + if (usesExtraction) extractionRegexes.toList() else emptyList()
                    val invalidRegex = RulePatterns.firstInvalid(allRegexPatterns)
                    val dangerousTrigger = triggerRegexes.firstOrNull { RulePatterns.isDangerousMatchAll(it) }
                        ?: if (triggerRegexes.none { it.isNotBlank() }) "" else null
                    val testHit = if (testText.isNotBlank()) {
                        // v1.141.87u 一致性：应用 regexOptions（i/s/m）编译，与运行时 matchesInput 对齐
                        val patterns = triggerRegexes.filter { it.isNotBlank() }.ifEmpty { listOf(".*") }
                        patterns.any { pattern ->
                            runCatching { cachedRegex(pattern, regexOptions.trim()).containsMatchIn(testText) }.getOrDefault(false)
                        }
                    } else null
                    // v1.141.87u 一致性：excludeRegex 拦截提示（命中但被排除 = 运行时不会触发）
                    val excludedByRegex = testHit == true && excludeRegex.isNotBlank() &&
                        runCatching { cachedRegex(excludeRegex.trim(), regexOptions.trim()).containsMatchIn(testText) }.getOrDefault(false)
                    val testExtractionText = if (testText.isNotBlank() && usesExtraction) {
                        extractionRegexes.filter { it.isNotBlank() }.mapNotNull { pattern ->
                            runCatching { Regex(pattern).find(testText) }.getOrNull()?.let { m ->
                                val groups = m.groups.drop(1).mapNotNull { it?.value }
                                if (groups.isEmpty()) null else "$pattern → ${groups.joinToString(",")}"
                            }
                        }.joinToString("; ")
                    } else ""
                    // v1.62 分区标题：测试验证
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionTitle(R.string.editor_section_test, modifier = Modifier.weight(1f))
                        // v1.65 测试输入清空
                        TextButton(
                            text = stringResource(R.string.action_clear),
                            onClick = { testText = "" },
                        )
                    }
                    TextField(
                        value = testText,
                        onValueChange = { testText = it },
                        label = stringResource(R.string.editor_label_test_input),
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    invalidRegex?.let {
                        Text(
                            text = stringResource(R.string.editor_regex_invalid, it),
                            style = MiuixTheme.textStyles.body2,
                            color = Color(0xFFFF5A52),
                        )
                    }
                    dangerousTrigger?.let {
                        Text(
                            text = stringResource(R.string.editor_regex_danger),
                            style = MiuixTheme.textStyles.body2,
                            color = Color(0xFFF5A623),
                        )
                    }
                    if (testText.isNotBlank()) {
                        Text(
                            text = stringResource(if (testHit == true) R.string.editor_test_matched else R.string.editor_test_unmatched),
                            style = MiuixTheme.textStyles.body2,
                            color = if (testHit == true) Color(0xFF00B578) else Color(0xFFFF5A52),
                        )
                        // v1.141.87u 一致性：命中但被排除正则拦截 → 运行时不会触发
                        if (excludedByRegex) {
                            Text(
                                text = stringResource(R.string.editor_test_excluded),
                                style = MiuixTheme.textStyles.body2,
                                color = Color(0xFFF5A623),
                            )
                        }
                        if (testExtractionText.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.editor_test_extracted, testExtractionText),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        if (testHit == true) {
                            val captureGroups = triggerRegexes.filter { it.isNotBlank() }.mapNotNull { pattern ->
                                runCatching { Regex(pattern).find(testText) }.getOrNull()?.let { m ->
                                    val groups = m.groups.drop(1).mapIndexedNotNull { idx, g -> g?.value?.let { stringResource(R.string.editor_test_group_format, idx + 1, it) } }
                                    if (groups.isEmpty()) null else groups.joinToString("  ")
                                }
                            }
                            if (captureGroups.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.editor_test_groups, captureGroups.joinToString("; ")),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            // v1.141.87u 模板渲染预览：显示 ${r1}/${input}/${url:input} 渲染后的最终跳转地址，无需真跳转即可验证
                            if (usesTemplate && targetTemplate.isNotBlank()) {
                                val extractedParams = extractionRegexes.filter { it.isNotBlank() }.mapNotNull { pattern ->
                                    runCatching { Regex(pattern).find(testText) }.getOrNull()?.let { m ->
                                        m.groups.drop(1).mapIndexedNotNull { idx, g -> g?.value?.let { "r${idx + 1}" to it } }
                                    }
                                }.flatten().toMap()
                                val params = extractedParams + ("input" to testText) + ("redirectUrl" to "")
                                val rendered = runCatching {
                                    RuleTarget(
                                        type = if (targetTemplate.startsWith("intent://", true)) RuleTargetType.Intent else RuleTargetType.Url,
                                        template = targetTemplate,
                                        packageName = packageName,
                                    ).resolveTemplate(params)
                                }.getOrNull()
                                if (!rendered.isNullOrBlank()) {
                                    Text(
                                        text = stringResource(R.string.editor_test_rendered) + "：" + rendered,
                                        style = MiuixTheme.textStyles.body2,
                                        color = Color(0xFF00B578),
                                        maxLines = 3,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Card {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // v1.39+ 文本类规则独立通知渠道：仅文本类(Text)显示，链接类(Link)还原不显示、走全局跳转渠道
                    if (category == RuleCategory.Text) {
                        top.yukonga.miuix.kmp.preference.OverlayDropdownPreference(
                            title = stringResource(R.string.editor_notification_mode),
                            summary = stringResource(R.string.editor_notification_mode_summary),
                            items = editorNotificationModeOptions().map { stringResource(it.first) },
                            selectedIndex = editorNotificationModeOptions().indexOfFirst { it.second == ruleNotificationMode }.coerceAtLeast(0),
                            startAction = {},
                            insideMargin = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                            onSelectedIndexChange = { ruleNotificationMode = editorNotificationModeOptions()[it].second },
                        )
                    }
                    // v1.62 高级选项标题可点击展开/收起
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.editor_advanced_title),
                            style = MiuixTheme.textStyles.headline1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(if (showAdvanced) R.string.editor_advanced_collapse else R.string.editor_advanced_expand),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                    if (showAdvanced) {
                        TextField(
                            value = priorityText,
                            onValueChange = { priorityText = it.filter { ch -> ch.isDigit() || ch == '-' } },
                            label = stringResource(R.string.editor_priority_summary),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = group,
                            onValueChange = { group = it },
                            label = stringResource(R.string.editor_group),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // v1.71 分组字段说明（场景联动）
                        Text(
                            text = stringResource(R.string.editor_group_hint),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        TextField(
                            value = excludeRegex,
                            onValueChange = { excludeRegex = it },
                            label = stringResource(R.string.editor_exclude_regex_summary),
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = regexOptions,
                            onValueChange = { regexOptions = it.filter { c -> c in "ism" }.take(3) },
                            label = stringResource(R.string.editor_regex_options_summary),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // v1.68 正则选项含义说明
                        Text(
                            text = stringResource(R.string.editor_regex_options_hint),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        TextField(
                            value = sourcePackages,
                            onValueChange = { sourcePackages = it },
                            label = stringResource(R.string.editor_source_packages),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // v1.68 来源包名格式说明
                        Text(
                            text = stringResource(R.string.editor_source_packages_hint),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            TextField(
                                value = activeTimeStart,
                                onValueChange = { activeTimeStart = it.filter { ch -> ch.isDigit() || ch == ':' }.take(5) },
                                label = stringResource(R.string.editor_active_time_start),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextField(
                                value = activeTimeEnd,
                                onValueChange = { activeTimeEnd = it.filter { ch -> ch.isDigit() || ch == ':' }.take(5) },
                                label = stringResource(R.string.editor_active_time_end),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // v1.68 时间格式说明
                        Text(
                            text = stringResource(R.string.editor_time_format_hint),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        // v1.79 延迟跳转（毫秒）
                        TextField(
                            value = delayMillisText,
                            onValueChange = { delayMillisText = it.filter(Char::isDigit).take(4) },
                            label = stringResource(R.string.editor_label_delay_millis),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(R.string.editor_label_delay_millis_hint),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            PlaceholderHelpCard()
        }
        // v1.68 底部常驻保存栏（滚动内容时始终可见，不再需要往回滚找保存）
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            TextButton(
                text = stringResource(R.string.action_save_rule),
                onClick = {
                    // 修复：空白规则校验（v1.33）——触发器必须至少填一个，
                    // 否则兜底 .* 会匹配一切内容造成误跳转
                    if (triggerRegexes.none { it.isNotBlank() }) {
                        Toast.makeText(context, R.string.rule_toast_trigger_required, Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    if (isLinkDirectOpen && targetTemplate.isBlank() && packageName.isBlank()) {
                        Toast.makeText(context, R.string.rule_toast_template_or_package_required, Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    if (isCategoryDirectAppOpen && packageName.isBlank()) {
                        Toast.makeText(context, R.string.rule_toast_package_required, Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    // v1.68 保存栏在 Card 外，重新计算全部正则（原局部变量不可见）
                    val invalidForSave = RulePatterns.firstInvalid(
                        triggerRegexes.toList() + if (usesExtraction) extractionRegexes.toList() else emptyList(),
                    )
                    if (invalidForSave != null) {
                        Toast.makeText(context, context.getString(R.string.rule_toast_regex_invalid, invalidForSave), Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val rule = RuleConfig(
                        id = editingRule?.id ?: ruleId.ifBlank { UUID.randomUUID().toString() },
                        name = name.ifBlank { context.getString(R.string.rule_unnamed) },
                        category = category,
                        actionMode = actionMode,
                        matchRegex = triggerRegexes.firstNonBlankOr(""),
                        // v1.44 移除 `.*(.+).*` 垃圾兜底：提取正则留空=不提取（引擎不再拦截），
                        // 避免"匹配一切+贪婪提取整段"的隐藏数据污染
                        parameterRegex = if (usesExtraction) extractionRegexes.firstOrNull { it.isNotBlank() }.orEmpty() else "",
                        triggerRegexes = triggerRegexes.filter { it.isNotBlank() },
                        extractionRegexes = if (usesExtraction) extractionRegexes.filter { it.isNotBlank() } else emptyList(),
                        parseAfterRedirect = category == RuleCategory.Link && actionMode == RuleActionMode.WebViewResolveAndOpen && parseAfterRedirect,
                        clearClipboardAfterJump = clearClipboardAfterJump,
                        priority = priorityText.toIntOrNull() ?: 0,
                        group = group.trim(),
                        excludeRegex = excludeRegex.trim(),
                        regexOptions = regexOptions.trim(),
                        sourcePackages = sourcePackages.trim(),
                        activeTimeStart = activeTimeStart.trim(),
                        activeTimeEnd = activeTimeEnd.trim(),
                        notificationMode = ruleNotificationMode.ifBlank { null },
                        // v1.79 AND 触发条件 + 延迟跳转（延迟上限 5000ms，通知模式 5s 过期内）
                        matchAllTriggers = matchAllTriggers,
                        delayMillis = delayMillisText.toIntOrNull()?.coerceIn(0, 5000) ?: 0,
                        target = RuleTarget(
                            type = if (targetTemplate.startsWith("intent://", true)) RuleTargetType.Intent else RuleTargetType.Url,
                            template = if (usesTemplate) targetTemplate else "",
                            packageName = if (category == RuleCategory.Link || isCategoryUrlOpen || isCategoryDirectAppOpen) packageName else "",
                        ),
                    )
                    io.github.hypercopy.UiActionLogger.ruleChanged(
                        if (editingRule == null) "新增" else "修改",
                        rule.name.ifBlank { "未命名" },
                        "分类=" + rule.category.name +
                            " 动作=" + rule.actionMode.name +
                            " 触发=[" + rule.triggerRegexes.joinToString(" | ") + "]" +
                            " 提取=[" + rule.extractionRegexes.joinToString(" | ") + "]" +
                            " 目标包=[" + rule.target.packageName + "]" +
                            " 目标模板=[" + rule.target.template + "]"
                    )
                    when (repository.saveRuleMerged(rule)) {
                        io.github.hypercopy.data.rules.RuleSaveResult.Duplicate ->
                            Toast.makeText(
                                context,
                                context.getString(R.string.rule_toast_duplicate_with_name, repository.findDuplicate(rule)?.name.orEmpty()),
                                Toast.LENGTH_SHORT,
                            ).show()
                        io.github.hypercopy.data.rules.RuleSaveResult.Rejected ->
                            Toast.makeText(context, R.string.rule_toast_trigger_required, Toast.LENGTH_SHORT).show()
                        io.github.hypercopy.data.rules.RuleSaveResult.Merged ->
                            Toast.makeText(context, R.string.rule_toast_merged_same_target, Toast.LENGTH_SHORT).show()
                        else -> {
                            Toast.makeText(context, R.string.rule_toast_saved, Toast.LENGTH_SHORT).show()
                            onBack()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
        }
    }
    WindowDialog(
        title = stringResource(R.string.editor_template_title),
        summary = stringResource(R.string.editor_template_summary),
        show = showTemplatePicker,
        onDismissRequest = { showTemplatePicker = false },
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // v1.71 默认名集合（clickable 内不能调 stringResource，提前在 Composable 上下文取值）
            val defaultNames = setOf(
                stringResource(R.string.rule_unnamed),
                stringResource(R.string.editor_rule_name_new),
            )
            // v1.71 平台示例规则（官方文档 3 例，一键填充完整规则）
            Text(
                text = stringResource(R.string.editor_template_examples_title),
                style = MiuixTheme.textStyles.title3,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            Text(
                text = stringResource(R.string.editor_template_examples_hint),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            exampleRules.forEach { example ->
                Text(
                    text = "${stringResource(example.nameRes)}  ·  ${stringResource(example.descriptionRes)}",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // 一键填充完整规则
                            triggerRegexes.clear()
                            triggerRegexes.add(example.matchRegex)
                            extractionRegexes.clear()
                            extractionRegexes.add(example.extractionRegex)
                            targetTemplate = example.template
                            packageName = example.packageName
                            if (name.isBlank() || name in defaultNames) {
                                name = context.getString(example.nameRes)
                            }
                            actionMode = example.actionMode
                            parseAfterRedirect = example.parseAfterRedirect
                            showTemplatePicker = false
                            Toast.makeText(context, context.getString(R.string.editor_example_applied, context.getString(example.nameRes)), Toast.LENGTH_SHORT).show()
                        }
                        .padding(vertical = 8.dp),
                )
            }
            Text(
                text = stringResource(R.string.editor_template_title),
                style = MiuixTheme.textStyles.title3,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            regexTemplates.forEach { (nameRes, pattern) ->
                Text(
                    text = "${stringResource(nameRes)}  ·  $pattern",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (triggerRegexes.size == 1 && triggerRegexes[0].isBlank()) {
                                triggerRegexes[0] = pattern
                            } else {
                                triggerRegexes += pattern
                            }
                            showTemplatePicker = false
                        }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
// v1.141.87u 已装应用选择器（Link 分类包名点选）
    WindowDialog(
        title = stringResource(R.string.editor_pick_app_title),
        summary = stringResource(R.string.editor_pick_app_title),
        show = showAppPicker,
        onDismissRequest = { showAppPicker = false },
    ) {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = remember {
            context.packageManager.queryIntentActivities(launchIntent, 0)
                .sortedBy { it.loadLabel(context.packageManager).toString() }
        }
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            apps.forEach { info ->
                val label = info.loadLabel(context.packageManager).toString()
                Text(
                    text = "$label  ·  ${info.activityInfo.packageName}",
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            packageName = info.activityInfo.packageName
                            if (name.isBlank()) name = label
                            showAppPicker = false
                        }
                        .padding(vertical = 8.dp),
                    maxLines = 1,
                )
            }
        }
    }
    // v1.142.1 编辑页「更多」菜单：导出/分享/复制/删除
    WindowDialog(
        title = stringResource(R.string.editor_more_menu),
        summary = "",
        show = showMoreMenu,
        onDismissRequest = { showMoreMenu = false },
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val menuItems = listOf(
                R.string.action_export_short,
                R.string.action_share_short,
                R.string.action_duplicate_rule,
                R.string.action_delete,
            )
            menuItems.forEach { itemRes ->
                Text(
                    text = stringResource(itemRes),
                    style = MiuixTheme.textStyles.body1,
                    color = if (itemRes == R.string.action_delete) Color(0xFFFF5A52) else MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showMoreMenu = false
                            when (itemRes) {
                                R.string.action_export_short -> {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText(
                                            context.getString(R.string.action_export_rule),
                                            editingRule!!.toJson().toString(2),
                                        ),
                                    )
                                    Toast.makeText(context, R.string.rule_toast_exported, Toast.LENGTH_SHORT).show()
                                }
                                R.string.action_share_short -> {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, githubRuleSubmissionUri(editingRule!!)))
                                }
                                R.string.action_duplicate_rule -> {
                                    if (triggerRegexes.none { it.isNotBlank() }) {
                                        Toast.makeText(context, R.string.rule_toast_trigger_required, Toast.LENGTH_SHORT).show()
                                    } else {
                                    val copy = RuleConfig(
                                        id = UUID.randomUUID().toString(),
                                        name = (name.ifBlank { context.getString(R.string.rule_unnamed) }) + context.getString(R.string.rule_copy_suffix),
                                        category = category,
                                        actionMode = actionMode,
                                        matchRegex = triggerRegexes.firstNonBlankOr(""),
                                        parameterRegex = if (usesExtraction) extractionRegexes.firstOrNull { it.isNotBlank() }.orEmpty() else "",
                                        triggerRegexes = triggerRegexes.filter { it.isNotBlank() },
                                        extractionRegexes = if (usesExtraction) extractionRegexes.filter { it.isNotBlank() } else emptyList(),
                                        parseAfterRedirect = parseAfterRedirect,
                                        clearClipboardAfterJump = clearClipboardAfterJump,
                                        priority = priorityText.toIntOrNull() ?: 0,
                                        group = group.trim(),
                                        excludeRegex = excludeRegex.trim(),
                                        regexOptions = regexOptions.trim(),
                                        sourcePackages = sourcePackages.trim(),
                                        activeTimeStart = activeTimeStart.trim(),
                                        activeTimeEnd = activeTimeEnd.trim(),
                                        notificationMode = ruleNotificationMode.ifBlank { null },
                                        matchAllTriggers = matchAllTriggers,
                                        delayMillis = delayMillisText.toIntOrNull()?.coerceIn(0, 5000) ?: 0,
                                        target = RuleTarget(
                                            type = if (targetTemplate.startsWith("intent://", true)) RuleTargetType.Intent else RuleTargetType.Url,
                                            template = if (usesTemplate) targetTemplate else "",
                                            packageName = packageName,
                                        ),
                                    )
                                    repository.saveRule(copy)
                                    io.github.hypercopy.UiActionLogger.ruleChanged("复制", copy.name, "源=" + editingRule!!.name)
                                    Toast.makeText(context, R.string.rule_toast_rule_copied, Toast.LENGTH_SHORT).show()
                                    }
                                }
                                R.string.action_delete -> showDeleteConfirm = true
                            }
                        }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
    // v1.77 未保存修改确认（返回时）
    WindowDialog(
        title = stringResource(R.string.editor_discard_title),
        summary = stringResource(R.string.editor_discard_summary),
        show = showDiscardConfirm,
        onDismissRequest = { showDiscardConfirm = false },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = stringResource(R.string.editor_discard_continue),
                onClick = { showDiscardConfirm = false },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.editor_discard_confirm),
                onClick = {
                    showDiscardConfirm = false
                    onBack()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(textColor = Color(0xFFFF5A52)),
            )
        }
    }
    // v1.77 删除规则确认
    WindowDialog(
        title = stringResource(R.string.editor_delete_title),
        summary = stringResource(R.string.editor_delete_summary),
        show = showDeleteConfirm,
        onDismissRequest = { showDeleteConfirm = false },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = stringResource(R.string.action_cancel),
                onClick = { showDeleteConfirm = false },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.editor_delete_confirm),
                onClick = {
                    showDeleteConfirm = false
                    val id = editingRule?.id ?: run { onBack(); return@TextButton }
                    repository.moveToTrash(setOf(id))
                    io.github.hypercopy.UiActionLogger.ruleChanged("删除", editingRule?.name?.ifBlank { "未命名" } ?: "未知", "id=" + id)
                    Toast.makeText(context, R.string.rule_toast_moved_to_trash, Toast.LENGTH_SHORT).show()
                    onBack()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(textColor = Color(0xFFFF5A52)),
            )
        }
    }
// v1.42 智能识别读取失败时手动输入兜底
    io.github.hypercopy.ui.components.ClipboardManualInputDialog(
        show = showManualInput,
        onDismiss = { showManualInput = false },
        onConfirm = { input ->
            showManualInput = false
            val suggestions = io.github.hypercopy.data.rules.RuleAnalyzer.analyze(input)
            if (suggestions.isNotEmpty()) {
                val s = suggestions.first()
                if (triggerRegexes.size == 1 && triggerRegexes[0].isBlank()) {
                    triggerRegexes[0] = s.matchRegex
                } else if (triggerRegexes.none { it == s.matchRegex }) {
                    triggerRegexes += s.matchRegex
                }
                if (name.isBlank()) name = s.platform
                if (packageName.isBlank()) packageName = s.packageName
                if (targetTemplate.isBlank()) targetTemplate = s.template
                // v1.46 同步执行模式（手动输入识别同按钮入口）
                actionMode = s.actionMode
                // v1.57 同步重定向后解析（手动输入同按钮入口）
                parseAfterRedirect = s.parseAfterRedirect
                // v1.58 场景联动（手动输入同按钮入口）
                if (group.isBlank()) group = io.github.hypercopy.data.settings.SettingsRepository(context.applicationContext).readSceneGroup()
                Toast.makeText(context, context.getString(R.string.editor_auto_recognize_ok, s.platform), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.editor_auto_recognize_none, Toast.LENGTH_SHORT).show()
            }
        },
    )
}
/** v1.62 编辑器分区标题（v1.65 支持 modifier） */
@Composable
private fun SectionTitle(textRes: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(textRes),
        style = MiuixTheme.textStyles.title3,
        color = MiuixTheme.colorScheme.primary,
        modifier = modifier,
    )
}

@Composable
private fun ClearClipboardAfterJumpSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = stringResource(R.string.editor_clear_clipboard_after_jump), style = MiuixTheme.textStyles.headline1)
            Text(text = stringResource(R.string.editor_clear_clipboard_after_jump_summary), style = MiuixTheme.textStyles.body2)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ParseAfterRedirectSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = stringResource(R.string.editor_parse_after_redirect), style = MiuixTheme.textStyles.headline1)
            Text(text = stringResource(R.string.editor_parse_after_redirect_summary), style = MiuixTheme.textStyles.body2)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RegexListEditor(
    title: String,
    values: List<String>,
    onChange: (Int, String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    // v1.71 字段说明（对齐官方文档：匹配正则=判断是否命中 / 参数正则=提取参数）
    description: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MiuixTheme.textStyles.headline1)
        if (description != null) {
            Text(
                text = description,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        values.forEachIndexed { index, value ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = value,
                    onValueChange = { onChange(index, it) },
                    label = "${title} ${index + 1}",
                    maxLines = 3,
                    modifier = Modifier.weight(1f),
                )
                if (values.size > 1) TextButton(text = stringResource(R.string.action_delete), onClick = { onRemove(index) })
            }
        }
        TextButton(text = stringResource(R.string.editor_action_add_regex), onClick = onAdd, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun PlaceholderHelpCard() {
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val placeholders = listOf(
        PlaceholderHelp("${'$'}{input}", stringResource(R.string.editor_placeholder_help_input)),
        PlaceholderHelp("${'$'}{url:input}", stringResource(R.string.editor_placeholder_help_url_input)),
        PlaceholderHelp("${'$'}{redirectUrl}", stringResource(R.string.editor_placeholder_help_redirect)),
        // v1.71 补全官方命名与函数占位符（对齐 hypercopy.1812z.top 制作规则文档）
        PlaceholderHelp("${'$'}{p1}", stringResource(R.string.editor_placeholder_help_p)),
        PlaceholderHelp("${'$'}{r1}", stringResource(R.string.editor_placeholder_help_regex)),
        PlaceholderHelp("${'$'}{r1_2}", stringResource(R.string.editor_placeholder_help_regex_group)),
        PlaceholderHelp("${'$'}{pkg}", stringResource(R.string.editor_placeholder_help_pkg)),
        PlaceholderHelp("${'$'}{time:yyyy-MM-dd HH:mm}", stringResource(R.string.editor_placeholder_help_time)),
        PlaceholderHelp("${'$'}{lower:key}", stringResource(R.string.editor_placeholder_help_lower)),
        PlaceholderHelp("${'$'}{upper:key}", stringResource(R.string.editor_placeholder_help_upper)),
        PlaceholderHelp("${'$'}{encode:key}", stringResource(R.string.editor_placeholder_help_encode)),
        PlaceholderHelp("${'$'}{url:key}", stringResource(R.string.editor_placeholder_help_url_key)),
        PlaceholderHelp("${'$'}{raw:r1}", stringResource(R.string.editor_placeholder_help_raw)),
    )
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = stringResource(R.string.editor_placeholder_help_title), style = MiuixTheme.textStyles.headline1)
            Text(text = stringResource(R.string.editor_placeholder_help_copy_hint), style = MiuixTheme.textStyles.body2)
            placeholders.forEach { item ->
                Text(
                    text = item.description,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard.setPrimaryClip(ClipData.newPlainText(item.placeholder, item.placeholder))
                            Toast.makeText(context, context.getString(R.string.editor_placeholder_copied, item.placeholder), Toast.LENGTH_SHORT).show()
                        }
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

private data class PlaceholderHelp(val placeholder: String, val description: String)

/** v1.77 编辑器字段快照：返回时对比是否未保存修改 */
private data class EditorSnapshot(
    val name: String,
    val triggerRegexes: List<String>,
    val extractionRegexes: List<String>,
    val targetTemplate: String,
    val packageName: String,
    val actionMode: RuleActionMode,
    val parseAfterRedirect: Boolean,
    val clearClipboardAfterJump: Boolean,
    val priorityText: String,
    val group: String,
    val excludeRegex: String,
    val regexOptions: String,
    val sourcePackages: String,
    val activeTimeStart: String,
    val activeTimeEnd: String,
    val ruleNotificationMode: String,
    // v1.79 新增
    val matchAllTriggers: Boolean,
    val delayMillisText: String,
)

private data class EditorDefaults(
    val name: String,
    val matchRegex: String,
    val parameterRegex: String,
    val targetTemplate: String,
    val packageName: String,
    val actionMode: RuleActionMode,
)

private enum class CategoryOpenMode {
    DirectApp,
    Url,
}

@Composable
private fun linkActionTabs() = listOf(
    stringResource(R.string.editor_link_action_parse),
    stringResource(R.string.editor_link_action_direct),
    stringResource(R.string.editor_link_action_webview),
    stringResource(R.string.editor_link_action_clipboard_write),
    stringResource(R.string.editor_link_action_notify_only),
)

@Composable
private fun categoryOpenModeTabs() = listOf(
    stringResource(R.string.editor_open_app),
    stringResource(R.string.editor_open_url),
)

private fun defaultEditorValues(context: Context, category: RuleCategory, sourceUrl: String, targetUrl: String): EditorDefaults = when (category) {
    RuleCategory.Link -> EditorDefaults(
        name = ruleNameFromTarget(context, targetUrl),
        matchRegex = if (sourceUrl.isBlank()) ".*" else ".*${Regex.escape(sourceUrl)}.*",
        parameterRegex = ".*(.+).*",
        targetTemplate = targetUrl.ifBlank { "${'$'}{input}" },
        packageName = parsePackageName(targetUrl),
        actionMode = if (sourceUrl.isNotBlank()) RuleActionMode.WebViewResolveAndOpen else RuleActionMode.DirectOpen,
    )

    RuleCategory.Address -> EditorDefaults(
        name = context.getString(R.string.editor_default_address_name),
        matchRegex = "(?=.*(地址|省|市|区)).{10,}",
        parameterRegex = "(.+)",
        targetTemplate = "${'$'}{input}",
        packageName = "",
        actionMode = RuleActionMode.DirectOpen,
    )

    RuleCategory.Text -> EditorDefaults(
        name = "",
        matchRegex = "",
        parameterRegex = "(.+)",
        targetTemplate = "${'$'}{input}",
        packageName = "",
        actionMode = RuleActionMode.DirectOpen,
    )

    RuleCategory.Express -> EditorDefaults(
        name = "",
        matchRegex = "",
        parameterRegex = "",
        targetTemplate = "",
        packageName = "",
        actionMode = RuleActionMode.DirectOpen,
    )
}

private fun openModeFromRule(rule: RuleConfig?, category: RuleCategory): CategoryOpenMode {
    if (category == RuleCategory.Link) return CategoryOpenMode.DirectApp
    return if (rule?.target?.template.isNullOrBlank() && !rule?.target?.packageName.isNullOrBlank()) {
        CategoryOpenMode.DirectApp
    } else {
        CategoryOpenMode.Url
    }
}

private fun RuleCategory.labelRes(): Int = when (this) {
    RuleCategory.Link -> R.string.category_link
    RuleCategory.Text -> R.string.category_text
    RuleCategory.Address -> R.string.category_address
    RuleCategory.Express -> R.string.category_express
}

@Composable
private fun ActionModeSelector(selected: RuleActionMode, onSelected: (RuleActionMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.editor_action_mode), style = MiuixTheme.textStyles.headline1)
        TabRowWithContour(
            tabs = linkActionTabs(),
            selectedTabIndex = selected.tabIndex(),
            onTabSelected = { onSelected(ruleActionModeFromTab(it)) },
            modifier = Modifier.fillMaxWidth(),
        )
        // v1.63 人性化：模式选择后显示行为说明，让用户知道选了什么
        Text(
            text = stringResource(
                when (selected) {
                    RuleActionMode.ParseAndOpen -> R.string.editor_action_mode_parse_hint
                    RuleActionMode.DirectOpen -> R.string.editor_action_mode_direct_hint
                    RuleActionMode.WebViewResolveAndOpen -> R.string.editor_action_mode_webview_hint
                    RuleActionMode.ClipboardWrite -> R.string.editor_action_mode_clipboard_write_hint
                    RuleActionMode.NotifyOnly -> R.string.editor_action_mode_notify_only_hint
                },
            ),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun CategoryOpenModeSelector(selected: CategoryOpenMode, onSelected: (CategoryOpenMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.editor_open_mode), style = MiuixTheme.textStyles.headline1)
        TabRowWithContour(
            tabs = categoryOpenModeTabs(),
            selectedTabIndex = selected.tabIndex(),
            onTabSelected = { onSelected(categoryOpenModeFromTab(it)) },
            modifier = Modifier.fillMaxWidth(),
        )
        // v1.71 行为说明（对齐 Link 分类的执行类型说明，补 v1.63 遗漏）
        Text(
            text = stringResource(
                when (selected) {
                    CategoryOpenMode.DirectApp -> R.string.editor_open_mode_direct_app_hint
                    CategoryOpenMode.Url -> R.string.editor_open_mode_url_hint
                },
            ),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private fun RuleActionMode.tabIndex(): Int = when (this) {
    RuleActionMode.ParseAndOpen -> 0
    RuleActionMode.DirectOpen -> 1
    RuleActionMode.WebViewResolveAndOpen -> 2
    RuleActionMode.ClipboardWrite -> 3
    RuleActionMode.NotifyOnly -> 4
}
private fun ruleActionModeFromTab(index: Int): RuleActionMode = when (index) {
    1 -> RuleActionMode.DirectOpen
    2 -> RuleActionMode.WebViewResolveAndOpen
    3 -> RuleActionMode.ClipboardWrite
    4 -> RuleActionMode.NotifyOnly
    else -> RuleActionMode.ParseAndOpen
}

private fun CategoryOpenMode.tabIndex(): Int = when (this) {
    CategoryOpenMode.DirectApp -> 0
    CategoryOpenMode.Url -> 1
}

private fun categoryOpenModeFromTab(index: Int): CategoryOpenMode = when (index) {
    1 -> CategoryOpenMode.Url
    else -> CategoryOpenMode.DirectApp
}

private fun ruleNameFromTarget(context: Context, targetUrl: String): String {
    val uri = runCatching { Uri.parse(targetUrl) }.getOrNull()
    return when {
        uri?.scheme?.isNotBlank() == true -> context.getString(R.string.editor_rule_name_from_scheme, uri.scheme)
        else -> context.getString(R.string.editor_rule_name_new)
    }
}

private fun parsePackageName(targetUrl: String): String {
    return runCatching { Intent.parseUri(targetUrl, Intent.URI_INTENT_SCHEME).`package`.orEmpty() }.getOrDefault("")
}

private fun List<String>.firstNonBlankOr(default: String): String = firstOrNull { it.isNotBlank() } ?: default


/** 常用正则模板（v1.25）：一键插入触发器正则；v1.71 补平台链接模板 */
private val regexTemplates: List<Pair<Int, String>> = listOf(
    R.string.template_generic_url to """https?://[^\s]+""",
    R.string.template_taobao_command to """(?:🔐|\$|_|￥|₳|€|£|₤)[A-Za-z0-9]{6,20}(?:₤|🔐|\$|_|￥|₳|€|£)""",
    R.string.template_jd_command to """¥[A-Za-z0-9]{6,20}¥""",
    R.string.template_taobao_short to """[A-Za-z0-9]{10,24}""",
    R.string.template_taobao_order to """\b\d{15,19}\b""",
    R.string.template_tracking to """\b[A-Z0-9]{10,24}\b""",
    R.string.template_phone to """1[3-9]\d{9}""",
    R.string.template_email to """[\w.+-]+@[\w-]+\.[\w.-]+""",
    R.string.template_id_card to """\b\d{17}[\dXx]\b""",
    R.string.template_date to """\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?""",
    // v1.71 平台链接模板（与智能识别能力对齐）
    R.string.template_douyin_short to """.*v\.douyin\.com.*""",
    R.string.template_kuaishou_short to """.*v\.kuaishou\.com.*""",
    R.string.template_bilibili_short to """.*b23\.tv.*""",
    R.string.template_xhs_short to """.*xhslink\.com.*""",
    R.string.template_weibo_link to """.*weibo\.(com|cn)/\d+.*""",
    R.string.template_meituan_link to """.*meituan\.com.*""",
)

/** v1.71 平台示例规则（对齐官方文档 hypercopy.1812z.top/制作规则 的三个经典示例） */
private data class ExampleRule(
    val nameRes: Int,
    val descriptionRes: Int,
    val matchRegex: String,
    val extractionRegex: String,
    val template: String,
    val packageName: String,
    val actionMode: RuleActionMode,
    val parseAfterRedirect: Boolean = false,
)
private val exampleRules: List<ExampleRule> = listOf(
    ExampleRule(
        nameRes = R.string.example_bilibili_name,
        descriptionRes = R.string.example_bilibili_desc,
        matchRegex = """.*bilibili\.com.*""",
        extractionRegex = """.*\/video\/(BV[0-9A-Za-z]+).*""",
        template = "bilibili://video/\${p1}",
        packageName = "tv.danmaku.bili",
        actionMode = RuleActionMode.ParseAndOpen,
    ),
    ExampleRule(
        nameRes = R.string.example_amap_name,
        descriptionRes = R.string.example_amap_desc,
        matchRegex = """(?=.*(地址|省|市|镇)).{10,}""",
        extractionRegex = "",
        template = "androidamap://poi?keywords=\${input}",
        packageName = "com.autonavi.minimap",
        actionMode = RuleActionMode.DirectOpen,
    ),
    ExampleRule(
        nameRes = R.string.example_douyin_name,
        descriptionRes = R.string.example_douyin_desc,
        matchRegex = """.*v\.douyin\.com.*""",
        extractionRegex = """.*(?:share/)?video/(\d+).*""",
        template = "snssdk1128://aweme/detail/\${r1}",
        packageName = "com.ss.android.ugc.aweme",
        actionMode = RuleActionMode.WebViewResolveAndOpen,
        parseAfterRedirect = true,
    ),
)
/**
 * v1.39 规则级通知模式选项：标签 to 值（"" = 跟随全局）
 */
private fun editorNotificationModeOptions(): List<Pair<Int, String>> = listOf(
    R.string.notif_follow_global to "",
    R.string.notif_normal_click to io.github.hypercopy.Config.JUMP_NOTIFICATION_MODE_NORMAL,
    R.string.notif_live to io.github.hypercopy.Config.JUMP_NOTIFICATION_MODE_LIVE,
    R.string.notif_miui_island to io.github.hypercopy.Config.JUMP_NOTIFICATION_MODE_MIUI_ISLAND,
    R.string.notif_none_direct to io.github.hypercopy.Config.JUMP_NOTIFICATION_MODE_NONE,
)