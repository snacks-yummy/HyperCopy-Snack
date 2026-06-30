package io.github.hypercopy.data.rules

/** 正则工具：语法校验 + 危险全匹配检测 */
object RulePatterns {

    /** 正则语法是否合法 */
    fun isValid(pattern: String): Boolean =
        runCatching { Regex(pattern) }.isSuccess

    /** 返回第一个非法正则（空串跳过）；全部合法返回 null */
    fun firstInvalid(patterns: List<String>): String? =
        patterns.firstOrNull { it.isNotBlank() && !isValid(it) }

    /** 危险的全匹配正则（会拦截所有内容的通配） */
    fun isDangerousMatchAll(pattern: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty()) return true
        return p == ".*" || p == "^.*$" || p == "(?s).*" || p == "(?s)^.*$" ||
            p == ".+" || p == "^.+$" || p == "(?s).+" || p == "(?s)^.+$"
    }

    /** 命中测试：任一 trigger 正则匹配即命中（与 RuleConfig.matchesInput 的 any 语义一致） */
    fun matchesAny(patterns: List<String>, text: String): Boolean {
        val effective = patterns.filter { it.isNotBlank() }.ifEmpty { listOf(".*") }
        return effective.any { pattern ->
            runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(false)
        }
    }
}
