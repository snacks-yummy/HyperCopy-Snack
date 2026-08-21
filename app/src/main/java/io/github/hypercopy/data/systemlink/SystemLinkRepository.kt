package io.github.hypercopy.data.systemlink

import android.content.Context
import io.github.hypercopy.HyperLog
import io.github.hypercopy.clipboard.privileged.IntentAmStartCommand
import io.github.hypercopy.clipboard.privileged.PrivilegedShell
import io.github.hypercopy.clipboard.privileged.ShellResult
import io.github.hypercopy.data.rules.normalizeInputUrl
import io.github.hypercopy.data.settings.SettingsRepository
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class SystemLinkApp(
    val packageName: String,
    val label: String,
    val linkHandlingAllowed: Boolean,
    val domains: List<SystemLinkDomain>,
)

data class SystemLinkDomain(
    val host: String,
    val enabled: Boolean,
    val state: String,
)

data class AndroidUser(
    val id: Int,
    val name: String,
)

class SystemLinkRepository(private val context: Context) {
    private val tag = "HyperCopy"
    private val settingsRepository = SettingsRepository(context.applicationContext)
    fun readApps(userId: Int): List<SystemLinkApp> {
        // v1.140.13 修复：HyperOS/Android16 上 pm get-app-links 耗时 3s+，原 3s 超时导致 count=0；
        // 放宽至 10s + 60s 内存缓存（成功才缓存，失败/超时下次重试）
        // v1.145.15 缓存链升级：内存 60s → 文件 24h → pm（成功双写；pm 失败降级文件旧数据，不限时效）
        val cacheKey = userId
        val now = System.currentTimeMillis()
        appLinksCache[cacheKey]?.takeIf { now - it.checkedAt < APP_LINKS_CACHE_MILLIS }?.let { return it.apps }
        readCachedFromFile(userId)?.let { return it }
        val result = runFirstSuccessfulResult("pm get-app-links --user $userId", timeoutSeconds = 10L)
        val output = result.output
        if (result.exitCode != 0) {
            // v1.145.15 降级链：pm 失败 → 文件旧数据（哪怕超 24h，有数据 > 白屏）→ 空列表
            val stale = readCachedFromFile(userId, ignoreFreshness = true)
            if (stale != null) {
                HyperLog.w(tag, "pm get-app-links 失败/超时(降级为文件缓存): code=${result.exitCode} count=${stale.size}")
                return stale
            }
            HyperLog.w(tag, "pm get-app-links 失败/超时(降级为空列表): code=${result.exitCode} output=${output.take(200)}")
            return emptyList()
        }
        HyperLog.d(tag, "pm get-app-links output length=${output.length} head=${output.take(500)}")
        val apps = parseApps(output, userId)
            .sortedWith(compareBy<SystemLinkApp> { it.label }.thenBy { it.packageName })
        HyperLog.d(tag, "system link apps parsed user=$userId count=${apps.size}")
        appLinksCache[cacheKey] = AppLinksCacheEntry(apps, now)
        persistToFile(userId, apps)
        return apps
    }

    /** v1.145.15 文件缓存读取：24h 内有效；ignoreFreshness=true 时不限时效（pm 失败降级用） */
    private fun readCachedFromFile(userId: Int, ignoreFreshness: Boolean = false): List<SystemLinkApp>? {
        return runCatching {
            val file = cacheFile(userId)
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            if (!ignoreFreshness && System.currentTimeMillis() - json.optLong("checkedAt", 0L) > FILE_CACHE_MILLIS) return null
            parseCachedApps(json.optJSONArray("apps") ?: return null)
        }.onFailure {
            HyperLog.d(tag, "read system link file cache failed: user=$userId", it)
        }.getOrNull()
    }

