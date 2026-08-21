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
        // v1.145.16 启动版本自校验：版本号+git commit，设备侧直接核对构建来源（防"装旧版无感知"）
        HyperLog.i("HyperCopy", "启动 v${BuildConfig.VERSION_NAME} commit=${BuildConfig.GIT_COMMIT}")
        // v1.142.7b 中文 UI 操作日志初始化（外部私有目录 Android/data/io.github.hypercopy/files/logs/）
        UiActionLogger.init(this)
        // v1.145.17 默认值迁移：更新检测 off / 云规则检测 7 天 / 自动检测关（仅一次，不覆盖用户后续选择）
        runCatching { SettingsRepository(this).migrateDefaultsV117() }
        // v1.145.16 外部备份自检必须在 ensureBuiltinRules 之前：数据被清后先恢复全量（含自定义），
        // 否则 ensureBuiltinRules 会先把空库补成仅内置并覆盖外部备份，自定义规则将无法找回
        runCatching { RuleRepository(this).ensureExternalBackup() }
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
