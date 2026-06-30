package io.github.hypercopy.data.rules

fun RuleConfig.resolveInputUrl(text: String): String {
    return extractMatchingWebUrl(text)?.let(::normalizeInputUrl) ?: normalizeInputUrl(text)
}

fun extractFirstInputUrl(text: String): String? {
    return WEB_URL_REGEX.findAll(text)
        .flatMap { webUrlCandidates(it.value) }
        .firstOrNull()
        ?.let(::normalizeInputUrl)
}

private fun RuleConfig.extractMatchingWebUrl(text: String): String? {
    return WEB_URL_REGEX.findAll(text)
        .flatMap { webUrlCandidates(it.value) }
        .firstOrNull { candidate -> matchesInput(candidate) || matchesInput(normalizeInputUrl(candidate)) }
}

private fun webUrlCandidates(raw: String): Sequence<String> = sequence {
    val trimmed = raw.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '\uFF0C', '\u3002', '\uFF1B', '\uFF1A', '\uFF01', '\uFF1F')
    val colonSegmentIndex = trimmed.indexOf("/:")
    if (colonSegmentIndex >= 0) yield(trimmed.substring(0, colonSegmentIndex + 1))
    yield(trimmed)
}

fun normalizeInputUrl(text: String): String {
    val value = text.trim()
    val uri = runCatching { android.net.Uri.parse(value) }.getOrNull()
    return if (uri?.scheme.isNullOrBlank()) "https://$value" else value
}

/**
 * 链接提取正则：除 http(s) 外，补充支持 BT 下载类非标协议 ——
 *  - magnet:          磁力链接（?xt=urn:btih:...）
 *  - ed2k://          电驴
 *  - thunder://       迅雷
 * 普通 https?:// 与裸域名仍走原逻辑。
 */
private val WEB_URL_REGEX = Regex(
    """(?:https?://)?[A-Za-z0-9.-]+\.[A-Za-z]{2,}(?:/[^\s]*)?|magnet:\?xt=[^\s]+|ed2k://[^\s]+|thunder://[^\s]+""",
    RegexOption.IGNORE_CASE
)
