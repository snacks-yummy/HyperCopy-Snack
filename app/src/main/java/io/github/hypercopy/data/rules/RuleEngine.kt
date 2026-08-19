package io.github.hypercopy.data.rules
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** 编译后的正则缓存：规则匹配是剪贴板热路径，避免每次重复编译（key 含选项，不同选项分别缓存） */
private val regexCache = ConcurrentHashMap<String, Regex>()

internal fun cachedRegex(pattern: String, options: String = ""): Regex =
    regexCache.getOrPut(options + "|" + pattern) { Regex(pattern, options.toRegexOptionSet()) }

/** 正则选项字符串 → RegexOption 集合：i=IGNORE_CASE, s=DOTALL, m=MULTILINE */
internal fun String.toRegexOptionSet(): Set<RegexOption> {
    if (isBlank()) return emptySet()
    val set = mutableSetOf<RegexOption>()
    if (contains('i')) set += RegexOption.IGNORE_CASE
    if (contains('s')) set += RegexOption.DOT_MATCHES_ALL
    if (contains('m')) set += RegexOption.MULTILINE
    return set
}

data class RuleMatch(val rule: RuleConfig, val parameters: Map<String, String>, val intent: Intent)

// v1.139.4 长文本保护：超过该长度的输入不再触发通用 URL 通配规则（浏览器/Chrome 等），
// 防止复制日志/文章/聊天记录等长文本时被其中的 URL 误劫持跳转
private const val MAX_WILDCARD_TEXT_LENGTH = 200

/**
 * 是否为通用 URL 通配规则（matchRegex/trigger 为 https?://[^\s]+ 之类任意 URL 匹配）。
 * 平台特定规则（抖音/微信视频号/B站等含特定域名的）不受长文本保护影响。
 */
fun RuleConfig.isUrlWildcard(): Boolean {
    val m = matchRegex.ifBlank { triggerRegexes.joinToString("|") }
    if (m.isBlank()) return false
    // v1.139.7 兼容 \/ 转义写法：https?:\/\/[^\s]+（JSON 转义斜杠）与 https?://[^\s]+ 均识别
    val normalized = m.replace("\\/", "/")
    return normalized.contains("https?://") && (normalized.contains("[^\\s]+") || normalized.contains("\\S+"))
}

fun matchRule(text: String, rules: List<RuleConfig>, sourcePackage: String? = null, activeOnly: Boolean = false): RuleMatch? {
    return rules.firstNotNullOfOrNull { rule ->
        if (!rule.enabled) return@firstNotNullOfOrNull null
        if (activeOnly && !rule.isEffective(sourcePackage)) return@firstNotNullOfOrNull null
        if (rule.actionMode != RuleActionMode.ParseAndOpen) return@firstNotNullOfOrNull null
        // v1.139.4 长文本保护：超长输入不触发 URL 通配规则（防复制日志/文章误跳）
        if (text.length > MAX_WILDCARD_TEXT_LENGTH && rule.isUrlWildcard()) return@firstNotNullOfOrNull null
        if (!rule.matchesInput(text)) return@firstNotNullOfOrNull null
        val parameters = rule.extractParameters(text).toMutableMap()
        if (rule.extractionPatterns().isNotEmpty() && parameters.none { it.key.startsWith("r") }) {
            // v1.44 提取失败兜底：正则命中但无捕获组/无匹配时，用整段文本作为 r1，
            // 避免规则"命中但不跳转"的假死（此前直接返回 null 静默失败）
            parameters["r1"] = text
        }
        RuleMatch(rule, parameters, rule.target.toIntent(parameters + ("input" to text)))
    }
}
fun findRule(text: String, rules: List<RuleConfig>, sourcePackage: String? = null, activeOnly: Boolean = false): RuleConfig? {
    return rules.firstOrNull { rule ->
        rule.enabled && (!activeOnly || rule.isEffective(sourcePackage)) &&
            // v1.139.4 长文本保护（同上）
            !(text.length > MAX_WILDCARD_TEXT_LENGTH && rule.isUrlWildcard()) &&
            rule.matchesInput(text)
    }
}

