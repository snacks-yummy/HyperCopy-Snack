package io.github.hypercopy.clipboard.privileged

import android.content.Intent
import io.github.hypercopy.HyperLog
import io.github.hypercopy.clipboard.monitor.ShizukuPermission
import io.github.hypercopy.clipboard.monitor.ShizukuProcess

object ShizukuActivityLauncher {
    private const val TAG = "HyperCopy"
    private const val TIMEOUT_SECONDS = 5L

    fun launch(intent: Intent, userId: Int = 0): Boolean {
        if (!ShizukuPermission.isGranted()) return false
        val command = IntentAmStartCommand.build(intent, userId)
        // v1.141.58 Bug B 修复：同步执行 + 真实 exitValue 判断。
        // 此前异步线程 + 无条件 return true → 日志『启动结果: 成功』假阳性。
        // newProcess 已由 ShizukuProcess 3s 超时兜底，总阻塞上限 ~8s，不再卡死主流程。
        val process = runCatching {
            HyperLog.d(TAG, "Shizuku 启动Activity(同步)")
            ShizukuProcess.start(arrayOf("sh", "-c", command))
        }.getOrElse { throwable ->
            HyperLog.d(TAG, "Shizuku 启动Activity异常", throwable)
            return false
        }
        if (process == null) {
            HyperLog.d(TAG, "Shizuku 启动Activity失败: newProcess null")
            return false
        }
        val finished = waitForExit(process)
        if (!finished) {
            process.destroyForcibly()
            HyperLog.d(TAG, "Shizuku 启动Activity超时")
            return false
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val success = process.exitValue() == 0 || output.indicatesActivityStarted()
        if (!success) HyperLog.d(TAG, "Shizuku 启动Activity失败: ${output.take(300)}")
        else HyperLog.d(TAG, "Shizuku 启动Activity完成: 成功")
        return success
    }

    private fun waitForExit(process: Process): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000L
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
