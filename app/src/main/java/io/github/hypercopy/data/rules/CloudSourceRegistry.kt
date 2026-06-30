package io.github.hypercopy.data.rules

import android.content.Context
import io.github.hypercopy.data.settings.SettingsRepository
import org.json.JSONObject

/**
 * v1.139.1 云端规则源配置（换源）：
 * - 内置源：作者 1812z（GitHub + 加速站双通道自动选最快）/ 零食仓库
 * - 自定义源：用户粘贴链接添加（GitHub 仓库 或 加速站），持久化保存
 */
data class CloudSourceConfig(
    val key: String,
    val displayName: String,
    val repoOwner: String,
    val repoName: String,
    val acceleratedBase: String? = null,
    val isBuiltin: Boolean = true,
    // v1.140.18 源说明：注明原作者 / 授权信息（源管理对话框副标题展示）
    val description: String = "",
) {
    val githubRepo: String get() = "$repoOwner/$repoName"
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("displayName", displayName)
        put("repoOwner", repoOwner)
        put("repoName", repoName)
        put("acceleratedBase", acceleratedBase ?: "")
        put("description", description)
    }
    companion object {
        fun fromJson(obj: JSONObject): CloudSourceConfig = CloudSourceConfig(
            key = obj.optString("key"),
            displayName = obj.optString("displayName"),
            repoOwner = obj.optString("repoOwner"),
            repoName = obj.optString("repoName"),
            acceleratedBase = obj.optString("acceleratedBase").ifBlank { null },
            isBuiltin = false,
            description = obj.optString("description"),
        )
    }
}

/** v1.139.1 源注册表：内置源 + 自定义源（持久化） */
object CloudSourceRegistry {
    // 内置源 ①：作者 1812z（GitHub + 加速站双通道，自动选最快）
    val AUTHOR = CloudSourceConfig(
        key = "1812z",
        displayName = "作者 1812z",
        repoOwner = "1812z",
        repoName = "HyperCopy_Rules",
        acceleratedBase = "https://hypercopy.1812z.top/rules",
        description = "原项目作者：1812z（云端规则原作者）",
    )
    // 内置源 ②：零食仓库（用户自维护规则仓库）
    val MINE = CloudSourceConfig(
        key = "snacks",
        displayName = "零食仓库",
        repoOwner = "snacks-yummy",
        repoName = "HyperCopy_Rules",
        description = "我的二改仓库（基于原项目二次开发，已获作者授权）",
    )
    val builtinSources: List<CloudSourceConfig> = listOf(AUTHOR, MINE)

    fun allSources(context: Context): List<CloudSourceConfig> =
        builtinSources + SettingsRepository(context.applicationContext).readCustomCloudSources()

    fun byKey(context: Context, key: String): CloudSourceConfig? =
        allSources(context).firstOrNull { it.key == key }

    /** 自定义源 key：owner_repo 安全化 */
    fun customKey(owner: String, repo: String): String =
        "custom_${owner.lowercase().filter { it.isLetterOrDigit() }}_${repo.lowercase().filter { it.isLetterOrDigit() }}"

    /** 解析 GitHub 仓库链接：https://github.com/owner/repo */
    fun parseGitHubUrl(url: String): Pair<String, String>? {
        val m = Regex("https?://github.com/([\\w.-]+)/([\\w.-]+)").find(url.trim().removeSuffix("/")) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    /** 解析加速站链接：https://host/rules 形式 */
    fun parseAcceleratedUrl(url: String): String? {
        val trimmed = url.trim().removeSuffix("/")
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) return null
        if (trimmed.contains("github.com")) return null
        return trimmed
    }
}
