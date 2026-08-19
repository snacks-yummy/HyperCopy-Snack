package io.github.hypercopy.clipboard.handling

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.hypercopy.Config
import io.github.hypercopy.HyperLog
import io.github.hypercopy.R
import io.github.hypercopy.clipboard.jump.MiuiSuperIslandNotification
import io.github.hypercopy.clipboard.privileged.MiuiXmsfNetworkBlocker
import io.github.hypercopy.data.rules.RuleConfig
import io.github.hypercopy.data.settings.SettingsRepository
/**
 * v1.141 文本类通知统一门面（方案B，独立文本通知引擎）。
 *
 * 服务于"文本类"规则（RuleCategory.Text：取件码 NotifyOnly、短信验证码 ClipboardWrite 等）
 * 的自定义通知渠道，渠道类型与全局跳转通知一致（none/普通/实时/灵动岛），但**完全独立**：
 *  - 渠道来源：规则级 > 全局"文本类通知方式"(text_notification_mode) > 默认普通通知
 *  - 不读全局跳转渠道 jump_notification_mode
 *  - 独立 channel：hypercopy_text_*（不用 hypercopy_jump_*），避免关闭跳转渠道连带影响文本
 *  - 独立通知 ID：取件码 3002 / 验证码 3003（不混用，避免互相顶掉）
 *  - 灵动岛走完整 bypass 链路（Shizuku + 绕过灵动岛限制）
 */
object TextNotification {

    /**
     * v1.141.11 按官方《小米超级岛推送指南》规范修正：
     *  - 同一条岛通知的创建/更新/结束必须用同一个 notification id（官方 AndroidNotificationNotifyId 必填）。
     *  - 用固定 ID（取件码3002/验证码3003）+ updatable=true → 新内容 update 时 HyperOS 原位替换胶囊（取代、不堆积），实现"复制哪个弹哪个"。
     *  - 发送前 cancel 上一个 id + 官方参数 reopen=reopen → cancel 后重新发送仍显示（解决固定ID连发不弹的坑，官方默认 reopen=close 会不显示）。
     *  - 非灵动岛模式保持固定 ID。此前 v1.141.10 动态ID违背官方"同一订单同id"规范，已回退。
     */
    private val lastIslandIds = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 灵动岛独立自增 id：以规则基础 id(3002/3003) 为高位，低位递增，保证同 class 的每一条都是全新通知 id。 */
    private val islandSeq = java.util.concurrent.atomic.AtomicInteger(0)

    private fun nextIslandId(channelId: String, baseId: Int): Int {
        // 取件码/验证码各自高位不同（3002/3003），低位自增，互不撞。
        // (注：Notification id 需 int 有效，3002*10000+seq 控制在 int 范围内)
        return baseId * 10000 + (islandSeq.incrementAndGet() % 9999)
    }

    /** 解析最终渠道模式：规则级 notificationMode > 全局文本渠道 > 普通。 */
    fun resolveMode(context: Context, rule: RuleConfig?): String {
        val ruleMode = rule?.notificationMode
        if (!ruleMode.isNullOrBlank()) return ruleMode
        return SettingsRepository(context).readTextNotificationMode()
    }

