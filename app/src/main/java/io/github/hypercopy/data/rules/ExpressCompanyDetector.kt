package io.github.hypercopy.data.rules

/**
 * v1.84 快递公司识别器：根据单号前缀/数字段识别所属快递公司。
 * 识别规则与内置「快递单号-菜鸟查件」触发正则保持一致，保证显示与跳转一致。
 */
object ExpressCompanyDetector {

    /** 字母前缀 → 公司名（按最长前缀优先匹配） */
    private val PREFIX_RULES: List<Pair<String, String>> = listOf(
        "ANEKY" to "安能物流",
        "BTWYL" to "百世快运",
        "ZTOKY" to "中通快运",
        "YDKY" to "韵达快运",
        "JTSD" to "极兔速递",
        "CNSD" to "菜鸟速递",
        "SXJD" to "顺心捷达",
        "YMDD" to "壹米滴答",
        "KYSY" to "跨越速运",
        "XFEX" to "信丰物流",
        "ZJS" to "宅急送",
        "HTKY" to "百世快递",
        "DPK" to "德邦快递",
        "DPL" to "德邦快递",
        "DBL" to "德邦快递",
        "JDB" to "京东物流",
        "JDC" to "京东物流",
        "JDD" to "京东物流",
        "JDG" to "京东物流",
        "YTO" to "圆通速递",
        "ZTO" to "中通快递",
        "STO" to "申通快递",
        "JTO" to "极兔速递",
        "FW" to "丰网速运",
        "ANE" to "安能物流",
        "US" to "优速快递",
        "TT" to "天天快递",
        "SF" to "顺丰速运",
        "YT" to "圆通速递",
        "ZT" to "中通快递",
        "YD" to "韵达快递",
        "JT" to "极兔速递",
        "JD" to "京东物流",
        "DB" to "德邦快递",
        "PA" to "中国邮政",
        "PB" to "中国邮政",
    )

    /** 纯数字段 → 公司名（与内置触发正则的数字段对应） */
    private val DIGIT_RULES: List<Pair<Regex, String>> = listOf(
        Regex("^777\\d{9,}$") to "京东物流",
        Regex("^77203\\d{7,}$") to "顺丰速运",
        Regex("^7353\\d{7,}$") to "顺丰速运",
        Regex("^43415\\d{7,}$") to "顺丰速运",
        Regex("^31250\\d{7,}$") to "顺丰速运",
        Regex("^50\\d{10,}$") to "中通快递",
        Regex("^7\\d{11,12}$") to "中通快递",
        Regex("^43\\d{12}$") to "圆通速递",
        Regex("^46\\d{12}$") to "圆通速递",
        Regex("^79\\d{12}$") to "韵达快递",
        Regex("^58\\d{9,}$") to "申通快递",
        Regex("^268\\d{8,}$") to "中国邮政",
        Regex("^368\\d{8,}$") to "中国邮政",
    )

    /** EMS 国际格式：2 字母 + 9 数字 + 2 字母 */
    private val EMS_REGEX = Regex("^[A-Z]{2}\\d{9}[A-Z]{2}$")
    /** v1.101 各公司标准数字位数（业界常见格式；未列出的公司宽松 9-20 位）——防止短号/无效号跳转吃菜鸟校验提示 */
    private val STANDARD_DIGIT_LENGTHS: Map<String, IntRange> = mapOf(
        "圆通速递" to 13..13,
        "顺丰速运" to 12..15,
        "极兔速递" to 12..13,
        "中通快递" to 12..13,
        "申通快递" to 12..15,
        "韵达快递" to 13..13,
        "京东物流" to 13..15,
        "德邦快递" to 10..12,
        "中国邮政" to 12..12,
        "百世快递" to 12..12,
        "EMS" to 9..11,
    )

    /**
     * 识别单号所属快递公司名（未识别返回 null）。
     * @param text 剪贴板原文（会自动提取首个疑似单号）
     */
    fun detect(text: String): String? {
        val cleaned = extractTrackingNumber(text)?.uppercase() ?: return null
        // EMS 国际格式优先
        if (EMS_REGEX.matches(cleaned)) return "EMS"
        // 字母前缀
        val prefixMatch = Regex("^([A-Z]{2,6})(\\d{9,20})$").find(cleaned)
        if (prefixMatch != null) {
            val prefix = prefixMatch.groupValues[1]
            val digits = prefixMatch.groupValues[2]
            val company = PREFIX_RULES.sortedByDescending { it.first.length }
                .firstOrNull { prefix.startsWith(it.first) }?.second ?: return null
            // v1.101 长度校验：位数不符合公司标准格式（如圆通必须 13 位）→ 不识别，
            // 避免无效短号跳转菜鸟后触发「请检查运单号输入是否正确」
            val range = STANDARD_DIGIT_LENGTHS[company] ?: (9..20)
            if (digits.length !in range) return null
            return company
        }
        // 纯数字段
        if (cleaned.all { it.isDigit() }) {
            for ((regex, name) in DIGIT_RULES) {
                if (regex.matches(cleaned)) return name
            }
        }
        return null
    }