fun RuleConfig.directIntent(text: String, packageManager: PackageManager? = null): Intent {
    if (actionMode == RuleActionMode.DirectOpen && target.template.isBlank() && target.packageName.isNotBlank()) {
        return (packageManager?.getLaunchIntentForPackage(target.packageName) ?: Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(target.packageName)
        }).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    val parameters = extractParameters(text) + ("input" to text)
    val template = target.template.ifBlank { "${'$'}{input}" }
    return target.copy(template = template).toIntent(parameters)
}

fun RuleConfig.parseIntent(text: String, requireMatch: Boolean = true, extraParameters: Map<String, String> = emptyMap()): Intent? {
    if (requireMatch && !matchesInput(text)) return null
    val parameters = extractParameters(text).toMutableMap()
    if (extractionPatterns().isNotEmpty() && parameters.none { it.key.startsWith("r") }) {
        // v1.44 提取失败兜底：用整段文本作为 r1（同 matchRule），避免命中不跳转
        parameters["r1"] = text
    }
    return target.toIntent(parameters + extraParameters + ("input" to text))
}

fun RuleConfig.matchesInput(text: String): Boolean {
    // 排除规则（负规则）：命中则跳过
    if (excludeRegex.isNotBlank()) {
        if (runCatching { cachedRegex(excludeRegex, regexOptions).containsMatchIn(text) }.getOrDefault(false)) return false
    }
    val patterns = triggerPatterns()
    // 空正则 = 配置无效，安全降级为"不匹配"（绝不"匹配所有"拦截一切）
    if (patterns.isEmpty()) return false
    // v1.79 触发条件模式：matchAllTriggers=true 时所有触发正则都需匹配（AND），默认任一命中（OR）
    return if (matchAllTriggers) {
        patterns.all { pattern -> runCatching { cachedRegex(pattern, regexOptions).containsMatchIn(text) }.getOrDefault(false) }
    } else {
        patterns.any { pattern -> runCatching { cachedRegex(pattern, regexOptions).containsMatchIn(text) }.getOrDefault(false) }
    }
}
/**
 * 生效条件判断（v1.24）：
 * - 来源 App 白名单：sourcePackages 非空时，来源包名必须匹配其一（null/空来源不匹配白名单规则）
 * - 时间段：activeTimeStart/End 任一非空即启用时间段限制（HH:mm，支持跨午夜如 22:00-06:00）
 * 均未配置 → 任何时刻/来源都生效
 */
fun RuleConfig.isEffective(sourcePackage: String? = null, now: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
    val allowed = sourcePackages.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (allowed.isNotEmpty()) {
        if (sourcePackage.isNullOrBlank() || allowed.none { it.equals(sourcePackage, ignoreCase = true) }) return false
    }
    val start = activeTimeStart.trim()
    val end = activeTimeEnd.trim()
    if (start.isEmpty() && end.isEmpty()) return true
    fun parseMin(s: String): Int? = runCatching {
        val parts = s.split(":")
        parts[0].trim().toInt() * 60 + parts.getOrNull(1)?.trim()?.toIntOrNull().orZero()
    }.getOrNull()
    val startMin = parseMin(start)
    val endMin = parseMin(end)
    if (startMin == null || endMin == null) return true // 格式错误视为不限
    val nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
    return if (startMin == endMin) true // 起止相同视为全天
    else if (startMin < endMin) nowMin in startMin until endMin
    else nowMin >= startMin || nowMin < endMin // 跨午夜
}
private fun Int?.orZero(): Int = this ?: 0
fun RuleConfig.extractParameters(text: String): Map<String, String> {
    val values = mutableMapOf<String, String>()
    var legacyIndex = 1
    extractionPatterns().forEachIndexed { patternIndex, pattern ->
        val matches = runCatching { cachedRegex(pattern, regexOptions).findAll(text) }.getOrNull() ?: return@forEachIndexed
        matches.forEachIndexed { matchIndex, match ->
            // v1.141.35 修复 r{n} 命名：多分支正则(验证码|OTP、取件码|凭码取件)命中非首分支时，
            // 首捕获组(group1)为 null，真正的码在 group2 等后续组。
            // 原实现 r{n} 只给 groupIndex==0 → group2 场景 r{n} 缺失 → template=${r1} 解析空。
            // 改为：r{n} 赋给第一个非空捕获组，确保命中分支的码值一定能作为 r1/{n} 解析到。
            var firstNonNullAssigned = false
            match.groups.drop(1).forEachIndexed { groupIndex, group ->
                val value = group?.value ?: return@forEachIndexed
                // r{n} 与 r{n}_1 命名：第一个非空捕获组（对标"主要提取值"）
                if (!firstNonNullAssigned && matchIndex == 0) {
                    firstNonNullAssigned = true
                    values["r${patternIndex + 1}"] = value
                    values["r${patternIndex + 1}_1"] = value
                }
                // 多 match 多组：r{n}_{k}_{m}（k=match序号, m=组序号）
                values["r${patternIndex + 1}_${matchIndex + 1}_${groupIndex + 1}"] = value
                if (groupIndex == 0) values["p${legacyIndex++}"] = value
            }
        }
    }
    return values
}

