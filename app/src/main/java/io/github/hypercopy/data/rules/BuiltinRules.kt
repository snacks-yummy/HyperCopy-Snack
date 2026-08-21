package io.github.hypercopy.data.rules

import android.content.Context
import io.github.hypercopy.HyperLog

/**
 * 内置规则加载器。
 * 从 assets/builtin_rules/{link,text} 读取云规则仓库的全部规则，
 * 使用独立 id 前缀 builtin_，保证开箱即用且与云规则下载互不冲突。
 */
object BuiltinRules {
    private const val TAG = "内置规则"

    const val ID_PREFIX = "builtin_"

    fun loadAll(context: Context): List<RuleConfig> {
        val assets = context.assets
        val result = mutableListOf<RuleConfig>()
        for (folder in listOf("link", "text")) {
            val files = runCatching {
                assets.list("builtin_rules/$folder")?.filter { it.endsWith(".json", ignoreCase = true) }.orEmpty()
            }.getOrDefault(emptyList())
            for (fileName in files) {
                val content = runCatching {
                    assets.open("builtin_rules/$folder/$fileName").bufferedReader().use { it.readText() }
                }.getOrNull() ?: continue
                val rule = parseBuiltinRule(content, folder, fileName) ?: continue
                result.add(rule)
            }
        }
        // "浏览器"规则是通配规则（匹配所有 URL），必须放到列表最后作为兜底：
        // 保证其他 App 规则优先命中，未命中任何具体规则时才用浏览器打开
        val browser = result.firstOrNull { it.id == BROWSER_RULE_ID }
        if (browser != null) {
            result.remove(browser)
            result.add(browser)
        }
        HyperLog.d(TAG, "builtin rules loaded: ${result.size}")
        return result
    }

    /** 内置"浏览器"兜底规则稳定 id（assets: link/浏览器.json） */
    const val BROWSER_RULE_ID = "builtin_cloud_link_浏览器"

    private fun parseBuiltinRule(content: String, folder: String, fileName: String): RuleConfig? {
        return runCatching {
            val baseName = fileName.removeSuffix(".json").removeSuffix(".JSON")
            val parsed = rulesFromJson(content).firstOrNull() ?: return@runCatching null
            val stableId = "cloud_${folder}_$baseName"
            parsed.copy(id = "$ID_PREFIX$stableId")
        }.getOrNull()
    }
}
