package io.github.hypercopy.data.rules

import android.content.Context
import io.github.hypercopy.Config
import org.json.JSONArray
import org.json.JSONObject

/** 最近跳转历史条目（v1.30） */
data class JumpHistoryEntry(
    val ruleName: String,
    val packageName: String,
    val timestamp: Long,
)

/**
 * 最近跳转历史（v1.30）：内存环形缓冲 + SharedPreferences 持久化（最多 50 条）。
 * 命中跳转时记录，统计页展示最近记录。
 */
class JumpHistoryRepository(private val context: Context) {
    private val maxEntries = 50

    fun record(ruleName: String, packageName: String) {
        val entries = read() + JumpHistoryEntry(ruleName, packageName, System.currentTimeMillis())
        persist(entries.takeLast(maxEntries))
    }

    /** v1.145.15 按包名统计跳转次数（系统链接 App 频率排序用），零存储成本（读 50 条内存统计） */
    fun countByPackage(): Map<String, Int> = read().groupingBy { it.packageName }.eachCount()

    fun read(): List<JumpHistoryEntry> = runCatching {
        val json = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_JUMP_HISTORY, null) ?: return emptyList()
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                add(
                    JumpHistoryEntry(
                        ruleName = obj.optString("ruleName"),
                        packageName = obj.optString("packageName"),
                        timestamp = obj.optLong("timestamp"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    fun clear() {
        context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_JUMP_HISTORY).apply()
    }

    private fun persist(entries: List<JumpHistoryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("ruleName", entry.ruleName)
                    .put("packageName", entry.packageName)
                    .put("timestamp", entry.timestamp),
            )
        }
        context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_JUMP_HISTORY, array.toString()).apply()
    }

    companion object {
        private const val KEY_JUMP_HISTORY = "jump_history"
    }
}