fun RuleConfig.triggerPatterns(): List<String> = triggerRegexes.ifEmpty { listOf(matchRegex) }.filter { it.isNotBlank() }

fun RuleConfig.extractionPatterns(): List<String> = extractionRegexes.ifEmpty { listOf(parameterRegex) }.filter { it.isNotBlank() }

fun RuleTarget.toIntent(parameters: Map<String, String>): Intent {
    val resolved = resolveTemplate(parameters)
    return when (type) {
        RuleTargetType.Intent -> runCatching { Intent.parseUri(resolved, Intent.URI_INTENT_SCHEME) }
            .getOrElse { Intent(action, Uri.parse(resolved)) }
        RuleTargetType.Url -> Intent(action, Uri.parse(resolved))
    }.apply {
        if (packageName.isNotBlank()) setPackage(packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

internal fun RuleTarget.resolveTemplate(
    parameters: Map<String, String>,
    encode: (String) -> String = Uri::encode,
): String {
    // ① 时间函数 ${time:yyyy-MM-dd HH:mm}
    val timeResolved = TIME_PLACEHOLDER_REGEX.replace(template) { match ->
        runCatching { SimpleDateFormat(match.groupValues[1], Locale.getDefault()).format(Date()) }.getOrDefault("")
    }
    // ② 包名占位符 ${pkg}
    val pkgResolved = timeResolved.replace(PKG_PLACEHOLDER_REGEX, packageName)
    // ③ 变换函数 ${lower:key} ${upper:key} ${encode:key}（先于普通替换执行）
    val funcResolved = FUNC_PLACEHOLDER_REGEX.replace(pkgResolved) { match ->
        val func = match.groupValues[1]
        val key = match.groupValues[2]
        val value = parameters[key].orEmpty()
        when (func) {
            "encode" -> encode(value)
            "lower" -> value.lowercase()
            "upper" -> value.uppercase()
            else -> value
        }
    }
    // ④ url:key 提取第一个 URL
    val urlResolved = URL_PLACEHOLDER_REGEX.replace(funcResolved) { match ->
        val key = match.groupValues[1]
        parameters[key]?.let { extractFirstInputUrl(it) ?: it }.orEmpty()
    }
    // ⑤ 普通参数替换（含 raw: 原样、默认编码）
    val resolved = parameters.entries.fold(urlResolved) { value, entry ->
        val replacement = if (entry.key == "input" || entry.key == "redirectUrl") entry.value else encode(entry.value)
        value
            .replace("${'$'}{raw:${entry.key}}", entry.value)
            .replace("${'$'}{${entry.key}}", replacement)
    }
    return TEMPLATE_PLACEHOLDER_REGEX.replace(resolved, "")
}

private val URL_PLACEHOLDER_REGEX = Regex("""\$\{url:([^}]+)\}""")
private val TEMPLATE_PLACEHOLDER_REGEX = Regex("""\$\{(?:raw:|lower:|upper:|encode:|url:|time:)[^}]+\}|\$\{[^}]+\}""")
private val TIME_PLACEHOLDER_REGEX = Regex("""\$\{time:([^}]+)\}""")
private val PKG_PLACEHOLDER_REGEX = Regex("""\$\{pkg\}""")
private val FUNC_PLACEHOLDER_REGEX = Regex("""\$\{(lower|upper|encode):([^}]+)\}""")
