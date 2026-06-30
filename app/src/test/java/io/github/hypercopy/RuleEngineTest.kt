package io.github.hypercopy

import android.content.Intent
import io.github.hypercopy.data.rules.RuleActionMode
import io.github.hypercopy.data.rules.RuleCategory
import io.github.hypercopy.data.rules.RuleConfig
import io.github.hypercopy.data.rules.RuleTarget
import io.github.hypercopy.data.rules.RuleTargetType
import io.github.hypercopy.data.rules.contentSignature
import io.github.hypercopy.data.rules.findRule
import io.github.hypercopy.data.rules.matchRule
import io.github.hypercopy.data.rules.resolveTemplate
import io.github.hypercopy.data.rules.rulesFromJson
import io.github.hypercopy.data.rules.rulesToJson
import io.github.hypercopy.data.rules.sameContentAs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规则引擎回归测试（bug⑥ 覆盖扩充） */
class RuleEngineTest {

    private fun rule(
        matchRegex: String = "",
        extraction: List<String> = emptyList(),
        template: String = "\${input}",
        actionMode: RuleActionMode = RuleActionMode.ParseAndOpen,
        excludeRegex: String = "",
        regexOptions: String = "",
    ) = RuleConfig(
        name = "t",
        actionMode = actionMode,
        matchRegex = matchRegex,
        parameterRegex = "",
        extractionRegexes = extraction,
        excludeRegex = excludeRegex,
        regexOptions = regexOptions,
        target = RuleTarget(type = RuleTargetType.Url, template = template),
    )

    @Test
    fun emptyRegexMatchesNothing() {
        // v1.17 安全降级：空正则不匹配任何内容（绝不拦截一切）
        val r = rule(matchRegex = "")
        assertFalse(r.matchesInput("anything"))
        assertFalse(r.matchesInput(""))
    }

    @Test
    fun excludeRegexSkips() {
        // v1.21 排除规则：命中 excludeRegex 即跳过
        val r = rule(matchRegex = "https?://.*", excludeRegex = "example\\.com")
        assertTrue(r.matchesInput("https://a.com"))
        assertFalse(r.matchesInput("https://example.com/x"))
    }

    @Test
    fun regexOptionsIgnoreCase() {
        // v1.21 正则选项：i 忽略大小写
        val r = rule(matchRegex = "bv\\w+", regexOptions = "i")
        assertTrue(r.matchesInput("BV1abc"))
        assertTrue(r.matchesInput("bv1ABC"))
        val noOpt = rule(matchRegex = "bv\\w+")
        assertFalse(noOpt.matchesInput("BV1abc"))
    }

    @Test
    fun multiMatchExtraction() {
        // v1.21 多参数提取：findAll 提取多个 match，r{n}_{k}_{m} 命名
        val r = rule(matchRegex = "", extraction = listOf("(\\d{3})"))
        val params = r.extractParameters("123 456 789")
        assertEquals("123", params["r1"])
        assertEquals("123", params["r1_1_1"])
        assertEquals("456", params["r1_2_1"])
        assertEquals("789", params["r1_3_1"])
    }

    @Test
    fun templateFunctions() {
        // v1.21 模板函数：lower/upper/encode
        val target = RuleTarget(type = RuleTargetType.Url, template = "app://\${lower:r1}/\${upper:r2}/\${pkg}")
        val resolved = target.resolveTemplate(mapOf("r1" to "AbC", "r2" to "Xyz"), encode = { it })
        assertEquals("app://abc/XYZ/com.test", resolved)
    }

    @Test
    fun templateTimeFunction() {
        // v1.21 时间函数：${time:...} 非空
        val target = RuleTarget(type = RuleTargetType.Url, template = "x?\${time:yyyyMMdd}")
        val resolved = target.resolveTemplate(emptyMap(), encode = { it })
        assertTrue(resolved.startsWith("x?"))
        assertTrue(resolved.length > 2)
    }

    @Test
    fun prioritySortingInRepositoryJson() {
        // v1.21 schema：新字段序列化往返一致
        val r = rule(matchRegex = "x").copy(priority = 5, group = "购物", excludeRegex = "a", regexOptions = "i")
        val json = rulesToJson(listOf(r))
        val restored = rulesFromJson(json)
        assertEquals(1, restored.size)
        assertEquals(5, restored[0].priority)
        assertEquals("购物", restored[0].group)
        assertEquals("a", restored[0].excludeRegex)
        assertEquals("i", restored[0].regexOptions)
    }

    @Test
    fun sameContentSignatureIgnoresManagementFields() {
        // 管理字段（priority/group/notificationMode/name）不影响内容去重
        val a = rule(matchRegex = "x").copy(priority = 1, group = "A", name = "n1")
        val b = rule(matchRegex = "x").copy(priority = 9, group = "B", name = "n2")
        assertTrue(a.sameContentAs(b))
        assertEquals(a.contentSignature(), b.contentSignature())
    }

    @Test
    fun directOpenFallsBackToIntent() {
        val r = rule(matchRegex = "", actionMode = RuleActionMode.DirectOpen).copy(
            target = RuleTarget(type = RuleTargetType.Url, template = "", packageName = "com.x"),
        )
        val intent = r.directIntent("https://example.com")
        assertNotNull(intent)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun disabledRuleNeverMatches() {
        val r = rule(matchRegex = ".*").copy(enabled = false)
        assertNull(findRule("x", listOf(r)))
        assertNull(matchRule("x", listOf(r)))
    }
}