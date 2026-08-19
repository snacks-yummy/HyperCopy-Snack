package io.github.hypercopy

object Config {
    const val APPLICATION_ID = "io.github.hypercopy"
    const val PREFS_NAME = "hypercopy_settings"
    const val KEY_LOG_LEVEL = "log_level"
    // v1.141.39 日志缓冲条数：内存环形缓冲上限（日志 UI 展示窗口），档位 1000~50000
    const val KEY_LOG_BUFFER_MAX = "log_buffer_max"
    const val DEFAULT_LOG_BUFFER_MAX = 10_000
    const val MIN_LOG_BUFFER_MAX = 1_000
    const val MAX_LOG_BUFFER_MAX = 50_000
    const val KEY_AUTO_CHECK_UPDATE = "auto_check_update"
    const val KEY_HIDE_FROM_RECENTS = "hide_from_recents"
    const val KEY_APP_LANGUAGE = "app_language"
    const val KEY_COLOR_MODE = "color_mode"
    const val KEY_CLIPBOARD_MONITOR_MODE = "clipboard_monitor_mode"
    const val KEY_JUMP_NOTIFICATION_MODE = "jump_notification_mode"
    const val KEY_WEBVIEW_TIMEOUT_MILLIS = "webview_timeout_millis"
    const val KEY_MIUI_ISLAND_BYPASS_RESTRICTION = "miui_island_bypass_restriction"
    const val KEY_APP_LIST_WORK_MODE = "app_list_work_mode"
    const val KEY_IGNORE_JUMP_APP = "ignore_jump_app"
    const val KEY_DETECT_CLONED_APP = "detect_cloned_app"
    const val KEY_CLONED_APP_USER_ID = "cloned_app_user_id"
    const val KEY_SYSTEM_LINK_HANDLING = "system_link_handling"
    const val KEY_SYSTEM_LINK_CLEAR_CLIPBOARD_AFTER_JUMP = "system_link_clear_clipboard_after_jump"
    const val KEY_SYSTEM_LINK_USER_ID = "system_link_user_id"
    const val KEY_APP_LIST_PACKAGES = "app_list_packages"
    const val KEY_NOTIFY_UNMATCHED = "notify_unmatched"
    const val DEFAULT_NOTIFY_UNMATCHED = true
    const val KEY_MATCH_DEBUG_LOG = "match_debug_log"
    // v1.74 匹配调试日志默认开启（用户要求：新装即可诊断匹配问题）
    const val DEFAULT_MATCH_DEBUG_LOG = true
    const val KEY_ONBOARDING_DONE = "onboarding_done"
    // v1.30 方便快捷
    const val KEY_MONITOR_ENABLED = "monitor_enabled"
    const val DEFAULT_MONITOR_ENABLED = true
    const val KEY_SHOW_HIT_TOAST = "show_hit_toast"
    const val DEFAULT_SHOW_HIT_TOAST = true
    // v1.84 云端快递识别（默认关，隐私优先：仅本地识别不命中时可选上传单号到快递100）
    const val KEY_CLOUD_EXPRESS_DETECT = "cloud_express_detect"
    const val DEFAULT_CLOUD_EXPRESS_DETECT = false
    // v1.85 快递直达（默认开）：express 规则命中时直接跳转，忽略规则级通知模式
    const val KEY_EXPRESS_DIRECT_JUMP = "express_direct_jump"
    const val DEFAULT_EXPRESS_DIRECT_JUMP = true
    // v1.85 菜鸟查件自动确认（默认开）：直达菜鸟后无障碍自动点击官方弹窗确认
    const val KEY_CAINIAO_AUTO_CONFIRM = "cainiao_auto_confirm"
    const val DEFAULT_CAINIAO_AUTO_CONFIRM = true
    // v1.109 菜鸟详情页自动展开（默认开）：到达物流详情页后无障碍自动点击「展开」展示完整轨迹
    const val KEY_CAINIAO_AUTO_EXPAND = "cainiao_auto_expand"
    const val DEFAULT_CAINIAO_AUTO_EXPAND = true
    // v1.141.63 淘宝口令弹窗自动确认（默认开）：跳转淘宝后无障碍自动点击「查看详情/打开/进入店铺」
    const val KEY_TAOBAO_KOU_LING_CONFIRM = "taobao_kouling_confirm"
    const val DEFAULT_TAOBAO_KOU_LING_CONFIRM = true
    // v1.88 小米复制直达委托（默认开）
    const val KEY_EXPRESS_DELEGATE_AI_ENGINE = "express_delegate_ai_engine"
    const val DEFAULT_EXPRESS_DELEGATE_AI_ENGINE = false
    // v1.33 去重窗口可配置（毫秒，默认 1.5s）
    const val KEY_DUPLICATE_WINDOW_MILLIS = "duplicate_window_millis"
    const val DEFAULT_DUPLICATE_WINDOW_MILLIS = 1_500L
    // v1.38 启动自动激活（Shizuku 授权 + 无障碍开启）
    const val KEY_AUTO_ACTIVATE = "auto_activate"
    const val DEFAULT_AUTO_ACTIVATE = true
    // v1.33 场景规则集
    const val KEY_SCENE_GROUP = "scene_group" // 当前激活的场景分组（空=未激活）
    const val KEY_SCENE_BACKUP = "scene_backup" // 激活前的规则启用状态备份（JSON）
    // v1.126 跳转增强
    const val KEY_SCHEME_DIRECT_JUMP = "scheme_direct_jump"     // Scheme 直达优先（编辑器/规则模板）
    const val KEY_JUMP_FALLBACK_WEB = "jump_fallback_web"       // 跳转失败网页兜底
    const val KEY_JUMP_PRECHECK = "jump_precheck"               // 跳转前预检

