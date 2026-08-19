package io.github.hypercopy.data.rules




import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class RuleConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: RuleCategory = RuleCategory.Link,
    val enabled: Boolean = true,
    val actionMode: RuleActionMode = RuleActionMode.ParseAndOpen,
    val matchRegex: String,
    val parameterRegex: String,
    val triggerRegexes: List<String> = emptyList(),
    val extractionRegexes: List<String> = emptyList(),
    val parseAfterRedirect: Boolean = false,
    val target: RuleTarget,
    val clearClipboardAfterJump: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    // v1.21 新增字段
    val priority: Int = 0,               // 规则优先级：越大越优先（默认0，同级按数组顺序）
    val group: String = "",              // 分组/标签
    val excludeRegex: String = "",       // 排除规则：命中则跳过（负规则）
    val regexOptions: String = "",       // 正则选项：i=忽略大小写, s=DOTALL, m=MULTILINE（可组合如"is"）
    val notificationMode: String? = null, // 规则级通知模式：覆盖全局（null=跟随全局）
    // v1.24 新增字段：生效条件
    val sourcePackages: String = "",     // 来源 App 包名白名单（逗号分隔；空=不限来源）
    val activeTimeStart: String = "",    // 生效时间段起（HH:mm；空=不限）
    val activeTimeEnd: String = "",      // 生效时间段止（HH:mm；空=不限）
    // v1.79 新增字段
    val matchAllTriggers: Boolean = false, // 触发条件 AND：所有触发正则都需匹配（默认 OR 任一命中）
    val delayMillis: Int = 0,              // 延迟跳转毫秒数（0=立即；建议 ≤3000，通知模式 5s 过期上限内）
)

enum class RuleCategory(val value: String) {
    Link("link"),
    Text("text"),
    Address("address"),
    Express("express"),
}

enum class RuleActionMode(val value: String) {
    ParseAndOpen("parse_and_open"),
    DirectOpen("direct_open"),
    WebViewResolveAndOpen("webview_resolve_and_open"),
    // v1.79 剪贴板改写回写：命中后把模板渲染结果写回剪贴板（不跳转）
    ClipboardWrite("clipboard_write"),
    // v1.138 仅通知：命中后发通知栏通知（不跳转、不改剪贴板），取件码/取货码场景
    NotifyOnly("notify_only"),
}

data class RuleTarget(
    val type: RuleTargetType,
    val template: String,
    val packageName: String = "",
    val action: String = Intent.ACTION_VIEW,
)

/** 规则内容比较：排除 id / name / enabled / createdAt / priority / group / notificationMode 等管理字段，仅比较功能内容（防止改名后重复添加绕过去重） */
fun RuleConfig.sameContentAs(other: RuleConfig): Boolean =
    category == other.category &&
        actionMode == other.actionMode &&
        matchRegex == other.matchRegex &&
        parameterRegex == other.parameterRegex &&
        triggerRegexes == other.triggerRegexes &&
        extractionRegexes == other.extractionRegexes &&
        parseAfterRedirect == other.parseAfterRedirect &&
        target == other.target &&
        clearClipboardAfterJump == other.clearClipboardAfterJump &&
        excludeRegex == other.excludeRegex &&
        regexOptions == other.regexOptions &&
        matchAllTriggers == other.matchAllTriggers &&
        delayMillis == other.delayMillis &&
        sourcePackages == other.sourcePackages &&
        activeTimeStart == other.activeTimeStart &&
        activeTimeEnd == other.activeTimeEnd

/** 内容签名：与 sameContentAs 一致的功能内容哈希，用于快速分组去重 */
fun RuleConfig.contentSignature(): String = listOf(
    category.value, actionMode.value, matchRegex, parameterRegex,
    triggerRegexes.joinToString("||"), extractionRegexes.joinToString("||"),
    parseAfterRedirect.toString(), target.type.value, target.template, target.packageName, target.action,
    clearClipboardAfterJump.toString(), excludeRegex, regexOptions,
    matchAllTriggers.toString(), delayMillis.toString(),
    sourcePackages, activeTimeStart, activeTimeEnd,
).joinToString("|")