    /**
     * 统一发送一条文本类通知。
     * @param entry title/content/packageName/icon 等展示信息
     * @param rule 携待规则（取规则级渠道）；null 则只用全局文本渠道
     * @param tag  调用方日志标识（如 "取件码"/"短信验证码"）
     * @return 是否真正发送（false = 无通知/none 渠道）
     */
    fun notify(context: Context, entry: TextNotificationEntry, rule: RuleConfig?, tag: String): Boolean {
        // Android 13+ 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            HyperLog.d(tag, "通知权限未授予, 跳过文本通知: ${entry.title}")
            return false
        }
        val mode = resolveMode(context, rule)
        if (mode == Config.JUMP_NOTIFICATION_MODE_NONE) {
            HyperLog.d(tag, "文本通知渠道=无通知, 跳过: ${entry.title}")
            return false
        }
        // 独立文本 channel（与跳转 channel 硬隔离）
        val channelId = Config.TEXT_NOTIFICATION_CHANNEL_PREFIX + mode
        val isIsland = mode == Config.JUMP_NOTIFICATION_MODE_MIUI_ISLAND
        // v1.141.15 取件码"连续不弹"修复：改用动态自增 ID。
        // 根因：固定 id(3002)+同 id 更新，HyperOS 在旧胶囊未收起时收到同 id 新内容会就地更新内容
        // 但**不再重新弹出胶囊**（updatable 只替换不重展），导致取件码内容长、触发频时被吞不弹。
        // 修法：灵动岛模式每条用独立自增 id（全新通知必弹 HyperOS），发送前 cancel 本类上一个 id
        // 避免通知栏无限堆积；取件码/验证码各自独立序列，互不干扰。
        val effectiveId = if (isIsland) {
            nextIslandId(channelId, entry.notificationId)
        } else {
            entry.notificationId
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nameRes = when (mode) {
                Config.JUMP_NOTIFICATION_MODE_MIUI_ISLAND -> R.string.notification_channel_jump_miui_island_name
                Config.JUMP_NOTIFICATION_MODE_LIVE -> R.string.notification_channel_jump_live_name
                else -> R.string.notification_channel_notify_only_name
            }
            // v1.141.77 渠道→重要性映射：灵动岛最高(MAX)、live 高(HIGH)、普通默认(DEFAULT)
            val channelImportance = when (mode) {
                Config.JUMP_NOTIFICATION_MODE_MIUI_ISLAND -> android.app.NotificationManager.IMPORTANCE_MAX
                Config.JUMP_NOTIFICATION_MODE_LIVE -> android.app.NotificationManager.IMPORTANCE_HIGH
                else -> android.app.NotificationManager.IMPORTANCE_DEFAULT
            }
            val channel = android.app.NotificationChannel(
                channelId,
                context.getString(nameRes),
                channelImportance,
            )
            val notifManager = context.getSystemService(android.app.NotificationManager::class.java)
            // v1.141.77 升级兼容：已存在 channel 的 importance 创建后只读，不一致时删除重建
            val existingChannel = notifManager.getNotificationChannel(channelId)
            if (existingChannel != null && existingChannel.importance != channelImportance) {
                notifManager.deleteNotificationChannel(channelId)
                HyperLog.d(tag, "文本通知渠道重要性变更, 重建 channel: $channelId ($existingChannel.importance -> $channelImportance)")
            }
            notifManager.createNotificationChannel(channel)
        }
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(entry.icon)
            .setContentTitle(entry.title)
            .setContentText(entry.content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(entry.content))
            // v1.141.77 渠道→优先级映射：灵动岛 MAX、live HIGH、普通 DEFAULT（不再固定 HIGH 打扰）
            .setPriority(
                when (mode) {
                    Config.JUMP_NOTIFICATION_MODE_MIUI_ISLAND -> NotificationCompat.PRIORITY_MAX
                    Config.JUMP_NOTIFICATION_MODE_LIVE -> NotificationCompat.PRIORITY_HIGH
                    else -> NotificationCompat.PRIORITY_DEFAULT
                }
            )
            .setAutoCancel(true)
            // v1.141.12 通知栏一键清除优化：明确非持续（ongoing=false）+ 消息语义，
            // 使灵动岛/文本通知在通知栏可用"全部清除"批量关闭，而不是只能右滑单条关闭。
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        val notification = builder.build()
        if (mode == Config.JUMP_NOTIFICATION_MODE_MIUI_ISLAND) {
            runCatching {
                MiuiSuperIslandNotification.apply(
                    context, notification, entry.title, entry.islandContent ?: entry.content, entry.packageName.orEmpty(), emptyList(),
                )
            }.onFailure { HyperLog.d(tag, "灵动岛extras失败: ${it.message}") }
        }
        // 退回 v1.141.12b：灵动岛走完整 focus + 断网 bypass 链路（v1.141.13 验证"纯普通通知不自动上岛"被证伪，需回退）。
        val shouldBypassIsland = mode == Config.JUMP_NOTIFICATION_MODE_MIUI_ISLAND &&
            SettingsRepository(context).readClipboardMonitorMode() == Config.CLIPBOARD_MONITOR_MODE_SHIZUKU &&
            SettingsRepository(context).readMiuiIslandBypassRestriction()
        val notificationManager = NotificationManagerCompat.from(context)
        runCatching {
            if (isIsland) {
                lastIslandIds[channelId]?.let { lastId ->
                    notificationManager.cancel(lastId)
                    // v1.141.16 冲 100%：拉长 cancel→notify 间隔，让 HyperOS 旧的收起动画/上岛动画完全走完，
                    // 避免新老胶囊抢同一时刻的岛动画被吞（剩余 ~5% 不弹的疑似根因）。
                    try { Thread.sleep(250L) } catch (_: InterruptedException) {}
                }
                lastIslandIds[channelId] = effectiveId
            }
            if (shouldBypassIsland) {
                MiuiXmsfNetworkBlocker.notifyWithTemporaryBlock(context) {
                    notificationManager.notify(effectiveId, notification)
                }
            } else {
                notificationManager.notify(effectiveId, notification)
            }
        }.onFailure { HyperLog.d(tag, "文本通知发送失败: ${it.message}") }
        // v1.141.16 能补的观测：发送后复核该 id 是否真实进入系统通知栏（getActiveNotifications），
        // 确认"app→系统"这段链路是否 100% 通。结果打日志便于测试定位。
        val inSystem = runCatching {
            val active = context.getSystemService(android.app.NotificationManager::class.java)
                .activeNotifications
            active.any { it.id == effectiveId }
        }.getOrElse { false }
        HyperLog.d(tag, "文本通知已发送: ${entry.title} mode=$mode bypass=$shouldBypassIsland id=$effectiveId inSystem=$inSystem | ${entry.content.take(80)}")
        return true
    }
}

/** 文本类通知的展示信息载体。 */
data class TextNotificationEntry(
    /** 独立通知 ID：取件码 3002 / 验证码 3003，不与其他规则混用 */
    val notificationId: Int,
    /** 通知标题（一般为规则名） */
    val title: String,
    /** 通知正文（提取结果/改写内容） */
    val content: String,
    /** 灵动岛目标包名（取件码/验证码规则 target.packageName） */
    val packageName: String?,
    /** 小图标资源 */
    val icon: Int,
    /** v1.141.87h 岛内专用正文：澎湃岛大岛 content 区域单行显示，超长截断；
     * 通知栏 BigText 仍用完整 content。null 时岛内回退用 content。 */
    val islandContent: String? = null,
)
