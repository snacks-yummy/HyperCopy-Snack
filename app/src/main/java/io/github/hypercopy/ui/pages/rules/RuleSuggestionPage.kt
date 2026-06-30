package io.github.hypercopy.ui.pages.rules

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hypercopy.R
import io.github.hypercopy.clipboard.handling.ClipboardTextReader
import io.github.hypercopy.data.rules.RuleActionMode
import io.github.hypercopy.data.rules.RuleAnalyzer
import io.github.hypercopy.data.rules.RuleCategory
import io.github.hypercopy.data.rules.RuleConfig
import io.github.hypercopy.data.rules.resolveTemplate
import io.github.hypercopy.data.rules.RuleRepository
import io.github.hypercopy.data.rules.RuleTarget
import io.github.hypercopy.data.rules.RuleTargetType
import io.github.hypercopy.data.rules.sameContentAs
import io.github.hypercopy.ui.activities.RuleEditorActivity
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 规则建议页：对未命中规则的内容自动分析，生成候选规则，一键保存。
 */
@Composable
fun RuleSuggestionPage(
    initialText: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { RuleRepository(context.applicationContext) }
    var text by remember { mutableStateOf(initialText) }
    var suggestions by remember { mutableStateOf<List<RuleAnalyzer.Suggestion>>(emptyList()) }
    var analyzed by remember { mutableStateOf(false) }
    // v1.42 剪贴板读取失败时手动输入兜底
    var showManualInput by remember { mutableStateOf(false) }
    // v1.77 返回保护：输入了内容但未保存时确认
    var showDiscardConfirm by remember { mutableStateOf(false) }
    BackHandler {
        if (text.isNotBlank() || suggestions.isNotEmpty()) {
            showDiscardConfirm = true
        } else {
            onBack()
        }
    }

    // 初始带入文本（如从剪贴板添加）时自动分析，直达结果
    LaunchedEffect(Unit) {
        if (initialText.isNotBlank()) {
            suggestions = RuleAnalyzer.analyze(initialText)
            analyzed = true
        }
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.action_back))
                }
                Text(
                    text = stringResource(R.string.suggestion_title),
                    style = MiuixTheme.textStyles.title1,
                    modifier = Modifier.weight(1f),
                )
            }

            Card {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text = stringResource(R.string.suggestion_input_hint), style = MiuixTheme.textStyles.headline1)
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // v1.72 超长提示（上限对齐剪贴板处理长度）
                    if (text.length > io.github.hypercopy.Config.CLIPBOARD_TEXT_MAX_LENGTH) {
                        Text(
                            text = stringResource(R.string.suggestion_too_long, text.length, io.github.hypercopy.Config.CLIPBOARD_TEXT_MAX_LENGTH),
                            style = MiuixTheme.textStyles.body2,
                            color = Color(0xFFF5A623),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            text = stringResource(R.string.action_paste_from_clipboard),
                            onClick = {
                                // 延迟+重试读取（避开 Shizuku 悬浮 Activity 抢焦点竞态），结果主线程回调
                                ClipboardTextReader.readDelayed(context, source = "suggestion") { clipText ->
                                    // v1.40 兜底：剪贴板为空/被清理时，用最近一次处理过的文本
                                    val finalText = clipText?.takeIf { it.isNotBlank() }
                                        ?: io.github.hypercopy.clipboard.handling.ClipboardTextHandler.lastProcessedText
                                    if (finalText.isNullOrBlank()) {
                                        // v1.42 弹手动输入框（不再只提示已空）
                                        showManualInput = true
                                        return@readDelayed
                                    }
                                    text = finalText
                                    // 粘贴后自动分析，直达结果
                                    suggestions = RuleAnalyzer.analyze(finalText)
                                    analyzed = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        // v1.64 清空：清空输入+分析结果，方便重新粘贴识别
                        TextButton(
                            text = stringResource(R.string.action_clear),
                            onClick = {
                                text = ""
                                suggestions = emptyList()
                                analyzed = false
                            },
                        )
                        TextButton(
                            text = stringResource(R.string.action_analyze),
                            onClick = {
                                suggestions = RuleAnalyzer.analyze(text)
                                analyzed = true
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }

            when {
                !analyzed -> Unit
                suggestions.isEmpty() -> Card {
                    Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = stringResource(R.string.suggestion_empty), style = MiuixTheme.textStyles.title3)
                        Text(
                            text = stringResource(R.string.suggestion_empty_hint),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        // v1.72 空态加第二个入口：模拟浏览器抓取（适合短链跳转）
                        TextButton(
                            text = stringResource(R.string.suggestion_open_browser),
                            onClick = {
                                context.startActivity(
                                    Intent(context, io.github.hypercopy.ui.activities.RuleBrowserActivity::class.java),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
                else -> {
                    var existingRules by remember { mutableStateOf(repository.readRules()) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.suggestion_analyzed_count, suggestions.size),
                            style = MiuixTheme.textStyles.title3,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        // v1.72 全部保存：多条建议（多平台口令）一键保存
                        if (suggestions.any { s -> existingRules.none { it.sameContentAs(s.toRuleConfig(context)) } }) {
                            TextButton(
                                text = stringResource(R.string.suggestion_save_all),
                                onClick = {
                                    var saved = 0
                                    var skipped = 0
                                    suggestions.forEach { s ->
                                        when (repository.saveRuleMerged(s.toRuleConfig(context))) {
                                            io.github.hypercopy.data.rules.RuleSaveResult.Duplicate,
                                            io.github.hypercopy.data.rules.RuleSaveResult.Rejected,
                                            -> skipped++
                                            else -> saved++
                                        }
                                    }
                                    existingRules = repository.readRules()
                                    val msg = if (skipped > 0) {
                                        context.getString(R.string.suggestion_save_all_done_with_skip, saved, skipped)
                                    } else {
                                        context.getString(R.string.suggestion_save_all_done, saved)
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    }
                    suggestions.forEach { suggestion ->
                        SuggestionCard(
                            suggestion = suggestion,
                            sourceText = text,
                            duplicateOf = existingRules.firstOrNull { it.sameContentAs(suggestion.toRuleConfig(context)) },
                            existingRules = existingRules,
                            onSave = {
                                when (repository.saveRuleMerged(suggestion.toRuleConfig(context))) {
                                    io.github.hypercopy.data.rules.RuleSaveResult.Duplicate ->
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.rule_toast_duplicate_with_name, repository.findDuplicate(suggestion.toRuleConfig(context))?.name.orEmpty()),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    io.github.hypercopy.data.rules.RuleSaveResult.Merged ->
                                        Toast.makeText(context, R.string.rule_toast_merged_same_target, Toast.LENGTH_SHORT).show()
                                    else -> {
                                        Toast.makeText(context, R.string.rule_toast_saved, Toast.LENGTH_SHORT).show()
                                        // 保存成功后刷新"已添加"状态，候选卡立即变为已添加（灰显）
                                        existingRules = repository.readRules()
                                    }
                                }
                            },
                            onEdit = {
                                val rule = suggestion.toRuleConfig(context)
                                when (repository.saveRule(rule)) {
                                    io.github.hypercopy.data.rules.RuleSaveResult.Duplicate ->
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.rule_toast_duplicate_with_name, repository.findDuplicate(rule)?.name.orEmpty()),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    else -> {
                                        context.startActivity(
                                            Intent(context, RuleEditorActivity::class.java)
                                                .putExtra(RuleEditorActivity.EXTRA_RULE_ID, rule.id)
                                                .putExtra(RuleEditorActivity.EXTRA_CATEGORY, RuleCategory.Link.value),
                                        )
                                        onBack()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    // v1.42 剪贴板读取失败时手动输入兜底
    io.github.hypercopy.ui.components.ClipboardManualInputDialog(
        show = showManualInput,
        onDismiss = { showManualInput = false },
        onConfirm = { input ->
            showManualInput = false
            text = input
            suggestions = RuleAnalyzer.analyze(input)
            analyzed = true
        },
    )
    // v1.77 返回确认对话框
    WindowDialog(
        title = stringResource(R.string.suggestion_discard_title),
        summary = stringResource(R.string.suggestion_discard_summary),
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
}

/** 把分析候选转换为规则配置（内容与内置/已存口令规则一致 → 去重自动合并） */
private fun RuleAnalyzer.Suggestion.toRuleConfig(context: Context): RuleConfig = RuleConfig(
    name = platform,
    category = RuleCategory.Link,
    actionMode = actionMode,
    matchRegex = matchRegex,
    parameterRegex = extractionRegex,
    triggerRegexes = listOf(matchRegex),
    // v1.44 提取正则用带捕获组版本（trigger 与 extraction 分离），确保能提取到参数 r1
    extractionRegexes = listOf(extractionRegex),
    // v1.57 短链规则对齐官方：重定向后解析参数拼 App scheme（抖音/小红书/快手/B站短链）
    parseAfterRedirect = parseAfterRedirect,
    // v1.58 场景联动：场景激活（v1.33 场景规则集）时新识别规则自动归入当前场景组，
    // 避免场景切换时新规则被禁用（"刚保存的规则不生效"困惑）
    group = io.github.hypercopy.data.settings.SettingsRepository(context.applicationContext).readSceneGroup(),
    target = RuleTarget(
        type = if (template.startsWith("intent://", true)) RuleTargetType.Intent else RuleTargetType.Url,
        template = template,
        packageName = packageName,
    ),
)

@Composable
private fun SuggestionCard(
    suggestion: RuleAnalyzer.Suggestion,
    sourceText: String,
    duplicateOf: RuleConfig?,
    // v1.72 全部规则（统计平台已有规则数，辅助保存决策）
    existingRules: List<RuleConfig>,
    onSave: () -> Unit,
    onEdit: () -> Unit,
) {
    var showDetails by remember { mutableStateOf(false) }
    val preview = previewRecognizedContent(suggestion, sourceText)
    val context = LocalContext.current
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = suggestion.platform, style = MiuixTheme.textStyles.title3)
                if (duplicateOf != null) {
                    Text(
                        text = stringResource(R.string.suggestion_duplicate_ok),
                        style = MiuixTheme.textStyles.body2,
                        color = Color(0xFF00B578),
                    )
                }
                // v1.128 智能识别器同步适配：跳转方式徽标 + App 安装预检（与规则列表/编辑器一致）
                suggestion.jumpMode()?.let { (label, color) ->
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.body2,
                        color = color,
                        modifier = Modifier
                            .background(color.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                if (suggestion.packageName.isNotBlank()) {
                    val installed = runCatching { context.packageManager.getPackageInfo(suggestion.packageName, 0) }.isSuccess
                    val (installLabel, installColor) = if (installed) "已安装" to Color(0xFF00B578) else "未安装" to Color(0xFFFF5A52)
                    Text(
                        text = installLabel,
                        style = MiuixTheme.textStyles.body2,
                        color = installColor,
                        modifier = Modifier
                            .background(installColor.copy(alpha = 0.10f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            // v1.62 人性化：行为说明（不再平铺原始正则）
            Text(
                text = stringResource(R.string.suggestion_behavior, suggestion.platform),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            // v1.72 平台已有规则数（无重复时辅助决策：保存后是新增还是合并）
            if (duplicateOf == null && suggestion.packageName.isNotBlank()) {
                val platformCount = existingRules.count { it.target.packageName == suggestion.packageName }
                if (platformCount > 0) {
                    Text(
                        text = stringResource(R.string.suggestion_platform_count, suggestion.platform, platformCount),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            // v1.62 识别内容预览：让用户确认识别到了什么（口令码/链接/关键词）
            if (preview != null) {
                Text(
                    text = stringResource(R.string.suggestion_preview_label, preview),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        // v1.72 预览可点击复制（分享/保存口令码）
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("HyperCopySuggestion", preview))
                            Toast.makeText(context, R.string.suggestion_preview_copied, Toast.LENGTH_SHORT).show()
                        },
                )
            }
            // v1.72 跳转目标预览：保存前就知道会打开什么（模板渲染）
            val targetPreview = previewJumpTarget(context, suggestion, sourceText)
            if (targetPreview != null) {
                Text(
                    text = stringResource(R.string.suggestion_target_preview, targetPreview),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                )
            }
            // v1.62 详情折叠：正则/包名/模式收进"查看详情"，小白默认不看到技术信息
            TextButton(text = stringResource(if (showDetails) R.string.suggestion_hide_details else R.string.suggestion_view_details), onClick = { showDetails = !showDetails })
            if (showDetails) {
                // v1.72 匹配片段：解释"为什么识别为这个平台"
                val fragment = matchFragment(suggestion, sourceText)
                if (fragment != null) {
                    Text(
                        text = stringResource(R.string.suggestion_match_fragment, fragment),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Text(
                    text = suggestion.packageName,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = stringResource(
                        when (suggestion.actionMode) {
                            io.github.hypercopy.data.rules.RuleActionMode.ParseAndOpen -> R.string.mode_parse_and_open
                            io.github.hypercopy.data.rules.RuleActionMode.DirectOpen -> R.string.mode_direct_open
                            io.github.hypercopy.data.rules.RuleActionMode.WebViewResolveAndOpen -> R.string.mode_webview_resolve
                            io.github.hypercopy.data.rules.RuleActionMode.ClipboardWrite -> R.string.mode_clipboard_write
                            io.github.hypercopy.data.rules.RuleActionMode.NotifyOnly -> R.string.mode_notify_only
                        },
                    ),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = suggestion.matchRegex,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.suggestion_save_only),
                    onClick = onSave,
                    enabled = duplicateOf == null,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.suggestion_edit_save),
                    onClick = onEdit,
                    enabled = duplicateOf == null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** v1.62 识别内容预览：用提取正则匹配原文，优先取捕获组 1（口令码/关键内容），无捕获组取整段匹配 */
private fun previewRecognizedContent(suggestion: RuleAnalyzer.Suggestion, sourceText: String): String? {
    if (sourceText.isBlank()) return null
    return runCatching {
        val m = Regex(suggestion.extractionRegex).find(sourceText) ?: return null
        m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: m.value
    }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= 60 }
}
/** v1.72 跳转目标预览：渲染模板显示保存后会打开什么（DirectOpen 无模板返回 null = 打开 App 自行识别） */
private fun previewJumpTarget(context: Context, suggestion: RuleAnalyzer.Suggestion, sourceText: String): String? {
    if (suggestion.template.isBlank()) return null
    return runCatching {
        val params = mutableMapOf<String, String>()
        suggestion.extractionRegex.takeIf { it.isNotBlank() }?.let { pattern ->
            Regex(pattern).find(sourceText)?.groupValues?.getOrNull(1)?.let {
                params["p1"] = it
                params["r1"] = it
            }
        }
        params["input"] = sourceText
        // v1.130 预览增强：模板占位符参数缺失（如短链 ID 需重定向后提取）时显示说明文案，
        // 避免渲染出残缺 URL（xhsdiscover://item/）让用户误以为规则有问题
        val placeholders = Regex("\\$\\{([^}]+)}").findAll(suggestion.template).map { it.groupValues[1] }.toList()
        val missingParam = placeholders.any { rawKey ->
            val key = rawKey.removePrefix("raw:").removePrefix("lower:").removePrefix("upper:").removePrefix("encode:").removePrefix("url:")
            key != "input" && !params.containsKey(key)
        }
        if (missingParam) {
            return context.getString(R.string.suggestion_target_parse_later, suggestion.platform)
        }
        val rendered = io.github.hypercopy.data.rules.RuleTarget(
            type = io.github.hypercopy.data.rules.RuleTargetType.Url,
            template = suggestion.template,
        ).resolveTemplate(params, encode = { it })
        if (suggestion.parseAfterRedirect) {
            context.getString(R.string.suggestion_parse_after_redirect, rendered)
        } else {
            rendered
        }
    }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= 100 }
}

/** v1.72 匹配片段：原文中命中触发正则的部分（解释"为什么识别为这个平台"） */
private fun matchFragment(suggestion: RuleAnalyzer.Suggestion, sourceText: String): String? {
    if (sourceText.isBlank()) return null
    return runCatching {
        Regex(suggestion.matchRegex).find(sourceText)?.value
    }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= 40 }
}

/** v1.128 智能识别器同步适配：跳转方式徽标（与规则列表 jumpModeBadge 逻辑一致，纯文字防豆腐块） */
private fun RuleAnalyzer.Suggestion.jumpMode(): Pair<String, Color>? {
    if (actionMode == io.github.hypercopy.data.rules.RuleActionMode.ClipboardWrite) return "改写" to Color(0xFF9C6ADE)
    return when {
        template.isBlank() && packageName.isNotBlank() -> "包名" to Color(0xFF6C8EF5)
        template.startsWith("http", ignoreCase = true) -> "网页" to Color(0xFF00B578)
        template.isNotBlank() -> "Scheme" to Color(0xFFF5A623)
        else -> null
    }
}