enum class RuleTargetType(val value: String) {
    Url("url"),
    Intent("intent"),
}

fun RuleConfig.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("category", category.value)
    .put("enabled", enabled)
    .put("actionMode", actionMode.value)
    .put("matchRegex", matchRegex)
    .put("parameterRegex", parameterRegex)
    .put("triggerRegexes", triggerRegexes.toJsonArray())
    .put("extractionRegexes", extractionRegexes.toJsonArray())
    .put("parseAfterRedirect", parseAfterRedirect)
    .also { json -> if (clearClipboardAfterJump) json.put("clearClipboardAfterJump", true) }
    .put("target", target.toJson())
    .put("createdAt", createdAt)
    .also { json ->
        if (priority != 0) json.put("priority", priority)
        if (group.isNotBlank()) json.put("group", group)
        if (excludeRegex.isNotBlank()) json.put("excludeRegex", excludeRegex)
        if (regexOptions.isNotBlank()) json.put("regexOptions", regexOptions)
        if (notificationMode != null) json.put("notificationMode", notificationMode)
        if (sourcePackages.isNotBlank()) json.put("sourcePackages", sourcePackages)
        if (activeTimeStart.isNotBlank()) json.put("activeTimeStart", activeTimeStart)
        if (activeTimeEnd.isNotBlank()) json.put("activeTimeEnd", activeTimeEnd)
        if (matchAllTriggers) json.put("matchAllTriggers", true)
        if (delayMillis > 0) json.put("delayMillis", delayMillis)
    }

private fun List<String>.toJsonArray(): JSONArray = JSONArray().also { array ->
    filter { it.isNotBlank() }.forEach { array.put(it) }
}

fun RuleTarget.toJson(): JSONObject = JSONObject()
    .put("type", type.value)
    .put("template", template.normalizeTemplateSlash())
    .put("packageName", packageName)
    .put("action", action)

fun ruleConfigFromJson(json: JSONObject): RuleConfig = RuleConfig(
    id = json.optString("id", UUID.randomUUID().toString()),
    name = json.optString("name", ""),
    category = ruleCategoryFromValue(json.optString("category")),
    enabled = json.optBoolean("enabled", true),
    actionMode = ruleActionModeFromValue(json.optString("actionMode")),
    matchRegex = json.optString("matchRegex"),
    parameterRegex = json.optString("parameterRegex"),
    triggerRegexes = json.optStringArray("triggerRegexes"),
    extractionRegexes = json.optStringArray("extractionRegexes"),
    parseAfterRedirect = json.optBoolean("parseAfterRedirect", false),
    target = ruleTargetFromJson(json.optJSONObject("target") ?: JSONObject()),
    clearClipboardAfterJump = json.optBoolean("clearClipboardAfterJump", false),
    createdAt = json.optLong("createdAt", System.currentTimeMillis()),
    priority = json.optInt("priority", 0),
    group = json.optString("group"),
    excludeRegex = json.optString("excludeRegex"),
    regexOptions = json.optString("regexOptions"),
    notificationMode = json.optString("notificationMode").ifBlank { null },
    sourcePackages = json.optString("sourcePackages"),
    activeTimeStart = json.optString("activeTimeStart"),
    activeTimeEnd = json.optString("activeTimeEnd"),
    matchAllTriggers = json.optBoolean("matchAllTriggers", false),
    delayMillis = json.optInt("delayMillis", 0),
)

private fun JSONObject.optStringArray(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index)
            if (value.isNotBlank()) add(value)
        }
    }
}

fun ruleCategoryFromValue(value: String): RuleCategory = when (value) {
    RuleCategory.Text.value -> RuleCategory.Text
    RuleCategory.Address.value -> RuleCategory.Address
    RuleCategory.Express.value -> RuleCategory.Express
    else -> RuleCategory.Link
}