    /** v1.145.15 文件缓存写入：原子写（tmp+renameTo，对齐 persistRules）+ 锁防并发写坏 tmp */
    private fun persistToFile(userId: Int, apps: List<SystemLinkApp>) {
        synchronized(fileCacheLock) {
            runCatching {
                val file = cacheFile(userId)
                val tmp = java.io.File(file.parentFile, file.name + ".tmp")
                tmp.writeText(
                    JSONObject()
                        .put("checkedAt", System.currentTimeMillis())
                        .put("apps", JSONArray().also { arr ->
                            apps.forEach { app ->
                                arr.put(
                                    JSONObject()
                                        .put("packageName", app.packageName)
                                        .put("label", app.label)
                                        .put("linkHandlingAllowed", app.linkHandlingAllowed)
                                        .put("domains", JSONArray().also { ds ->
                                            app.domains.forEach { d ->
                                                ds.put(
                                                    JSONObject()
                                                        .put("host", d.host)
                                                        .put("enabled", d.enabled)
                                                        .put("state", d.state),
                                                )
                                            }
                                        }),
                                )
                            }
                        }).toString(),
                )
                if (!tmp.renameTo(file)) {
                    runCatching { file.writeText(tmp.readText()) } // rename 失败回退直接写
                    runCatching { tmp.delete() }
                }
            }.onFailure {
                HyperLog.d(tag, "persist system link file cache failed: user=$userId", it)
            }
        }
    }