    const val EXTRA_SUGGESTION_TEXT = "io.github.hypercopy.extra.SUGGESTION_TEXT"
    const val UNMATCHED_NOTIFICATION_ID = 3001
    const val UNMATCHED_NOTIFICATION_CHANNEL_ID = "hypercopy_unmatched"
    // v1.138 仅通知（取件码/取货码场景）
    // v1.141.17 清理：废弃常量 NOTIFY_ONLY_NOTIFICATION_ID / NOTIFY_ONLY_CHANNEL_ID 已删除，
    // 取件码通知自 v1.141 迁移到 TextNotification（动态 id + hypercopy_text_* channel），旧常量无引用残留。
    const val KEY_NOTIFY_PICKUP_CODE = "notify_pickup_code"           // 取件码通知总开关（默认开）
    const val KEY_NOTIFY_INCLUDE_PLATFORM = "notify_include_platform" // 通知显示平台名（默认开）
    const val KEY_NOTIFY_ONLY_RULE_V138 = "notify_only_rule_v138"     // v1.138 取件码规则 actionMode 迁移标记
    const val DEFAULT_NOTIFY_PICKUP_CODE = true
    const val DEFAULT_NOTIFY_INCLUDE_PLATFORM = true

    const val ACTION_HANDLE_CLIPBOARD_TEXT = "io.github.hypercopy.action.HANDLE_CLIPBOARD_TEXT"
    // 测试入口：adb shell am broadcast -a io.github.hypercopy.action.TEST_CLIPBOARD --es io.github.hypercopy.extra.CLIPBOARD_TEXT '<单号>'
    // 仅用于调试/自动化验证，绕过 mode 限制直接走处理链
    const val ACTION_TEST_CLIPBOARD = "io.github.hypercopy.action.TEST_CLIPBOARD"
    const val ACTION_CLEAR_CLIPBOARD = "io.github.hypercopy.action.CLEAR_CLIPBOARD"
    const val ACTION_CONFIRM_JUMP = "io.github.hypercopy.action.CONFIRM_JUMP"
    const val PERMISSION_CLEAR_CLIPBOARD = "io.github.hypercopy.permission.CLEAR_CLIPBOARD"
    const val EXTRA_CLIPBOARD_TEXT = "io.github.hypercopy.extra.CLIPBOARD_TEXT"
    const val EXTRA_CLIPBOARD_SOURCE = "io.github.hypercopy.extra.CLIPBOARD_SOURCE"
    const val EXTRA_PENDING_JUMP_ID = "io.github.hypercopy.extra.PENDING_JUMP_ID"
    const val EXTRA_PENDING_JUMP_USER_ID = "io.github.hypercopy.extra.PENDING_JUMP_USER_ID"
    // v1.50 剪贴板读取失败兜底：通知点击后前台处理剪贴板
    const val EXTRA_PROCESS_CLIPBOARD = "io.github.hypercopy.extra.PROCESS_CLIPBOARD"