fun ruleActionModeFromValue(value: String): RuleActionMode = when (value) {
    RuleActionMode.DirectOpen.value -> RuleActionMode.DirectOpen
    RuleActionMode.WebViewResolveAndOpen.value -> RuleActionMode.WebViewResolveAndOpen
    RuleActionMode.ClipboardWrite.value -> RuleActionMode.ClipboardWrite
    RuleActionMode.NotifyOnly.value -> RuleActionMode.NotifyOnly
    else -> RuleActionMode.ParseAndOpen
}

fun ruleTargetFromJson(json: JSONObject): RuleTarget {
    val type = when (json.optString("type")) {
        RuleTargetType.Intent.value -> RuleTargetType.Intent
        else -> RuleTargetType.Url
    }
    return RuleTarget(
        type = type,
        template = json.optString("template").normalizeTemplateSlash(),
        packageName = json.optString("packageName"),
        action = json.optString("action", Intent.ACTION_VIEW),
    )
}

fun rulesToJson(rules: List<RuleConfig>): String {
    val root = JSONObject()
    val items = JSONArray()
    rules.forEach { items.put(it.toJson()) }
    return root.put("version", 1).put("schemaVersion", SCHEMA_VERSION).put("rules", items).toString(2)
}

/** 当前 schema 版本：未来字段变更时递增并补充迁移逻辑 */
const val SCHEMA_VERSION = 3

/** 带版本迁移的读取：v1 旧数据无新字段，ruleConfigFromJson 用默认值兼容，无需额外迁移 */
fun rulesFromJson(text: String): List<RuleConfig> {
    if (text.isBlank()) return emptyList()
    val trimmed = text.trim()
    if (trimmed.startsWith("[")) return rulesFromJsonArray(JSONArray(trimmed))

    val root = JSONObject(trimmed)
    val items = root.optJSONArray("rules") ?: return listOf(ruleConfigFromJson(root))
    return rulesFromJsonArray(items)
}

private fun rulesFromJsonArray(items: JSONArray): List<RuleConfig> {
    return buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            add(ruleConfigFromJson(item))
        }
    }
}

/**
 * v1.141.66 淘宝链接规则强制跳转前清剪贴板（动态判断，免疫规则字段被保存覆盖）：
 * 链接跳转后剪贴板残留口令 → 淘宝检测口令偶发弹「查看详情」。
 * 规则字段 clearClipboardAfterJump 可能被编辑器保存重置为 false（一次性迁移不可靠），
 * 此处对淘宝·链接规则（template=${url:input} + 淘宝系域名）强制生效。
 * 口令规则（template 空）不强制——淘宝需读剪贴板口令弹窗，由 TaobaoKoulingConfirm 自动确认。
 */
val RuleConfig.clearClipboardEffective: Boolean
    get() = clearClipboardAfterJump || isTaobaoLinkRule()

fun RuleConfig.isTaobaoLinkRule(): Boolean =
    target.template.orEmpty().contains("\${url:input}") &&
        (matchRegex.contains("taobao.com") || matchRegex.contains("tb.cn") ||
            matchRegex.contains("tmall.com") || matchRegex.contains("e.tb.cn"))

/**
 * v1.141.70 URI template 转义残留归一化：
 * 识别器/外部工具导出的 JSON 可能把 `/` 序列化为 `\\/`（JSON 双重转义残留），
 * 解码后 template 为 `fleamarket:\\/\\/...`（反斜杠+斜杠），作为 URI 模板跳转存在风险。
 * 正则字段（matchRegex 等）不在此清洗——Java 正则中 `\\/` 等价 `/`，且可能属用户有意写法。
 * 幂等：已干净（无 `\\/`）时原样返回。
 */
fun String.normalizeTemplateSlash(): String = replace("\\/", "/")
