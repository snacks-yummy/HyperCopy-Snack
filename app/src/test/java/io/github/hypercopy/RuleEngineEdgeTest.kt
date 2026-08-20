package io.github.hypercopy

import io.github.hypercopy.data.rules.RuleActionMode
import io.github.hypercopy.data.rules.RuleConfig
import io.github.hypercopy.data.rules.RuleTarget
import io.github.hypercopy.data.rules.RuleTargetType
import io.github.hypercopy.data.rules.extractParameters
import io.github.hypercopy.data.rules.matchesInput
import io.github.hypercopy.data.rules.resolveTemplate
import io.github.hypercopy.data.rules.rulesFromJson
import io.github.hypercopy.data.rules.rulesToJson
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * v1.142.3 规则引擎边界测试（P2-15 测试用例扩充，5 类缺口）
 * 覆盖：①非法正则容错 ②编码边界 ③多 pattern 组合 ④rules.json 损坏恢复 ⑤迁移幂等性（标注，需 Robolectric）
 * 纯 JVM 可跑：不依赖 android.*（resolveTemplate 一律传自定义 encode，避开 Uri::encode stub）
 */
class RuleEngineEdgeTest {

    private fun rule(
        matchRegex: String = "",
        parameterRegex: String = "",
        extraction: List<String> = emptyList(),
        trigger: List<String> = emptyList(),
        excludeRegex: String = "",
        regexOptions: String = "",
        template: String = "\${input}",
    ) = RuleConfig(
        name = "edge",
        actionMode = RuleActionMode.ParseAndOpen,
        matchRegex = matchRegex,
        parameterRegex = parameterRegex,
        extractionRegexes = extraction,
        triggerRegexes = trigger,
        excludeRegex = excludeRegex,
        regexOptions = regexOptions,
        target = RuleTarget(type = RuleTargetType.Url, template = template),
    )

    // ===== ① 非法正则容错（runCatching 兜底，不崩溃） =====

    @Test
    fun invalidMatchRegexDoesNotCrash() {
        // 未闭合括号：PatternSyntaxException → matchesInput 应返回 false 而非崩溃
        val r = rule(matchRegex = "(")
        assertFalse(r.matchesInput("anything"))
        assertFalse(r.matchesInput("("))
    }

    @Test
    fun invalidExtractionRegexDoesNotCrash() {
        // 非法提取正则：extractParameters 应返回空 map 而非崩溃
        val r = rule(matchRegex = ".*", extraction = listOf("([a-z"))
        val params = r.extractParameters("hello 123")
        assertTrue(params.isEmpty())
    }

    @Test
    fun invalidExcludeRegexDoesNotCrash() {
        // 非法排除正则：matchesInput 不应崩溃（exclude 容错失败 = 不排除，走正常匹配）
        val r = rule(matchRegex = "https?://.*", excludeRegex = "[")
        assertTrue(r.matchesInput("https://a.com"))
    }

    @Test
    fun invalidTriggerRegexDoesNotCrash() {
        val r = rule(trigger = listOf("("), matchRegex = "")
        assertFalse(r.matchesInput("anything"))
    }

    // ===== ② 编码边界（encode 注入，避开 android Uri stub） =====

    @Test
    fun encodeFunctionAppliesEncoding() {
        // 注意：值含真实空格字符（"中文 空格"），replace(" ") 才有替换目标
        val target = RuleTarget(type = RuleTargetType.Url, template = "x?\${encode:r1}")
        val resolved = target.resolveTemplate(mapOf("r1" to "中文 空格"), encode = { it.replace(" ", "%20") })
        assertEquals("x?中文%20空格", resolved)
    }

    @Test
    fun rawFunctionBypassesEncoding() {
        val target = RuleTarget(type = RuleTargetType.Url, template = "x?\${raw:r1}")
        // raw 原样直出：即使 encode 有副作用也不影响 raw 参数
        val resolved = target.resolveTemplate(mapOf("r1" to "a/b?c=d"), encode = { it.replace("/", "%2F") })
        assertEquals("x?a/b?c=d", resolved)
    }

