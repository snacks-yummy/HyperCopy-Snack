package io.github.hypercopy.data.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit
import io.github.hypercopy.App
import io.github.hypercopy.Config
import io.github.libxposed.service.XposedService

class SettingsRepository(private val context: Context) {
    fun readLogLevel(): Int {
        return preferences().getInt(Config.KEY_LOG_LEVEL, Config.DEFAULT_LOG_LEVEL)
    }

    fun persistLogLevel(value: Int) {
        preferences().edit(commit = true) { putInt(Config.KEY_LOG_LEVEL, value) }
        syncLogLevelToLsposed(App.xposedService, value)
    }

    // v1.141.39 日志缓冲条数（内存环形缓冲上限，日志 UI 展示窗口）
    fun readLogBufferMax(): Int = preferences().getInt(Config.KEY_LOG_BUFFER_MAX, Config.DEFAULT_LOG_BUFFER_MAX)
    fun persistLogBufferMax(value: Int) {
        preferences().edit(commit = true) { putInt(Config.KEY_LOG_BUFFER_MAX, value) }
    }

    fun syncLogLevelToLsposed(service: XposedService?, value: Int = readLogLevel()) {
        if (service == null) return
        runCatching {
            service.getRemotePreferences(Config.PREFS_NAME)
                .edit()
                .putInt(Config.KEY_LOG_LEVEL, value)
                .commit()
        }
    }

    fun readAutoCheckUpdate(): Boolean {
        return preferences().getBoolean(Config.KEY_AUTO_CHECK_UPDATE, Config.DEFAULT_AUTO_CHECK_UPDATE)
    }