    const val LOG_LEVEL_OFF = 0
    const val LOG_LEVEL_BASIC = 1
    const val LOG_LEVEL_DEBUG = 2

    const val APP_LANGUAGE_SYSTEM = "system"
    const val APP_LANGUAGE_ZH = "zh"
    const val APP_LANGUAGE_EN = "en"

    const val COLOR_MODE_SYSTEM = "system"
    const val COLOR_MODE_LIGHT = "light"
    const val COLOR_MODE_DARK = "dark"

    const val CLIPBOARD_MONITOR_MODE_LSPOSED = "lsposed"
    const val CLIPBOARD_MONITOR_MODE_SHIZUKU = "shizuku"

    const val JUMP_NOTIFICATION_MODE_NONE = "none"
    const val JUMP_NOTIFICATION_MODE_NORMAL = "normal"
    const val JUMP_NOTIFICATION_MODE_LIVE = "live"
    const val JUMP_NOTIFICATION_MODE_MIUI_ISLAND = "miui_island"

    // v1.141 文本类("文本类"规则：取件码/验证码)独立通知渠道，不随全局跳转渠道 jump_notification_mode
    const val KEY_TEXT_NOTIFICATION_MODE = "text_notification_mode"
    const val DEFAULT_TEXT_NOTIFICATION_MODE = JUMP_NOTIFICATION_MODE_NORMAL
    // 文本类通知独立 channel 前缀（与跳转 channel hypercopy_jump_* 硬隔离）
    const val TEXT_NOTIFICATION_CHANNEL_PREFIX = "hypercopy_text_"
    // 取件码/验证码独立通知 ID（混用 NOTIFY_ONLY_NOTIFICATION_ID 会导致互相顶掉）
    const val TEXT_NOTIFY_PICKUP_NOTIFICATION_ID = 3002  // 取件码(NotifyOnly)
    const val TEXT_NOTIFY_VERIFY_NOTIFICATION_ID = 3003  // 短信验证码(ClipboardWrite)

    const val APP_LIST_WORK_MODE_WHITELIST = "whitelist"
    const val APP_LIST_WORK_MODE_BLACKLIST = "blacklist"

    const val DEFAULT_LOG_LEVEL = LOG_LEVEL_BASIC
    const val DEFAULT_AUTO_CHECK_UPDATE = true
    const val DEFAULT_HIDE_FROM_RECENTS = false
    const val DEFAULT_APP_LANGUAGE = APP_LANGUAGE_SYSTEM
    const val DEFAULT_COLOR_MODE = COLOR_MODE_SYSTEM
    const val DEFAULT_CLIPBOARD_MONITOR_MODE = CLIPBOARD_MONITOR_MODE_SHIZUKU
    const val DEFAULT_JUMP_NOTIFICATION_MODE = JUMP_NOTIFICATION_MODE_NONE
    const val DEFAULT_MIUI_ISLAND_BYPASS_RESTRICTION = false
    const val DEFAULT_APP_LIST_WORK_MODE = APP_LIST_WORK_MODE_BLACKLIST
    const val DEFAULT_IGNORE_JUMP_APP = true
    const val DEFAULT_DETECT_CLONED_APP = true
    const val CLONED_APP_USER_AUTO = -1
    const val DEFAULT_CLONED_APP_USER_ID = CLONED_APP_USER_AUTO
    const val DEFAULT_SYSTEM_LINK_HANDLING = true
    const val DEFAULT_SYSTEM_LINK_CLEAR_CLIPBOARD_AFTER_JUMP = true
    const val DEFAULT_SYSTEM_LINK_USER_ID = 0

    const val KEY_CLOUD_SOURCE = "cloud_source"
    const val KEY_CUSTOM_CLOUD_SOURCES = "custom_cloud_sources"
    const val KEY_CLOUD_SOURCE_MIGRATED_V1391 = "cloud_source_migrated_v1391"
    const val CLOUD_SOURCE_GITHUB = "github"
    const val CLOUD_SOURCE_ACCELERATED = "accelerated"
    // v1.139.1 默认源改为源 key（作者 1812z）
    const val DEFAULT_CLOUD_SOURCE = "1812z"

    const val CLIPBOARD_TEXT_MAX_LENGTH = 16_384
}
