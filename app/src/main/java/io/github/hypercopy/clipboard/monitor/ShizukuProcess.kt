package io.github.hypercopy.clipboard.monitor

import io.github.hypercopy.HyperLog

object ShizukuProcess {
    private const val TAG = "HyperCopy"

    fun start(command: Array<String>): Process? {
        return runCatching {
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
    }
}
