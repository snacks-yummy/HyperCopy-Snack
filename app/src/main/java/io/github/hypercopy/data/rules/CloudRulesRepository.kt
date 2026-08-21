package io.github.hypercopy.data.rules

import io.github.hypercopy.HyperLog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远端规则仓库。支持从 GitHub API 或加速源拉取规则。
 */
class CloudRulesRepository(
    private val config: CloudSourceConfig,
    private val appContext: android.content.Context,
) {
    // v1.145.10 冷却状态持久化（SP 按源隔离，跨进程重启保留）——本网络 GitHub 直连
    // 实测可用(1s)而代理/加速站全失效，冷却记忆持久化避免每次启动重复探测失败通道
    private fun cooldownPrefs() =
        appContext.getSharedPreferences("cloud_rules_cooldown", android.content.Context.MODE_PRIVATE)
    private fun githubInCooldown(): Boolean =
        cooldownPrefs().getLong("${KEY_COOLDOWN_UNTIL}_${config.key}", 0L) > System.currentTimeMillis()
    private fun recordGithubSuccess() {
        cooldownPrefs().edit()
            .putInt("${KEY_FAILURES}_${config.key}", 0)
            .putLong("${KEY_COOLDOWN_UNTIL}_${config.key}", 0L).apply()
    }
    private fun recordGithubFailure() {
        val prefs = cooldownPrefs()
        val failures = prefs.getInt("${KEY_FAILURES}_${config.key}", 0) + 1
        if (failures >= GITHUB_FAILURE_THRESHOLD) {
            prefs.edit()
                .putInt("${KEY_FAILURES}_${config.key}", failures)
                .putLong("${KEY_COOLDOWN_UNTIL}_${config.key}", System.currentTimeMillis() + GITHUB_COOLDOWN_MILLIS)
                .apply()
            HyperLog.d(TAG, "GitHub channel cooldown ${GITHUB_COOLDOWN_MILLIS / 1000}s (persisted, survives restart), accel-only mode")
        } else {
            prefs.edit().putInt("${KEY_FAILURES}_${config.key}", failures).apply()
        }
    }
    // v1.140.18 通道记忆：probe 确认的最快通道作为后续下载首选；下载实际成功通道动态更新
    @Volatile private var preferredChannel: String? = null
    private val lastSuccessChannel = AtomicReference<String?>(null)

    suspend fun listRules(folder: String): List<CloudRule> = withContext(Dispatchers.IO) {
        HyperLog.d(TAG, "listRules: source=${config.key}, folder=$folder")
        val accelCall = config.acceleratedBase?.let { base -> { listRulesFromAccelerated(base, folder) } }
        // v1.139.1 修复：自定义加速站源无 GitHub 仓库时只走加速站（避免无效 GitHub 请求）
        if (config.repoOwner.isBlank()) {
            return@withContext (accelCall?.invoke() ?: throw CloudRuleException(CloudRuleError.NetworkError))
        }
        // v1.140.13 修复：GitHub API 不可达环境下每次并发发起无效请求 + E 级错误噪音；
        // 连续失败进入冷却期，冷却期内只走加速通道
        val githubCall = githubCallIfAvailable { listRulesFromGithub(folder) }
        if (githubCall == null && accelCall != null) {
            // v1.145.10 修复：冷却期加速通道失败时回退 GitHub 直连
            // （实测代理/加速站全失效而 GitHub 直连可用——失效加速源不再阻塞链路）
            return@withContext runCatching { accelCall() }.getOrElse {
                HyperLog.d(TAG, "Accel channel failed(${it.message}), fallback to GitHub direct during cooldown")
                listRulesFromGithub(folder)
            }
        }
        fetchFastest("github" to githubCall, "accel" to accelCall)
    }
    suspend fun downloadRule(cloudRule: CloudRule): RuleConfig = withContext(Dispatchers.IO) {
        HyperLog.d(TAG, "downloadRule: url=${cloudRule.downloadUrl}")
        val accelCall = if (cloudRule.acceleratedUrl != null) {
            { downloadRuleFromAccelerated(cloudRule) }
        } else null
        if (config.repoOwner.isBlank()) {
            return@withContext (accelCall?.invoke() ?: throw CloudRuleException(CloudRuleError.NetworkError))
        }
        val githubCall = githubCallIfAvailable { downloadRuleFromGithub(cloudRule) }
        // v1.145.11 六通道自动切换：GitHub 直连 + 4 个实测可用代理 + 加速站，并行先成功者胜
        val ghfastCall = { downloadRuleFromProxy(cloudRule, GHFAST_BASE) }
        val ghproxyCall = { downloadRuleFromProxy(cloudRule, GHPROXY_BASE) }
        val ghfast2Call = { downloadRuleFromProxy(cloudRule, GHFAST_BASE_2) }
        val ghproxy2Call = { downloadRuleFromProxy(cloudRule, GHPROXY_BASE_2) }
        // v1.140.18 通道记忆：优先走探测确认的最快通道（首次确认后不再每次全并行），失败才回退多通道并行
        val preferred = preferredChannel
        if (preferred != null) {
            val preferredCall = when (preferred) {
                "github" -> githubCall
                "accel" -> accelCall
                "ghfast" -> ghfastCall
                "ghproxy" -> ghproxyCall
                "ghfast2" -> ghfast2Call
                "ghproxy2" -> ghproxy2Call
                else -> null
            }
            if (preferredCall != null) {
                try {
                    val result = preferredCall()
                    if (preferred == "github") recordGithubSuccess()
                    return@withContext result
                } catch (e: Exception) {
                    if (preferred == "github") recordGithubFailure()
                    HyperLog.d(TAG, "Preferred channel $preferred failed, fallback to multi-channel parallel: ${e.message}")
                }
            }
        }
        val result = fetchFastest(
            "github" to githubCall,
            "accel" to accelCall,
            "ghfast" to ghfastCall,
            "ghproxy" to ghproxyCall,
            "ghfast2" to ghfast2Call,
            "ghproxy2" to ghproxy2Call,
        )
        // 成功后把记忆更新为实际成功的通道
        lastSuccessChannel.get()?.let { preferredChannel = it }
        result
    }

    /** v1.140.13 GitHub 通道冷却判断：冷却期内返回 null（只走加速通道）；v1.145.10 冷却已持久化 */
    private fun <T> githubCallIfAvailable(block: () -> T): (() -> T)? {
        if (githubInCooldown()) {
            HyperLog.d(TAG, "GitHub channel in cooldown (persisted), accel-only: remainingMs=${
                cooldownPrefs().getLong("${KEY_COOLDOWN_UNTIL}_${config.key}", 0L) - System.currentTimeMillis()
            }")
            return null
        }
        return block
    }

    /** v1.139.1 智能双通道：并发请求，先成功者胜（自动选最快；失败自动容错）
     *  v1.140.13 通道可空；GitHub 通道成功重置冷却 / 失败累计计数
     *  v1.140.18 通用化：任意数量命名通道并行，先成功者胜（三通道下载） */
    private fun <T> fetchFastest(vararg namedCalls: Pair<String, (() -> T)?>): T {
        val calls = buildList {
            namedCalls.forEach { (name, call) -> if (call != null) add(name to call) }
        }
        // v1.140.18 防御：全部通道不可用时（如无加速源且 GitHub 冷却中）直接报网络错误
        if (calls.isEmpty()) {
            throw CloudRuleException(CloudRuleError.NetworkError)
        }
        if (calls.size == 1) {
            return runCatching { calls[0].second.invoke() }.getOrElse {
                if (calls[0].first == "github") recordGithubFailure()
                throw it
            }.also { lastSuccessChannel.set(calls[0].first) }
        }
        val result = AtomicReference<T?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        val workers = mutableListOf<Thread>()
        calls.forEach { (name, block) ->
            val t = Thread {
                runCatching { block() }.onSuccess { r ->
                    if (name == "github") recordGithubSuccess()
                    // v1.140.18 通道记忆：记录实际成功通道
                    lastSuccessChannel.set(name)
                    if (result.compareAndSet(null, r)) latch.countDown()
                }.onFailure {
                    if (name == "github") recordGithubFailure()
                    failure.set(it)
                }
            }
            t.isDaemon = true
            workers += t
            t.start()
        }
        latch.await()
        workers.forEach { runCatching { it.interrupt() } }
        result.get()?.let { return it }
        throw failure.get() ?: CloudRuleException(CloudRuleError.NetworkError)
    }
    private fun listRulesFromAccelerated(base: String, folder: String): List<CloudRule> {
        val indexUrl = "$base/index.json"
        HyperLog.d(TAG, "listRulesFromAccelerated: $indexUrl")
        val (status, body) = httpGet(indexUrl)
        if (status != HttpURLConnection.HTTP_OK) throw CloudRuleException(CloudRuleError.LoadFailed)
        val array = JSONArray(body)
        return buildList {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val file = entry.optString("file")
                if (!file.contains("/$folder/")) continue
                val fileName = file.substringAfterLast("/")
                val parsed = parseRuleFileName(fileName) ?: continue
                add(
                    CloudRule(
                        name = entry.optString("name", parsed.name),
                        packageName = parsed.packageName,
                        fileName = fileName,
                        folder = folder,
                        downloadUrl = "$base/${file.removePrefix("rules/")}",
                        acceleratedUrl = "$base/${file.removePrefix("rules/")}",
                        size = 0L,
                        sourceKey = config.key,
                    ),
                )
            }
        }
    }

    private fun downloadRuleFromAccelerated(cloudRule: CloudRule): RuleConfig {
        if (cloudRule.downloadUrl.isBlank()) throw CloudRuleException(CloudRuleError.MissingDownloadUrl)
        HyperLog.d(TAG, "downloadRuleFromAccelerated: ${cloudRule.downloadUrl}")
        val raw = readText(cloudRule.downloadUrl)
        val parsed = parseRuleContent(raw)
        validateDownloaded(parsed)
        val stableId = "cloud_${config.key}_${cloudRule.folder}_${cloudRule.fileNameWithoutExt()}"
        val category = resolveCategory(cloudRule.folder, parsed.category)
        return parsed.copy(id = stableId, name = cloudRule.name, category = category)
    }
    // ===== GitHub =====
    private fun listRulesFromGithub(folder: String): List<CloudRule> {
        val endpoint = "$GITHUB_API_BASE/repos/${config.githubRepo}/contents/$folder"
        val response = readJsonArray(endpoint)
        return buildList {
            for (index in 0 until response.length()) {
                val entry = response.optJSONObject(index) ?: continue
                if (entry.optString("type") != "file") continue
                val fileName = entry.optString("name")
                if (!fileName.endsWith(".json", ignoreCase = true)) continue
                val parsed = parseRuleFileName(fileName) ?: continue
                add(
                    CloudRule(
                        name = parsed.name,
                        packageName = parsed.packageName,
                        fileName = fileName,
                        folder = folder,
                        downloadUrl = entry.optString("download_url"),
                        size = entry.optLong("size", 0L),
                        sourceKey = config.key,
                    ),
                )
            }
        }
    }

    private fun downloadRuleFromGithub(cloudRule: CloudRule): RuleConfig {
        if (cloudRule.downloadUrl.isBlank()) throw CloudRuleException(CloudRuleError.MissingDownloadUrl)
        val raw = readText(cloudRule.downloadUrl)
        val parsed = parseRuleContent(raw)
        validateDownloaded(parsed)
        val stableId = "cloud_${config.key}_${cloudRule.folder}_${cloudRule.fileNameWithoutExt()}"
        val category = resolveCategory(cloudRule.folder, parsed.category)
        return parsed.copy(id = stableId, name = cloudRule.name, category = category)
    }
    /** v1.140.18 三通道：GitHub 文件代理下载（ghfast/gh-proxy 转发 raw.githubusercontent） */
    private fun downloadRuleFromProxy(cloudRule: CloudRule, proxyBase: String): RuleConfig {
        val rawUrl = "https://raw.githubusercontent.com/${config.githubRepo}/main/${cloudRule.folder}/${cloudRule.fileName}"
        val proxyUrl = "$proxyBase/$rawUrl"
        HyperLog.d(TAG, "downloadRuleFromProxy: $proxyUrl")
        val raw = readText(proxyUrl)
        val parsed = parseRuleContent(raw)
        validateDownloaded(parsed)
        val stableId = "cloud_${config.key}_${cloudRule.folder}_${cloudRule.fileNameWithoutExt()}"
        val category = resolveCategory(cloudRule.folder, parsed.category)
        return parsed.copy(id = stableId, name = cloudRule.name, category = category)
    }
    /** v1.140.18 进入云端页面时探测最快下载通道（并发 GET 小文件测速，8s 内取最快成功者） */
    suspend fun probeFastestDownloadChannel(): String = withContext(Dispatchers.IO) {
        if (config.repoOwner.isBlank()) {
            return@withContext config.acceleratedBase?.let { "accel" } ?: "github"
        }
        val rawProbe = "https://raw.githubusercontent.com/${config.githubRepo}/main/README.md"
        val candidates = buildList {
            add("github" to { probeChannel(rawProbe) })
            add("ghfast" to { probeChannel("$GHFAST_BASE/$rawProbe") })
            add("ghproxy" to { probeChannel("$GHPROXY_BASE/$rawProbe") })
            add("ghfast2" to { probeChannel("$GHFAST_BASE_2/$rawProbe") })
            add("ghproxy2" to { probeChannel("$GHPROXY_BASE_2/$rawProbe") })
            config.acceleratedBase?.let { base -> add("accel" to { probeChannel("$base/index.json") }) }
        }
        val best = AtomicReference<Pair<String, Long>?>(null)
        val done = CountDownLatch(candidates.size)
        candidates.forEach { (name, block) ->
            val t = Thread {
                val t0 = System.currentTimeMillis()
                runCatching { block() }.onSuccess {
                    val ms = System.currentTimeMillis() - t0
                    best.updateAndGet { cur -> if (cur == null || ms < cur.second) name to ms else cur }
                }
                done.countDown()
            }
            t.isDaemon = true
            t.start()
        }
        done.await(8, TimeUnit.SECONDS)
        val channel = best.get()?.first ?: "github"
        // v1.140.18 通道记忆：探测确认的最快通道作为后续下载首选（直到下次进入页面重新探测）
        preferredChannel = channel
        channel
    }
    private fun probeChannel(url: String) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", USER_AGENT)
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) throw CloudRuleException(CloudRuleError.NetworkError)
        } finally {
            conn.disconnect()
        }
    }

    /** 校验下载的规则有效性：空内容/损坏文件不得产生"匹配所有"的危险规则 */
    private fun validateDownloaded(parsed: RuleConfig) {
        val hasPattern = parsed.matchRegex.isNotBlank() || parsed.triggerRegexes.any { it.isNotBlank() }
        if (parsed.name.isBlank() || !hasPattern) {
            throw CloudRuleException(CloudRuleError.DownloadFailed)
        }
    }

    // ===== 通用 =====

    private fun parseRuleContent(text: String): RuleConfig {
        if (text.isBlank()) return RuleConfig(name = "", matchRegex = "", parameterRegex = "", target = RuleTarget(type = RuleTargetType.Url, template = ""))
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("[") -> {
                val array = JSONArray(trimmed)
                val first = array.optJSONObject(0) ?: JSONObject()
                ruleConfigFromJson(first)
            }
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                val rulesArray = obj.optJSONArray("rules")
                if (rulesArray != null && rulesArray.length() > 0) {
                    ruleConfigFromJson(rulesArray.optJSONObject(0) ?: JSONObject())
                } else {
                    ruleConfigFromJson(obj)
                }
            }
            else -> ruleConfigFromJson(JSONObject())
        }
    }

    private fun resolveCategory(folder: String, parsed: RuleCategory): RuleCategory = when (folder) {
        FOLDER_LINK -> RuleCategory.Link
        FOLDER_TEXT -> when (parsed) {
            RuleCategory.Text, RuleCategory.Address, RuleCategory.Express -> parsed
            else -> RuleCategory.Text
        }
        else -> parsed
    }

    private fun readJsonArray(url: String): JSONArray {
        val (status, body) = httpGet(url)
        if (status == HttpURLConnection.HTTP_NOT_FOUND) return JSONArray()
        if (status != HttpURLConnection.HTTP_OK) throw CloudRuleException(CloudRuleError.LoadFailed)
        return JSONArray(body)
    }

    private fun readText(url: String): String {
        val (status, body) = httpGet(url)
        if (status != HttpURLConnection.HTTP_OK) throw CloudRuleException(CloudRuleError.DownloadFailed)
        return body
    }

    private fun httpGet(url: String): HttpResult {
        var connection: HttpURLConnection? = null
        return try {
            HyperLog.d(TAG, "httpGet: $url")
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", ACCEPT_HEADER)
                setRequestProperty("User-Agent", USER_AGENT)
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            HyperLog.d(TAG, "httpGet: code=$code, body.length=${body.length}")
            HttpResult(code, body)
        } catch (e: Exception) {
            HyperLog.e(TAG, "httpGet failed: $url", e)
            throw CloudRuleException(CloudRuleError.NetworkError, e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseRuleFileName(fileName: String): ParsedFileName? {
        if (!fileName.endsWith(".json", ignoreCase = true)) return null
        val base = fileName.removeSuffix(".json").removeSuffix(".JSON")
        val underscoreIndex = base.lastIndexOf('_')
        return if (underscoreIndex > 0 && underscoreIndex < base.length - 1) {
            ParsedFileName(base.substring(0, underscoreIndex), base.substring(underscoreIndex + 1))
        } else {
            ParsedFileName(base, "")
        }
    }

    private data class ParsedFileName(val name: String, val packageName: String)

    private data class HttpResult(val status: Int, val body: String)

    companion object {
        private const val TAG = "云规则"
        private const val GITHUB_API_BASE = "https://api.github.com"
        // v1.140.18 三通道：GitHub 文件代理前缀（raw.githubusercontent 前缀转发）
        // v1.145.11 全网调研+实测更新（2026-08-21）：旧 ghfast.top/gh-proxy.com 已失效(000/404)，
        // 以下 4 个为实测可用（返回完整规则 JSON）：gh.sixyin.com 2.5s / ghproxy.net 1.05s /
        // github.tbap.top 0.75s(最快) / github.mxw.qzz.io 1.1s；失效自动被探测机制淘汰
        private const val GHFAST_BASE = "https://gh.sixyin.com"
        private const val GHPROXY_BASE = "https://ghproxy.net"
        private const val GHFAST_BASE_2 = "https://github.tbap.top"
        private const val GHPROXY_BASE_2 = "https://github.mxw.qzz.io"

        private const val FOLDER_LINK = "link"
        private const val FOLDER_TEXT = "text"
        private const val TIMEOUT_MS = 15_000
        private const val ACCEPT_HEADER = "application/vnd.github+json"
        private const val USER_AGENT = "HyperCopy"

        // v1.140.13 GitHub 通道失败冷却：连续失败 ≥2 次进入 120s 冷却，期间只走加速通道
        // v1.140.18 冷却状态已移至实例级（按源隔离），此处仅保留阈值常量
        private const val GITHUB_FAILURE_THRESHOLD = 2
        private const val GITHUB_COOLDOWN_MILLIS = 120_000L
        // v1.145.10 冷却持久化 key（按源隔离后缀）
        private const val KEY_FAILURES = "github_failures"
        private const val KEY_COOLDOWN_UNTIL = "github_cooldown_until"
    }
}
data class CloudRule(
    val name: String,
    val packageName: String,
    val fileName: String,
    val folder: String,
    val downloadUrl: String,
    val size: Long,
    val sourceKey: String = "",
    val acceleratedUrl: String? = null,
) {
    fun fileNameWithoutExt(): String =
        if (fileName.endsWith(".json", ignoreCase = true)) fileName.removeSuffix(".json").removeSuffix(".JSON") else fileName
    val category: RuleCategory
        get() = if (folder == "text") RuleCategory.Text else RuleCategory.Link

    /** v1.140.17 云端规则列表持久化：序列化 */
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("packageName", packageName)
        put("fileName", fileName)
        put("folder", folder)
        put("downloadUrl", downloadUrl)
        put("size", size)
        put("sourceKey", sourceKey)
        put("acceleratedUrl", acceleratedUrl ?: "")
    }
}

/** v1.140.17 云端规则列表持久化：反序列化（顶层函数形式） */
fun cloudRuleFromJson(obj: JSONObject): CloudRule = CloudRule(
    name = obj.optString("name"),
    packageName = obj.optString("packageName"),
    fileName = obj.optString("fileName"),
    folder = obj.optString("folder"),
    downloadUrl = obj.optString("downloadUrl"),
    size = obj.optLong("size"),
    sourceKey = obj.optString("sourceKey"),
    acceleratedUrl = obj.optString("acceleratedUrl").ifBlank { null },
)

enum class CloudRuleError {
    MissingDownloadUrl,
    LoadFailed,
    DownloadFailed,
    NetworkError,
}

class CloudRuleException(val error: CloudRuleError, cause: Throwable? = null) : Exception(error.name, cause)
