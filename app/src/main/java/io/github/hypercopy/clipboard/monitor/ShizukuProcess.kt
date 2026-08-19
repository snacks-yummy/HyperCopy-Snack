package io.github.hypercopy.clipboard.monitor

import io.github.hypercopy.HyperLog
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

object ShizukuProcess {
    private const val TAG = "HyperCopy"
    private const val NEW_PROCESS_TIMEOUT_MILLIS = 3_000L

    // v1.141.58 Bug C 修复：newProcess Binder 调用在 HyperOS 实测阻塞 ~15s，
    // 叠加调用方 waitForExit(5s) 共 20s 卡死剪贴板主流程。放独立线程 + 3s 超时兜底。
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HyperCopyShizukuNewProcess").apply { isDaemon = true }
    }

    fun start(command: Array<String>): Process? {
        val future: Future<Process?> = executor.submit(Callable<Process?> {
            runCatching {
                val m = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                )
                m.isAccessible = true
                m.invoke(null, command, null, null) as Process
            }.getOrElse { throwable ->
                HyperLog.d(TAG, "Shizuku newProcess reflection failed", throwable)
                null
            }
        })
        return try {
            future.get(NEW_PROCESS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            HyperLog.d(TAG, "Shizuku newProcess 超时(>${NEW_PROCESS_TIMEOUT_MILLIS}ms)或中断", e)
            future.cancel(true)
            null
        }
    }
}