    fun persistAutoCheckUpdate(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_AUTO_CHECK_UPDATE, value) }
    }

    fun readHideFromRecents(): Boolean {
        return preferences().getBoolean(Config.KEY_HIDE_FROM_RECENTS, Config.DEFAULT_HIDE_FROM_RECENTS)
    }

    fun persistHideFromRecents(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_HIDE_FROM_RECENTS, value) }
    }

    fun readAppLanguage(): String {
        return preferences().getString(Config.KEY_APP_LANGUAGE, Config.DEFAULT_APP_LANGUAGE) ?: Config.DEFAULT_APP_LANGUAGE
    }

    fun persistAppLanguage(value: String) {
        preferences().edit(commit = true) { putString(Config.KEY_APP_LANGUAGE, value) }
    }

    fun readColorMode(): String {
        return preferences().getString(Config.KEY_COLOR_MODE, Config.DEFAULT_COLOR_MODE) ?: Config.DEFAULT_COLOR_MODE
    }

    fun persistColorMode(value: String) {
        preferences().edit(commit = true) { putString(Config.KEY_COLOR_MODE, value) }
    }

    fun readClipboardMonitorMode(): String {
        return preferences().getString(
            Config.KEY_CLIPBOARD_MONITOR_MODE,
            Config.DEFAULT_CLIPBOARD_MONITOR_MODE,
        ) ?: Config.DEFAULT_CLIPBOARD_MONITOR_MODE
    }

    fun persistClipboardMonitorMode(value: String) {
        preferences().edit(commit = true) { putString(Config.KEY_CLIPBOARD_MONITOR_MODE, value) }
    }

    fun readJumpNotificationMode(): String {
        return preferences().getString(
            Config.KEY_JUMP_NOTIFICATION_MODE,
            Config.DEFAULT_JUMP_NOTIFICATION_MODE,
        ) ?: Config.DEFAULT_JUMP_NOTIFICATION_MODE
    }

    /** Bug③修复：WebView 解析超时（毫秒），可配置。
     *  v1.141.64 默认 3000→8000：mt.cn→peisong→JS 拉 weixin:// 实测链路 3.4s+
     *  （01:17 日志 3424ms 触发 fallback 用原始 URL 启动 → 未一步到小程序），
     *  8000ms 覆盖慢网络下的完整链路，超时兜底仍保留（失败时用户等 8s 可接受）。 */
    fun readWebViewTimeoutMillis(): Long =
        preferences().getLong(Config.KEY_WEBVIEW_TIMEOUT_MILLIS, 8_000L)
    fun persistJumpNotificationMode(value: String) {
        preferences().edit(commit = true) { putString(Config.KEY_JUMP_NOTIFICATION_MODE, value) }
    }
    fun readMiuiIslandBypassRestriction(): Boolean {
        return preferences().getBoolean(
            Config.KEY_MIUI_ISLAND_BYPASS_RESTRICTION,
            Config.DEFAULT_MIUI_ISLAND_BYPASS_RESTRICTION,
        )
    }

    fun persistMiuiIslandBypassRestriction(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_MIUI_ISLAND_BYPASS_RESTRICTION, value) }
    }

    fun readAppListWorkMode(): String {
        return preferences().getString(
            Config.KEY_APP_LIST_WORK_MODE,
            Config.DEFAULT_APP_LIST_WORK_MODE,
        ) ?: Config.DEFAULT_APP_LIST_WORK_MODE
    }

    fun persistAppListWorkMode(value: String) {
        preferences().edit(commit = true) { putString(Config.KEY_APP_LIST_WORK_MODE, value) }
    }

    fun readIgnoreJumpApp(): Boolean {
        return preferences().getBoolean(Config.KEY_IGNORE_JUMP_APP, Config.DEFAULT_IGNORE_JUMP_APP)
    }

    fun persistIgnoreJumpApp(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_IGNORE_JUMP_APP, value) }
    }

    fun readDetectClonedApp(): Boolean {
        return preferences().getBoolean(Config.KEY_DETECT_CLONED_APP, Config.DEFAULT_DETECT_CLONED_APP)
    }

    fun persistDetectClonedApp(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_DETECT_CLONED_APP, value) }
    }

    fun readClonedAppUserId(): Int {
        return preferences().getInt(Config.KEY_CLONED_APP_USER_ID, Config.DEFAULT_CLONED_APP_USER_ID)
    }

    fun persistClonedAppUserId(value: Int) {
        preferences().edit(commit = true) { putInt(Config.KEY_CLONED_APP_USER_ID, value) }
    }

    fun readSystemLinkHandling(): Boolean {
        return preferences().getBoolean(Config.KEY_SYSTEM_LINK_HANDLING, Config.DEFAULT_SYSTEM_LINK_HANDLING)
    }

    fun persistSystemLinkHandling(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_SYSTEM_LINK_HANDLING, value) }
    }

    fun readSystemLinkClearClipboardAfterJump(): Boolean {
        return preferences().getBoolean(
            Config.KEY_SYSTEM_LINK_CLEAR_CLIPBOARD_AFTER_JUMP,
            Config.DEFAULT_SYSTEM_LINK_CLEAR_CLIPBOARD_AFTER_JUMP,
        )
    }

    fun persistSystemLinkClearClipboardAfterJump(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_SYSTEM_LINK_CLEAR_CLIPBOARD_AFTER_JUMP, value) }
    }

    fun readSystemLinkUserId(): Int {
        return preferences().getInt(Config.KEY_SYSTEM_LINK_USER_ID, Config.DEFAULT_SYSTEM_LINK_USER_ID)
    }

    fun persistSystemLinkUserId(value: Int) {
        preferences().edit(commit = true) { putInt(Config.KEY_SYSTEM_LINK_USER_ID, value) }
    }

    fun readAppListPackages(): Set<String> {
        return preferences().getStringSet(Config.KEY_APP_LIST_PACKAGES, emptySet()).orEmpty()
    }

    fun persistAppListPackages(value: Set<String>) {
        preferences().edit(commit = true) { putStringSet(Config.KEY_APP_LIST_PACKAGES, value) }
    }

    fun readNotifyUnmatched(): Boolean {
        return preferences().getBoolean(Config.KEY_NOTIFY_UNMATCHED, Config.DEFAULT_NOTIFY_UNMATCHED)
    }

    fun persistNotifyUnmatched(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_NOTIFY_UNMATCHED, value) }
    }
    // v1.138 取件码通知设置
    fun readNotifyPickupCode(): Boolean = preferences().getBoolean(Config.KEY_NOTIFY_PICKUP_CODE, Config.DEFAULT_NOTIFY_PICKUP_CODE)
    fun persistNotifyPickupCode(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_NOTIFY_PICKUP_CODE, value) }
    }
    fun readNotifyIncludePlatform(): Boolean = preferences().getBoolean(Config.KEY_NOTIFY_INCLUDE_PLATFORM, Config.DEFAULT_NOTIFY_INCLUDE_PLATFORM)
    fun persistNotifyIncludePlatform(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_NOTIFY_INCLUDE_PLATFORM, value) }
    }
    // v1.141 文本类("文本类"规则：取件码/验证码)独立通知渠道（none/normal/live/miui_island），默认普通通知，不随跳转渠道 jump_notification_mode
    fun readTextNotificationMode(): String = preferences().getString(
        Config.KEY_TEXT_NOTIFICATION_MODE,
        Config.DEFAULT_TEXT_NOTIFICATION_MODE,
    ) ?: Config.DEFAULT_TEXT_NOTIFICATION_MODE
    fun persistTextNotificationMode(value: String) {
        preferences().edit(commit = true) { putString(Config.KEY_TEXT_NOTIFICATION_MODE, value) }
    }

    fun readMatchDebugLog(): Boolean {
        return preferences().getBoolean(Config.KEY_MATCH_DEBUG_LOG, Config.DEFAULT_MATCH_DEBUG_LOG)
    }
    fun persistMatchDebugLog(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_MATCH_DEBUG_LOG, value) }
    }
    // v1.27 首次引导
    fun readOnboardingDone(): Boolean = preferences().getBoolean(Config.KEY_ONBOARDING_DONE, false)
    fun writeOnboardingDone() {
        preferences().edit(commit = true) { putBoolean(Config.KEY_ONBOARDING_DONE, true) }
    }
    // v1.77 重置首次引导标记（设置页"一键配置（重新运行）"用）
    fun resetOnboardingDone() {
        preferences().edit(commit = true) { remove(Config.KEY_ONBOARDING_DONE) }
    }
    // v1.30 方便快捷
    fun readMonitorEnabled(): Boolean = preferences().getBoolean(Config.KEY_MONITOR_ENABLED, Config.DEFAULT_MONITOR_ENABLED)
    fun persistMonitorEnabled(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_MONITOR_ENABLED, value) }
    }
    fun readShowHitToast(): Boolean = preferences().getBoolean(Config.KEY_SHOW_HIT_TOAST, Config.DEFAULT_SHOW_HIT_TOAST)
    fun persistShowHitToast(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_SHOW_HIT_TOAST, value) }
    }
    // v1.84 云端快递识别（默认关）
    fun readCloudExpressDetect(): Boolean = preferences().getBoolean(Config.KEY_CLOUD_EXPRESS_DETECT, Config.DEFAULT_CLOUD_EXPRESS_DETECT)
    fun persistCloudExpressDetect(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_CLOUD_EXPRESS_DETECT, value) }
    }
    // v1.85 快递直达（默认开）
    fun readExpressDirectJump(): Boolean = preferences().getBoolean(Config.KEY_EXPRESS_DIRECT_JUMP, Config.DEFAULT_EXPRESS_DIRECT_JUMP)
    fun persistExpressDirectJump(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_EXPRESS_DIRECT_JUMP, value) }
    }
    // v1.85 菜鸟查件自动确认（默认开）
    fun readCainiaoAutoConfirm(): Boolean = preferences().getBoolean(Config.KEY_CAINIAO_AUTO_CONFIRM, Config.DEFAULT_CAINIAO_AUTO_CONFIRM)
    fun persistCainiaoAutoConfirm(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_CAINIAO_AUTO_CONFIRM, value) }
    }
    // v1.109 菜鸟详情页自动展开（默认开）：到达详情页后自动点击「展开」展示完整轨迹
    fun readCainiaoAutoExpand(): Boolean = preferences().getBoolean(Config.KEY_CAINIAO_AUTO_EXPAND, Config.DEFAULT_CAINIAO_AUTO_EXPAND)
    fun persistCainiaoAutoExpand(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_CAINIAO_AUTO_EXPAND, value) }
    }
    // v1.88 小米复制直达委托（默认开）
    fun readExpressDelegateAiEngine(): Boolean = preferences().getBoolean(Config.KEY_EXPRESS_DELEGATE_AI_ENGINE, Config.DEFAULT_EXPRESS_DELEGATE_AI_ENGINE)
    fun persistExpressDelegateAiEngine(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_EXPRESS_DELEGATE_AI_ENGINE, value) }
    }
    // v1.95 委托废弃迁移标志（升级时强制关闭已废弃的 AI 引擎委托开关）
    fun readForceDelegateDisabled(): Boolean = preferences().getBoolean("force_delegate_disabled_v195", false)
    fun readLastActiveVersionCode(): Int = preferences().getInt("last_active_version_code", 0)
    fun persistLastActiveVersionCode(versionCode: Int) {
        preferences().edit(commit = true) { putInt("last_active_version_code", versionCode) }
    }
    fun persistForceDelegateDisabled() {
        preferences().edit(commit = true) { putBoolean("force_delegate_disabled_v195", true) }
    }
    // v1.38 启动自动激活
    fun readAutoActivate(): Boolean = preferences().getBoolean(Config.KEY_AUTO_ACTIVATE, Config.DEFAULT_AUTO_ACTIVATE)
    fun persistAutoActivate(value: Boolean) {
        preferences().edit(commit = true) { putBoolean(Config.KEY_AUTO_ACTIVATE, value) }
    }
    // v1.33 去重窗口（毫秒）
    fun readDuplicateWindowMillis(): Long = preferences().getLong(Config.KEY_DUPLICATE_WINDOW_MILLIS, Config.DEFAULT_DUPLICATE_WINDOW_MILLIS)
    fun persistDuplicateWindowMillis(value: Long) {
        preferences().edit(commit = true) { putLong(Config.KEY_DUPLICATE_WINDOW_MILLIS, value) }
    }
    // v1.33 场景规则集
    fun readSceneGroup(): String = preferences().getString(Config.KEY_SCENE_GROUP, "") ?: ""
    fun persistSceneGroup(value: String) {
        preferences().edit(commit = true) { putString(Config.KEY_SCENE_GROUP, value) }
    }
    fun readSceneBackup(): String = preferences().getString(Config.KEY_SCENE_BACKUP, "") ?: ""
    fun persistSceneBackup(value: String) {
        preferences().edit(commit = true) { putString(Config.KEY_SCENE_BACKUP, value) }
    }

    fun readDesktopIconHidden(): Boolean {
        return context.packageManager.getComponentEnabledSetting(desktopIconComponent()) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    fun persistDesktopIconHidden(value: Boolean) {
        val state = if (value) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        context.packageManager.setComponentEnabledSetting(
            desktopIconComponent(),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }

    fun readCloudSource(): String {
        val v = preferences().getString(Config.KEY_CLOUD_SOURCE, Config.DEFAULT_CLOUD_SOURCE)
            ?: Config.DEFAULT_CLOUD_SOURCE
        // v1.139.1 旧值兼容：accelerated/github 旧通道值归为作者源 key
        return when (v) {
            Config.CLOUD_SOURCE_ACCELERATED, Config.CLOUD_SOURCE_GITHUB -> "1812z"
            else -> v
        }
    }

    fun persistCloudSource(value: String) {
        preferences().edit(commit = true) { putString(Config.KEY_CLOUD_SOURCE, value) }
    }

    // ===== v1.139.1 自定义云端源持久化 =====
    private fun customSourcesJson(): String =
        preferences().getString(Config.KEY_CUSTOM_CLOUD_SOURCES, "[]") ?: "[]"

    fun readCustomCloudSources(): List<io.github.hypercopy.data.rules.CloudSourceConfig> {
        return runCatching {
            val arr = org.json.JSONArray(customSourcesJson())
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { add(io.github.hypercopy.data.rules.CloudSourceConfig.fromJson(it)) }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun addCustomCloudSource(source: io.github.hypercopy.data.rules.CloudSourceConfig) {
        val current = readCustomCloudSources().filterNot { it.key == source.key }
        val arr = org.json.JSONArray()
        (current + source).forEach { arr.put(it.toJson()) }
        preferences().edit(commit = true) { putString(Config.KEY_CUSTOM_CLOUD_SOURCES, arr.toString()) }
    }

    fun removeCustomCloudSource(key: String) {
        val arr = org.json.JSONArray()
        readCustomCloudSources().filterNot { it.key == key }.forEach { arr.put(it.toJson()) }
        preferences().edit(commit = true) { putString(Config.KEY_CUSTOM_CLOUD_SOURCES, arr.toString()) }
    }

    /**
     * v1.105 全部开关状态导出（结构化 key=value，供会话摘要/处理周期日志输出）。
     * 每次 handle 处理时输出当前开关快照，方便日志直接对照行为与配置。
     */
    fun dumpSettings(): String = buildString {
        append("monitor=${if (readMonitorEnabled()) "ON" else "OFF"}")
        append(" mode=${readClipboardMonitorMode()}")
        append(" autoConfirm=${if (readCainiaoAutoConfirm()) "ON" else "OFF"}")
        append(" autoExpand=${if (readCainiaoAutoExpand()) "ON" else "OFF"}")
        append(" expressDirect=${if (readExpressDirectJump()) "ON" else "OFF"}")
        append(" cloudDetect=${if (readCloudExpressDetect()) "ON" else "OFF"}")
        append(" systemLink=${if (readSystemLinkHandling()) "ON" else "OFF"}")
        append(" notifyUnmatched=${if (readNotifyUnmatched()) "ON" else "OFF"}")
        append(" hitToast=${if (readShowHitToast()) "ON" else "OFF"}")
        append(" matchDebug=${if (readMatchDebugLog()) "ON" else "OFF"}")
        append(" ignoreJumpApp=${if (readIgnoreJumpApp()) "ON" else "OFF"}")
        append(" appList=${readAppListWorkMode()}")
        append(" dupWindow=${readDuplicateWindowMillis()}ms")
        append(" autoActivate=${if (readAutoActivate()) "ON" else "OFF"}")
        append(" delegateAI=${if (readExpressDelegateAiEngine()) "ON" else "OFF"}")
        append(" webViewTimeout=${readWebViewTimeoutMillis()}ms")
        append(" monitorNotifications=${readJumpNotificationMode()}")
        append(" logLevel=${readLogLevel()}")
    }
        // ===== v1.126 跳转增强 =====
    fun readSchemeDirectJump(): Boolean = preferences().getBoolean(Config.KEY_SCHEME_DIRECT_JUMP, true)
    fun persistSchemeDirectJump(value: Boolean) { preferences().edit().putBoolean(Config.KEY_SCHEME_DIRECT_JUMP, value).apply() }
    fun readJumpFallbackWeb(): Boolean = preferences().getBoolean(Config.KEY_JUMP_FALLBACK_WEB, true)
    fun persistJumpFallbackWeb(value: Boolean) { preferences().edit().putBoolean(Config.KEY_JUMP_FALLBACK_WEB, value).apply() }
    fun readJumpPrecheck(): Boolean = preferences().getBoolean(Config.KEY_JUMP_PRECHECK, true)
    fun persistJumpPrecheck(value: Boolean) { preferences().edit().putBoolean(Config.KEY_JUMP_PRECHECK, value).apply() }

    private fun desktopIconComponent() = ComponentName(context.packageName, DESKTOP_ICON_ALIAS)

    private fun preferences() = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val DESKTOP_ICON_ALIAS = "io.github.hypercopy.ui.framework.MainActivityAlias"
    }
}