    private fun parseCachedApps(array: JSONArray): List<SystemLinkApp> = buildList {
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val domains = buildList {
                val ds = obj.optJSONArray("domains") ?: return@buildList
                for (j in 0 until ds.length()) {
                    val d = ds.optJSONObject(j) ?: continue
                    add(SystemLinkDomain(host = d.optString("host"), enabled = d.optBoolean("enabled"), state = d.optString("state")))
                }
            }
            add(
                SystemLinkApp(
                    packageName = obj.optString("packageName"),
                    label = obj.optString("label"),
                    linkHandlingAllowed = obj.optBoolean("linkHandlingAllowed"),
                    domains = domains,
                ),
            )
        }
    }

    private fun cacheFile(userId: Int): java.io.File =
        context.filesDir.resolve("system_links_u$userId.json")

    /** v1.145.15 toggle 后乐观更新缓存（内存+文件）：只改该 App 状态，不失效缓存。
     *  避免旧方案（set 成功删缓存）导致 toggle 后 loadApp 强制全量 pm（3s+ 解析 188 个）的卡顿。
     *  文件更新失败静默（内存 60s 兜底，下次 pm 自然校准）。 */
    private fun updateCachedApp(userId: Int, packageName: String, transform: (SystemLinkApp) -> SystemLinkApp) {
        appLinksCache[userId]?.let { entry ->
            appLinksCache[userId] = entry.copy(
                apps = entry.apps.map { if (it.packageName == packageName) transform(it) else it },
                checkedAt = System.currentTimeMillis(),
            )
        }
        synchronized(fileCacheLock) {
            runCatching {
                val file = cacheFile(userId)
                if (!file.exists()) return@runCatching
                val apps = readCachedFromFile(userId, ignoreFreshness = true) ?: return@runCatching
                persistToFile(
                    userId,
                    apps.map { if (it.packageName == packageName) transform(it) else it },
                )
            }.onFailure {
                HyperLog.d(tag, "update system link file cache failed: user=$userId", it)
            }
        }
    }

    fun readUsers(): List<AndroidUser> {
        val output = runFirstSuccessful("pm list users", "cmd user list")
        val users = Regex("UserInfo\\{(\\d+):([^:}]*)[:}]")
            .findAll(output)
            .mapNotNull { match ->
                val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                AndroidUser(id, match.groupValues[2].trim())
            }
            .distinctBy { it.id }
            .sortedBy { it.id }
            .toList()
        val result = if (users.any { it.id == 0 }) users else listOf(AndroidUser(0, "")) + users
        HyperLog.d(tag, "system users detected: ${result.joinToString { "${it.id}:${it.name}" }}")
        return result
    }

    fun readDomains(userId: Int, packageName: String): List<SystemLinkDomain> {
        // v1.140.13 放宽超时：HyperOS 上按包查询同样可能超过 3s
        val result = runFirstSuccessfulResult(
            "pm get-app-links --user $userId ${IntentAmStartCommand.shellQuote(packageName)}",
            timeoutSeconds = 8L,
        )
        if (result.exitCode != 0) {
            HyperLog.w(tag, "pm get-app-links(单包) 失败/超时: package=$packageName code=${result.exitCode} output=${result.output.take(200)}")
            return emptyList()
        }
        return parseDomains(result.output)
    }

    fun setDomainEnabled(userId: Int, packageName: String, host: String, enabled: Boolean): Boolean {
        val value = if (enabled) "true" else "false"
        val result = runFirstSuccessfulResult(
            "pm set-app-links-user-selection --user $userId --package ${IntentAmStartCommand.shellQuote(packageName)} $value ${IntentAmStartCommand.shellQuote(host)}",
        )
        HyperLog.d(tag, "set domain link user=$userId package=$packageName host=$host enabled=$enabled code=${result.exitCode}")
        if (result.exitCode == 0) {
            // v1.145.15 乐观更新缓存（不失效，避免 toggle 后全量 pm 卡顿）
            updateCachedApp(userId, packageName) { app ->
                app.copy(
                    domains = app.domains.map {
                        if (it.host == host) it.copy(enabled = enabled, state = if (enabled) "selected" else "disabled") else it
                    },
                )
            }
        }
        return result.exitCode == 0
    }

    fun setLinkHandlingAllowed(userId: Int, packageName: String, enabled: Boolean): Boolean {
        val result = runFirstSuccessfulResult(
            "pm set-app-links-allowed --user $userId --package ${IntentAmStartCommand.shellQuote(packageName)} $enabled",
        )
        HyperLog.d(tag, "set app link allowed user=$userId package=$packageName enabled=$enabled code=${result.exitCode}")
        if (result.exitCode == 0) {
            // v1.145.15 乐观更新缓存（不失效，避免 toggle 后全量 pm 卡顿）
            updateCachedApp(userId, packageName) { it.copy(linkHandlingAllowed = enabled) }
        }
        return result.exitCode == 0
    }

    fun openLink(userId: Int, url: String): Boolean {
        val safeUrl = shellQuote(normalizeInputUrl(url))
        val output = runFirstSuccessful(
            "am start --user $userId -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d $safeUrl",
        )
        return output.contains("Starting", ignoreCase = true) || output.contains("Warning: Activity not started", ignoreCase = true)
    }

    fun isPackageInstalledForUser(userId: Int, packageName: String): Boolean {
        val cacheKey = "$userId:$packageName"
        val now = System.currentTimeMillis()
        packageInstallCache[cacheKey]?.takeIf { now - it.checkedAt < PACKAGE_INSTALL_CACHE_MILLIS }?.let { return it.installed }
        val result = runFirstSuccessfulResult("pm path --user $userId ${IntentAmStartCommand.shellQuote(packageName)}")
        val installed = result.exitCode == 0 && result.output.contains("package:")
        packageInstallCache[cacheKey] = PackageInstallCacheEntry(installed, now)
        HyperLog.d(tag, "check package user=$userId package=$packageName code=${result.exitCode} hasPackage=$installed")
        return installed
    }

    private fun runFirstSuccessful(vararg commands: String): String {
        return runFirstSuccessfulResult(*commands).output
    }

    private fun runFirstSuccessfulResult(vararg commands: String, timeoutSeconds: Long = 8L): ShellResult {
        commands.forEach { command ->
            val result = PrivilegedShell.run(settingsRepository, command, timeoutSeconds)
            if (result.exitCode == 0) return result
        }
        return ShellResult(-1, "")
    }

    private fun parseApps(output: String, userId: Int): List<SystemLinkApp> {
        val apps = mutableListOf<SystemLinkApp>()
        var packageName = ""
        val domainLines = mutableListOf<String>()
        var inTargetUser = false
        var inSelectionState = false

        fun flush() {
            if (packageName.isBlank()) return
            val domains = parseDomains(domainLines.joinToString("\n"))
            if (domains.isNotEmpty()) {
                apps += SystemLinkApp(
                    packageName = packageName,
                    label = appLabel(packageName),
                    linkHandlingAllowed = parseLinkHandlingAllowed(domainLines),
                    domains = domains,
                )
            }
            domainLines.clear()
            inTargetUser = false
            inSelectionState = false
        }

        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            val detectedPackageName = rawLine.extractPackageHeader()
            if (detectedPackageName != null) {
                flush()
                packageName = detectedPackageName
                inTargetUser = false
                inSelectionState = false
                return@forEach
            }
            if (line == "Domain verification state:") {
                inTargetUser = false
                inSelectionState = false
                return@forEach
            }
            if (line.startsWith("User ") && line.endsWith(":")) {
                inTargetUser = line.removePrefix("User ").removeSuffix(":").toIntOrNull() == userId
                inSelectionState = false
                return@forEach
            }
            if (line == "Selection state:") {
                inSelectionState = inTargetUser
                return@forEach
            }
            if (line.startsWith("Verification link handling allowed:")) {
                if (inTargetUser) domainLines += line
                return@forEach
            }
            if (line == "Disabled:" || line == "Enabled:") {
                if (inTargetUser) domainLines += line
                return@forEach
            }
            if (line.contains('.') && (line.contains(':') || inSelectionState)) {
                domainLines += line
            }
        }
        flush()
        return apps
    }

    private fun parseDomains(output: String): List<SystemLinkDomain> {
        var selectionEnabled: Boolean? = null
        var inSelectionList = false
        val domains = linkedMapOf<String, SystemLinkDomain>()
        output.lineSequence()
            .map { it.trim() }
            .forEach { line ->
                when (line) {
                    "Enabled:" -> {
                        selectionEnabled = true
                        inSelectionList = true
                        return@forEach
                    }
                    "Disabled:" -> {
                        selectionEnabled = false
                        inSelectionList = true
                        return@forEach
                    }
                }
                if (line.startsWith("Verification link handling allowed:")) return@forEach
                val normalized = line.removePrefix("*").trim()
                val parts = normalized.split(Regex("\\s+"), limit = 2)
                val host = parts.getOrNull(0).orEmpty().trimEnd(':')
                val state = if (inSelectionList) selectionEnabled?.let { if (it) "selected" else "disabled" }.orEmpty()
                else parts.getOrNull(1)?.trim()?.trimStart(':')?.trim().orEmpty()
                if (!host.contains('.') || host.equals("Domains", true)) return@forEach
                if (state.isBlank()) return@forEach
                val existing = domains[host]
                if (existing?.state?.isVerifiedSystemLinkState() == true && inSelectionList) return@forEach
                val displayState = if (state.isVerifiedSystemLinkState()) "verified" else state
                val domain = SystemLinkDomain(host = host, enabled = displayState.isSystemLinkEnabled(), state = displayState)
                domains[host] = when {
                    inSelectionList -> domain
                    existing == null -> domain
                    else -> existing
                }
            }
        return domains.values
            .sortedBy { it.host }
            .toList()
    }

    private fun parseLinkHandlingAllowed(lines: List<String>): Boolean {
        return lines.firstOrNull { it.startsWith("Verification link handling allowed:") }
            ?.substringAfter(':')
            ?.trim()
            ?.equals("true", ignoreCase = true)
            ?: true
    }

    private fun String.isSystemLinkEnabled(): Boolean {
        val value = lowercase()
        return value.contains("verified") || value.contains("approved") || value.contains("selected") || value.contains("enabled")
    }

    private fun String.isVerifiedSystemLinkState(): Boolean = equals("verified", ignoreCase = true)

    private fun String.extractPackageHeader(): String? {
        val indent = takeWhile { it.isWhitespace() }.length
        if (indent > APP_HEADER_MAX_INDENT) return null
        val line = trim().removePrefix("Package:").trim().trimEnd(':').trim()
        val packageName = line.substringBefore(' ').trim()
        val suffix = line.removePrefix(packageName).trim()
        if (!PACKAGE_NAME_REGEX.matches(packageName)) return null
        if (suffix.isNotEmpty() && !UUID_REGEX.matches(suffix)) return null
        return packageName
    }

    private fun appLabel(packageName: String): String {
        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    private fun shellQuote(value: String): String = IntentAmStartCommand.shellQuote(value)

    private companion object {
        const val APP_HEADER_MAX_INDENT = 4
        val PACKAGE_NAME_REGEX = Regex("""[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+""")
        val UUID_REGEX = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
        const val PACKAGE_INSTALL_CACHE_MILLIS = 30_000L
        val packageInstallCache = ConcurrentHashMap<String, PackageInstallCacheEntry>()
        // v1.140.13 系统链接列表缓存（60s）：HyperOS 上 pm get-app-links 全量查询需 3s+，避免频繁触发
        const val APP_LINKS_CACHE_MILLIS = 60_000L
        val appLinksCache = ConcurrentHashMap<Int, AppLinksCacheEntry>()
        // v1.145.15 文件缓存（24h）：冷启动秒开 + pm 失败降级旧数据
        const val FILE_CACHE_MILLIS = 24 * 60 * 60 * 1000L
        private val fileCacheLock = Any()
    }

    private data class PackageInstallCacheEntry(val installed: Boolean, val checkedAt: Long)

    // v1.140.13 系统链接列表缓存条目（仅成功结果入缓存）
    private data class AppLinksCacheEntry(val apps: List<SystemLinkApp>, val checkedAt: Long)
}
