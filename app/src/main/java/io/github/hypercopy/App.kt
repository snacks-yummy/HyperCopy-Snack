package io.github.hypercopy

import android.app.Application
import io.github.hypercopy.clipboard.monitor.ClipboardMonitorController
import io.github.hypercopy.data.rules.RuleRepository
import io.github.hypercopy.data.settings.SettingsRepository
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

class App : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        HyperLog.init(this)
        // v1.142.7b 中文 UI 操作日志初始化（外部私有目录 Android/data/io.github.hypercopy/files/logs/）
        UiActionLogger.init(this)
        // 内置云规则：开箱即用，无需手动下载
        runCatching { RuleRepository(this).ensureBuiltinRules() }
        XposedServiceHelper.registerListener(this)
        ClipboardMonitorController.startForCurrentMode(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        SettingsRepository(this).syncLogLevelToLsposed(service)
        listeners.forEach { it(service) }
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService == service) {
            xposedService = null
            listeners.forEach { it(null) }
        }
    }

    companion object {
        @Volatile
        var xposedService: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()

        fun addServiceListener(listener: (XposedService?) -> Unit) {
            listeners.add(listener)
            listener(xposedService)
        }

        fun removeServiceListener(listener: (XposedService?) -> Unit) {
            listeners.remove(listener)
        }
    }
}
