package io.github.hypercopy.ui.components

import androidx.annotation.StringRes
import io.github.hypercopy.R
import io.github.hypercopy.data.rules.RuleActionMode
import io.github.hypercopy.data.rules.RuleCategory

internal enum class RulePageCategory {
    System,
    Link,
    Text,
}

/** v1.139.1 规则来源（区分作者云端/内置/自定义）：builtin_=内置, cloud_=作者云端, 其他=自定义 */
internal enum class RuleSource {
    All,
    Builtin,
    Cloud,
    Custom,
}

@StringRes
internal fun ruleSourceTitle(source: RuleSource): Int = when (source) {
    RuleSource.All -> R.string.rule_source_all
    RuleSource.Builtin -> R.string.rule_source_builtin
    RuleSource.Cloud -> R.string.rule_source_cloud
    RuleSource.Custom -> R.string.rule_source_custom
}

internal val ruleSourceTitles: List<Int> = listOf(
    R.string.rule_source_all,
    R.string.rule_source_builtin,
    R.string.rule_source_cloud,
    R.string.rule_source_custom,
)

/**
 * v1.139.1b 我（用户）开发的内置规则 id（来源=内置）：其余 builtin_ 均为作者原版规则（来源=云端·作者）
 */
internal val MY_BUILTIN_RULE_IDS: Set<String> = setOf(
    // text: v1.138 用户需求新增（仅通知模式）
    "builtin_cloud_text_取件码通知",
    "builtin_cloud_text_短信验证码提取",
    // text: 用户需求深度修改（v1.85快递直达/v1.101单号校验/v1.125重构19家/v1.136圆通YT修复）
    "builtin_cloud_text_快递单号菜鸟查件_com.cainiao.wireless",
    // text: v1.141.87 系列深度二改（外卖方案C/地址去城市名/柜位精简/岛内content分离）
    "builtin_cloud_text_外卖取件通知",
    // link: v1.132 用户需求新增：微信视频号链接 → 便捷下载 App
    "builtin_cloud_link_微信视频号下载_com.lcw.easydownload",
    // link: 二改项目自研/二改版（v1.141.87 确认归类）：美团小程序/淘宝口令/淘宝链接/闲鱼链接
    "builtin_cloud_link_takeout_jump",
    "builtin_cloud_link_淘宝口令",
    "builtin_cloud_link_淘宝链接",
    "builtin_cloud_link_闲鱼链接",
)

/**
 * 规则 id → 来源：
 * - 我的内置（MY_BUILTIN_RULE_IDS）→ 内置
 * - 作者原版内置（其余 builtin_）+ 云端下载（cloud_）→ 云端
 * - UUID → 自定义
 */
internal fun ruleSourceOf(id: String, modifiedBuiltinIds: Set<String> = emptySet()): RuleSource = when {
    id in MY_BUILTIN_RULE_IDS || id in modifiedBuiltinIds -> RuleSource.Builtin
    id.startsWith("builtin_") -> RuleSource.Cloud
    id.startsWith("cloud_") -> RuleSource.Cloud
    else -> RuleSource.Custom
}

/** 来源筛选匹配 */
internal fun RuleSource.matchesRule(id: String, modifiedBuiltinIds: Set<String> = emptySet()): Boolean = when (this) {
    RuleSource.All -> true
    else -> ruleSourceOf(id, modifiedBuiltinIds) == this
}

@StringRes
internal fun rulePageTabTitle(category: RulePageCategory): Int = when (category) {
    RulePageCategory.System -> R.string.category_system
    RulePageCategory.Link -> R.string.category_link
    RulePageCategory.Text -> R.string.category_text
}

internal val localRuleCategoryTabTitles: List<Int> = listOf(
    R.string.category_system,
    R.string.category_link,
    R.string.category_text,
)

internal val cloudRuleCategoryTabTitles: List<Int> = listOf(
    R.string.category_link,
    R.string.category_text,
)

internal fun RulePageCategory.tabIndex(): Int = when (this) {
    RulePageCategory.System -> 0
    RulePageCategory.Link -> 1
    RulePageCategory.Text -> 2
}

internal fun localRulePageCategoryFromTab(index: Int): RulePageCategory = when (index) {
    1 -> RulePageCategory.Link
    2 -> RulePageCategory.Text
    else -> RulePageCategory.System
}

internal fun cloudRulePageCategoryFromTab(index: Int): RulePageCategory = when (index) {
    1 -> RulePageCategory.Text
    else -> RulePageCategory.Link
}

internal fun RulePageCategory.cloudTabIndex(): Int = when (this) {
    RulePageCategory.Text -> 1
    else -> 0
}

internal fun RulePageCategory.ruleCategories(): Set<RuleCategory> = when (this) {
    RulePageCategory.System -> emptySet()
    RulePageCategory.Link -> setOf(RuleCategory.Link)
    RulePageCategory.Text -> setOf(RuleCategory.Text, RuleCategory.Address, RuleCategory.Express)
}

@StringRes
internal fun RulePageCategory.titleRes(): Int = when (this) {
    RulePageCategory.System -> R.string.category_system
    RulePageCategory.Link -> R.string.category_link
    RulePageCategory.Text -> R.string.category_text
}

internal fun RulePageCategory.folderName(): String = when (this) {
    RulePageCategory.System -> "system"
    RulePageCategory.Link -> "link"
    RulePageCategory.Text -> "text"
}

@StringRes
internal fun RulePageCategory.testHintRes(): Int = when (this) {
    RulePageCategory.System -> R.string.rule_test_link_hint
    RulePageCategory.Link -> R.string.rule_test_link_hint
    RulePageCategory.Text -> R.string.rule_test_text_hint
}

@StringRes
internal fun RulePageCategory.emptyDescriptionRes(): Int = when (this) {
    RulePageCategory.System -> R.string.rule_system_empty_description
    RulePageCategory.Link -> R.string.rule_empty_link_description
    RulePageCategory.Text -> R.string.rule_empty_text_description
}

@StringRes
internal fun RuleCategory.titleRes(): Int = when (this) {
    RuleCategory.Link -> R.string.category_link
    RuleCategory.Text -> R.string.category_text
    RuleCategory.Address -> R.string.category_address
    RuleCategory.Express -> R.string.category_express
}

@StringRes
internal fun RuleActionMode.labelRes(): Int = when (this) {
    RuleActionMode.ParseAndOpen -> R.string.rule_action_parse_and_open
    RuleActionMode.DirectOpen -> R.string.rule_action_direct_open_app
    RuleActionMode.WebViewResolveAndOpen -> R.string.rule_action_webview_open
    RuleActionMode.ClipboardWrite -> R.string.rule_action_clipboard_write
    RuleActionMode.NotifyOnly -> R.string.rule_action_notify_only
}

@StringRes
internal fun ruleActionLabelRes(rule: io.github.hypercopy.data.rules.RuleConfig): Int {
    if (rule.category != RuleCategory.Link) {
        return if (rule.target.template.isBlank()) R.string.rule_action_direct_open_app else R.string.rule_action_open_url
    }
    return rule.actionMode.labelRes()
}