    /** 从文本提取首个疑似单号（字母前缀 2-6 位 + 数字 9-20 位，或纯数字 9-22 位）
     *  v1.141.50 修复：\b → ASCII 数字字母边界 lookaround。
     *  真机 ART 的 \w 含中文（Unicode 语义），\b 在中文与字母/数字间无边界 →
     *  整段短信【京东物流】关于运单JD0228717729868... 提取失败（desktop JDK 正常，真机 19:42 实锤）。 */
    fun extractTrackingNumber(text: String): String? =
        Regex("(?<![A-Za-z0-9])(?:[A-Za-z]{2,6}\\d{9,20}|\\d{9,22})(?![A-Za-z0-9])").find(text)?.value
    /**
     * 云端兜底触发条件：字母开头疑似单号（排除纯数字，避免手机号/订单号误判）。
     */
    fun looksLikeTrackingNumber(text: String): Boolean =
        Regex("(?<![A-Za-z0-9])[A-Za-z]{2,6}\\d{9,20}(?![A-Za-z0-9])").containsMatchIn(text)

    /**
     * v1.105 识别详情描述（供结构化日志）：返回如「顺丰速运 (SF+12位, 标准12~15)」；
     * 未识别返回 null（此时可配合 diagnose 查看原因）
     */
    fun describe(text: String): String? {
        val cleaned = extractTrackingNumber(text)?.uppercase() ?: return null
        if (EMS_REGEX.matches(cleaned)) return "EMS (国际格式 ${cleaned.length} 字符)"
        val prefixMatch = Regex("^([A-Z]{2,6})(\\d{9,20})$").find(cleaned)
        if (prefixMatch != null) {
            val prefix = prefixMatch.groupValues[1]
            val digits = prefixMatch.groupValues[2]
            val company = PREFIX_RULES.sortedByDescending { it.first.length }
                .firstOrNull { prefix.startsWith(it.first) }?.second ?: return null
            val range = STANDARD_DIGIT_LENGTHS[company] ?: (9..20)
            if (digits.length !in range) return null
            val expected = if (range.first == range.last) "${range.first} 位" else "${range.first}~${range.last} 位"
            return "$company (前缀 $prefix + ${digits.length} 位数字, 标准 $expected)"
        }
        if (cleaned.all { it.isDigit() }) {
            for ((regex, name) in DIGIT_RULES) {
                if (regex.matches(cleaned)) return "$name (纯数字 ${cleaned.length} 位)"
            }
        }
        return null
    }

    /**
     * v1.103 诊断未识别原因（供日志输出，用户可直接看懂为什么不跳转）。
     * @return 失败原因描述；null 表示可识别（detect 会返回公司名）
     */
    fun diagnose(text: String): String? {
        val cleaned = extractTrackingNumber(text)?.uppercase()
            ?: return "不含疑似单号（字母前缀+9~20位数字 或 纯9~22位数字）"
        if (EMS_REGEX.matches(cleaned)) return null
        val prefixMatch = Regex("^([A-Z]{2,6})(\\d{9,20})$").find(cleaned)
        if (prefixMatch != null) {
            val prefix = prefixMatch.groupValues[1]
            val digits = prefixMatch.groupValues[2]
            val company = PREFIX_RULES.sortedByDescending { it.first.length }
                .firstOrNull { prefix.startsWith(it.first) }?.second
            if (company == null) return "前缀 '$prefix' 未匹配已知快递公司"
            val range = STANDARD_DIGIT_LENGTHS[company] ?: (9..20)
            if (digits.length !in range) {
                val expected = if (range.first == range.last) "${range.first} 位" else "${range.first}~${range.last} 位"
                return "前缀匹配『$company』但位数不符：当前 ${digits.length} 位数字，标准 $expected（无效短号不跳转）"
            }
            return null
        }
        if (cleaned.all { it.isDigit() }) {
            for ((regex, _) in DIGIT_RULES) {
                if (regex.matches(cleaned)) return null
            }
            return "纯数字段（${cleaned.length} 位）未匹配已知快递公司格式"
        }
        return "无法解析为已知单号格式"
    }
}
