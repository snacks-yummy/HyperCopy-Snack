package io.github.hypercopy.data.rules

import android.content.Context
import io.github.hypercopy.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 规则命中统计（SharedPreferences 存储，规则删除时自动清理） */
class RuleStatsRepository(private val context: Context) {
    private val prefs
        get() = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)

    private val _changeSignal = MutableStateFlow(0)
    /** 统计变化信号：UI 收集后重组刷新命中数显示 */
    val changeSignal: StateFlow<Int> = _changeSignal.asStateFlow()

    fun increment(ruleId: String) {
        // 全局锁：无障碍回调/LSPosed 广播/悬浮窗线程可能并发命中同一规则，
        // 读-改-写必须原子（SharedPreferences 无 CAS，实例锁无效——每次 handle 都 new 实例）
        synchronized(lock) {
            val current = get(ruleId)
            prefs.edit().putInt(keyOf(ruleId), current + 1).apply()
            _changeSignal.value++
        }
    }

    fun get(ruleId: String): Int = prefs.getInt(keyOf(ruleId), 0)

    fun clear(ruleId: String) {
        prefs.edit().remove(keyOf(ruleId)).apply()
    }
    /** 全部规则的命中计数（ruleId -> count），v1.25 全局统计 */
    fun getAll(): Map<String, Int> = prefs.all
        .filterKeys { it.startsWith(KEY_PREFIX) }
        .mapKeys { it.key.removePrefix(KEY_PREFIX) }
        .mapValues { (_, v) -> (v as? Int) ?: 0 }

    /** 清理已删除规则的统计（避免无限增长） */
    fun prune(existingIds: Set<String>) {
        val stale = prefs.all.keys.filter { it.startsWith(KEY_PREFIX) && it.removePrefix(KEY_PREFIX) !in existingIds }
        if (stale.isNotEmpty()) {
            val editor = prefs.edit()
            stale.forEach { editor.remove(it) }
            editor.apply()
        }
    }

    private fun keyOf(ruleId: String) = KEY_PREFIX + ruleId

    private companion object {
        const val KEY_PREFIX = "rule_hit_"
        /** 全局统计锁：跨实例共享（increment 原子性） */
        private val lock = Any()
    }
}
