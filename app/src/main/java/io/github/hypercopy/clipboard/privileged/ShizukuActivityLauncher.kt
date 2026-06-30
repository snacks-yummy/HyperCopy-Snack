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
        // v1.139.7 异步启动：Shizuku newProcess/am start 在部分机型可能卡住（实测 4 分钟），
        // 同步等待会阻塞剪贴板处理主流程 → 期间所有复制无响应（多平台链接均受影响）。
        // 改为后台线程执行 + 立即返回，复制处理永不阻塞；结果日志由后台线程输出。
        kotlin.concurrent.thread(name = "HyperCopyShizukuLaunch") {
            runCatching {
                HyperLog.d(TAG, "Shizuku 启动Activity(异步)")
                val process = ShizukuProcess.start(arrayOf("sh", "-c", command))
                if (process == null) {
                    HyperLog.d(TAG, "Shizuku 启动Activity失败: newProcess null")
                    return@runCatching
                }
                val finished = waitForExit(process)
                if (!finished) {
                    process.destroyForcibly()
                    HyperLog.d(TAG, "Shizuku 启动Activity超时(后台)")
                    return@runCatching
                }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val success = process.exitValue() == 0 || output.indicatesActivityStarted()
                if (!success) HyperLog.d(TAG, "Shizuku 启动Activity失败: ${output.take(300)}")
                else HyperLog.d(TAG, "Shizuku 启动Activity完成: 成功")
            }.onFailure { throwable ->
                HyperLog.d(TAG, "Shizuku 启动Activity异常(后台)", throwable)
            }
        }
        // 立即返回，不阻塞剪贴板处理流程
        return true
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
