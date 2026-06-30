package io.github.hypercopy.clipboard.monitor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import io.github.hypercopy.HyperLog

/** 无障碍服务工具：检测/引导开启/一键授权 */
object AccessibilityUtils {

    private const val TAG = "HyperCopy"

    /** 检测无障碍服务是否已在系统设置中开启 */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ClipboardAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { raw ->
            ComponentName.unflattenFromString(raw) == expected
        }
    }

    /**
     * 通过 Shizuku 写系统设置，一键启用/停用无障碍服务（免手动跳系统设置）。
     * 依赖 Shizuku 已授权（shell 身份拥有 WRITE_SECURE_SETTINGS）。
     *
     * @return true = 写入成功；false = Shizuku 不可用/失败（调用方应回退跳系统设置手动操作）
     */
    fun setEnabledViaShizuku(context: Context, enable: Boolean): Boolean {
        if (!ShizukuPermission.isGranted()) return false
        val component = ComponentName(context, ClipboardAccessibilityService::class.java).flattenToString()
        return runCatching {
            // ① 读取当前已启用列表
            val readProcess = ShizukuProcess.start(
                arrayOf("sh", "-c", "settings get secure enabled_accessibility_services"),
            ) ?: return false
            val current = readProcess.inputStream.bufferedReader().use { it.readText() }.trim().trim('"')
            if (!waitForExit(readProcess)) return false
            val entries = if (current.isBlank()) emptyList() else current.split(':').filter { it.isNotBlank() }
            // ② 计算目标列表
            val updated = if (enable) {
                if (entries.contains(component)) entries else entries + component
            } else {
                entries.filter { it != component }
            }
            val value = updated.joinToString(":")
            // ③ 写回
            val writeProcess = ShizukuProcess.start(
                arrayOf("sh", "-c", "settings put secure enabled_accessibility_services \"$value\""),
            ) ?: return false
            if (!waitForExit(writeProcess)) return false
            // ④ 开启时确保无障碍总开关打开
            if (enable) {
                val enableProcess = ShizukuProcess.start(
                    arrayOf("sh", "-c", "settings put secure accessibility_enabled 1"),
                )
                if (enableProcess != null) waitForExit(enableProcess)
            }
            HyperLog.d(TAG, "set accessibility via shizuku enable=$enable entries=${updated.size}")
            true
        }.getOrDefault(false)
    }

    /** 跳转系统无障碍设置页（引导用户开启/关闭） */
    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun waitForExit(process: Process): Boolean {
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            val exited = runCatching {
                process.exitValue()
                true
            }.getOrDefault(false)
            if (exited) return true
            runCatching { Thread.sleep(50L) }
        }
        return false
    }
}