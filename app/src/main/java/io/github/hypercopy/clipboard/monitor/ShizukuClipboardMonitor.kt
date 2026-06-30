package io.github.hypercopy.clipboard.monitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.hypercopy.HyperLog
import io.github.hypercopy.R
object ShizukuClipboardMonitor {
    private const val TAG = "HyperCopy"

    private var detector: ShizukuLogcatClipboardDetector? = null
    private var probe: ClipboardChangeProbe? = null
    private var startGeneration = 0
    @Volatile
    private var currentStatus = Status.Stopped

    enum class Status {
        Checking,
        RunningShizuku,
        RunningReadLogs,
        PermissionDenied,
        Unavailable,
        Stopped,
    }

    fun start(context: Context, onStatusChanged: (Status) -> Unit = {}) {
        val appContext = context.applicationContext
        if (detector != null) {
            onStatusChanged(currentStatus)
            return
        }
        val generation = ++startGeneration
        updateStatus(Status.Checking, onStatusChanged)
        startProbe(appContext)
        ShizukuPermission.waitForAvailable { available ->
            if (generation != startGeneration) return@waitForAvailable
            if (available) {
                startWithShizuku(appContext, generation, onStatusChanged)
            } else {
                startWithReadLogsFallback(appContext, generation, onStatusChanged)
            }
        }
    }

    private fun startWithShizuku(appContext: Context, generation: Int, onStatusChanged: (Status) -> Unit) {
        if (detector != null) return
        ShizukuPermission.requestIfNeeded { granted ->
            if (generation != startGeneration) return@requestIfNeeded
            if (granted) {
                startDetector(appContext, generation, Status.RunningShizuku, onStatusChanged) { command -> ShizukuProcess.start(command) }
            } else {
                updateStatus(Status.PermissionDenied, onStatusChanged)
                showToast(appContext, R.string.toast_shizuku_permission_denied)
                HyperLog.d(TAG, "Shizuku permission denied")
            }
        }
    }

    private fun startWithReadLogsFallback(appContext: Context, generation: Int, onStatusChanged: (Status) -> Unit) {
        if (detector != null) return
        if (hasReadLogsPermission(appContext)) {
            startDetector(appContext, generation, Status.RunningReadLogs, onStatusChanged) { command -> Runtime.getRuntime().exec(command) }
        } else {
            updateStatus(Status.Unavailable, onStatusChanged)
            showToast(appContext, R.string.toast_shizuku_unavailable)
            HyperLog.d(TAG, "Shizuku unavailable and READ_LOGS not granted")
        }
    }

    fun stop() {
        startGeneration++
        detector?.stop()
        detector = null
        probe?.stop()
        probe = null
        currentStatus = Status.Stopped
        HyperLog.d(TAG, "stop Shizuku clipboard monitor")
    }

    private fun startProbe(context: Context) {
        if (probe != null) return
        probe = ClipboardChangeProbe(context).also { it.start() }
    }

    private fun startDetector(
        context: Context,
        generation: Int,
        runningStatus: Status,
        onStatusChanged: (Status) -> Unit,
        processStarter: (Array<String>) -> Process?,
    ) {
        if (detector != null) return
        detector = ShizukuLogcatClipboardDetector(
            packageName = context.packageName,
            processStarter = processStarter,
            onRunningChanged = { running ->
                if (generation == startGeneration) {
                    updateStatus(if (running) runningStatus else Status.Stopped, onStatusChanged)
                }
            },
        ) {
            // v1.140.11 恢复原版：logcat 嗅探到剪贴板访问日志 → 抢焦点读取 → 规则跳转
            // （无障碍已退出规则检测，无 isRunning 跳过逻辑，无需 force）
            ClipboardFocusRequester.request(context)
        }.also { it.start() }
    }

    private fun updateStatus(status: Status, onStatusChanged: (Status) -> Unit) {
        currentStatus = status
        onStatusChanged(status)
    }

    // v1.55 修复：授权回调可能在后台线程 → 切主线程防崩溃
    private fun showToast(context: Context, resId: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
        }
    }
    private fun hasReadLogsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
    }
}