    @Test
    fun urlFunctionExtractsFirstUrlFromMixedText() {
        // ${url:input}：从混合文本提取第一个 URL 用于跳转
        // 注意：用裸域名（JVM 环境 Uri.parse 为 stub 返回 null → 无协议补 https:// 恰好得到正确结果；
        //       带协议 URL 在 JVM 会双前缀（stub 判定无协议），真机 Uri 正常无此问题）
        val target = RuleTarget(type = RuleTargetType.Url, template = "\${url:input}")
        val resolved = target.resolveTemplate(mapOf("input" to "复制链接 a.com/x?y=1 更多文字"), encode = { it })
        assertEquals("https://a.com/x?y=1", resolved)
    }

    @Test
    fun chineseTextWithEncodeDoesNotCorrupt() {
        // 中文提取值经编码后跳转（encode 注入 UTF-8 语义）
        val target = RuleTarget(type = RuleTargetType.Url, template = "app://\${r1}")
        val resolved = target.resolveTemplate(mapOf("r1" to "验证码"), encode = { java.net.URLEncoder.encode(it, "UTF-8") })
        assertEquals("app://%E9%AA%8C%E8%AF%81%E7%A0%81", resolved)
    }

    // ===== ③ 多 pattern 组合（matchRegex + parameterRegex + extractionRegexes + trigger） =====

    @Test
    fun combinedPatternsMatchAndExtract() {
        // trigger 触发 + matchRegex 兜底 + parameterRegex 提取 + extractionRegexes 二次提取
        val r = rule(
            matchRegex = "快递|express",
            trigger = listOf("JD[0-9]{13}"),
            parameterRegex = "(JD[0-9]{13})",
            extraction = listOf("(JD[0-9]{13})"),
        )
        assertTrue(r.matchesInput("JD1234567890123"))
        assertFalse(r.matchesInput("SF1234567890123")) // 不命中 trigger 也不命中 matchRegex
        val params = r.extractParameters("你的JD1234567890123快递到了")
        assertEquals("JD1234567890123", params["r1"])
    }

    @Test
    fun matchAllTriggersRequiresAllPatterns() {
        // matchAllTriggers=true：所有 trigger 都需命中（AND）
        val r = rule(trigger = listOf("JD[0-9]{13}", "快递")).copy(matchAllTriggers = true)
        assertTrue(r.matchesInput("JD1234567890123 快递"))
        assertFalse(r.matchesInput("JD1234567890123 无此字样")) // 注意：不能含"快递"子串
    }

    // ===== ④ rules.json 损坏恢复（readRules 层已 runCatching 兜底为空列表） =====

    @Test
    fun blankJsonReturnsEmpty() {
        assertEquals(0, rulesFromJson("").size)
        assertEquals(0, rulesFromJson("   ").size)
    }

    @Test
    fun malformedJsonThrows() {
        // rulesFromJson 对损坏 JSON 抛异常 → readRules 的 runCatching 兜底为 emptyList（App 不崩溃）
        assertThrows(JSONException::class.java) { rulesFromJson("not-json{{{") }
        assertThrows(JSONException::class.java) { rulesFromJson("[{\"name\":\"x\"") } // 截断数组
        assertThrows(JSONException::class.java) { rulesFromJson("{\"rules\":[}") } // 结构损坏
    }

    @Test
    fun jsonRoundTripPreservesContent() {
        val r = rule(matchRegex = "test-\\d+", template = "app://\${r1}")
        val restored = rulesFromJson(rulesToJson(listOf(r)))
        assertEquals(1, restored.size)
        assertEquals("test-\\d+", restored[0].matchRegex)
        assertEquals("app://\${r1}", restored[0].target.template)
    }

    @Test
    fun malformedItemInArrayIsSkipped() {
        // 数组内混入非对象项（null/数字）→ optJSONObject 为 null → 跳过不崩溃
        val restored = rulesFromJson("""[{"name":"a","matchRegex":"x"},null,42,{"name":"b","matchRegex":"y"}]""")
        assertEquals(2, restored.size)
        assertEquals("a", restored[0].name)
        assertEquals("b", restored[1].name)
    }

    // ===== ⑤ 迁移函数幂等性（标注：需 Robolectric） =====
    // 现状：RuleRepository.readRules 内 13+ 迁移函数（v1.101→v1.141.73）依赖 Android Context（filesDir），
    // JVM 单测无法直接实例化。验证方案：引入 Robolectric 后对 readRules 跑两遍断言规则集一致。
    // 本轮不引入 Robolectric（大依赖），迁移幂等性靠真机升级路径实测兜底。
}
