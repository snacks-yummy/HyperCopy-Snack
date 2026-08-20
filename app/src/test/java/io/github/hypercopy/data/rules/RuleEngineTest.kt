package io.github.hypercopy.data.rules

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleEngineTest {
    private val rule = RuleConfig(
        name = "123网盘",
        actionMode = RuleActionMode.DirectOpen,
        matchRegex = "123pan\\.com|123pan\\.cn|www\\.123\\d{3}\\.com",
        parameterRegex = "\\/123pan\\/([^#&?.]*)",
        extractionRegexes = listOf(
            "\\/123pan\\/([^#&?.]*)",
            "(?:\\?|&)pwd=([^#&\\?]*)(?:#|&|\\?|$)",
        ),
        target = RuleTarget(
            type = RuleTargetType.Url,
            template = "pan://umeng.com/share/list?sharePwd=${'$'}{r2}&_sdk_=umeng&action=share_list&shareKey=${'$'}{r1}",
        ),
    )

    @Test
    fun resolves123PanLinkWithoutPassword() {
        val parameters = rule.extractParameters("https://1818828312.share.123pan.cn/123pan/sJDHjv-VdnSh")
        // v1.141.35 多匹配提取命名：r1 / r1_1 / r1_1_1 并存（历史断言未含 r1_1_1，补齐）
        assertEquals(mapOf("r1" to "sJDHjv-VdnSh", "r1_1" to "sJDHjv-VdnSh", "r1_1_1" to "sJDHjv-VdnSh", "p1" to "sJDHjv-VdnSh"), parameters)
        assertEquals(
            "pan://umeng.com/share/list?sharePwd=&_sdk_=umeng&action=share_list&shareKey=sJDHjv-VdnSh",
            rule.target.resolveTemplate(parameters, encode = { it }),
        )
    }

    @Test
    fun resolves123PanLinkWithPassword() {
        // URL 使用规则匹配的 /123pan/ 路径格式（/s/ 前缀不命中 extractionRegex 的 \/123pan\/）
        val parameters = rule.extractParameters("https://1818828312.share.123pan.cn/123pan/example?pwd=8abc")
        assertEquals(
            "pan://umeng.com/share/list?sharePwd=8abc&_sdk_=umeng&action=share_list&shareKey=example",
            rule.target.resolveTemplate(parameters, encode = { it }),
        )
    }
}
