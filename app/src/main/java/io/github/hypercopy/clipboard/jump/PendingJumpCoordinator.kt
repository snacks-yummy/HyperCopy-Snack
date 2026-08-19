package io.github.hypercopy.clipboard.jump

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.hypercopy.AppIconCache
import io.github.hypercopy.Config
import io.github.hypercopy.HyperLog
import io.github.hypercopy.R
import io.github.hypercopy.clipboard.privileged.ActivityLaunchStrategy
import io.github.hypercopy.clipboard.privileged.MiuiXmsfNetworkBlocker
import io.github.hypercopy.clipboard.monitor.ClipboardFocusRequester
import io.github.hypercopy.data.settings.SettingsRepository
import io.github.hypercopy.data.systemlink.AndroidUser
import io.github.hypercopy.data.systemlink.SystemLinkRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

object PendingJumpCoordinator {
    private const val TAG = "HyperCopy"
    private const val NORMAL_CHANNEL_ID = "hypercopy_jump_normal"
    private const val LIVE_CHANNEL_ID = "hypercopy_jump_live"
    private const val MIUI_ISLAND_CHANNEL_ID = "hypercopy_jump_miui_island"
    private const val NOTIFICATION_ID = 2001
    private const val EXPIRE_MILLIS = 5_000L
    private const val CLIPBOARD_CLEAR_TIMEOUT_MILLIS = 500L
    private const val SHIZUKU_FOREGROUND_CLEAR_TIMEOUT_MILLIS = 1_200L
    private val handler = Handler(Looper.getMainLooper())
    private val clipboardClearHandler = Handler(HandlerThread("HyperCopyClipboardClear").apply { start() }.looper)
    private val notificationHandler = Handler(HandlerThread("HyperCopyJumpNotification").apply { start() }.looper)
    private val nextId = AtomicLong(1L)
    @Volatile
    private var pending: Entry? = null

    fun submit(context: Context, jump: PendingJump, clearClipboardAfterJump: Boolean = false, notificationModeOverride: String? = null) {
        val appContext = context.applicationContext
        // 功能⑨：规则级通知模式覆盖全局
        val notificationMode = notificationModeOverride ?: SettingsRepository(appContext).readJumpNotificationMode()
        if (notificationMode == Config.JUMP_NOTIFICATION_MODE_NONE) {
            launch(appContext, jump, clearClipboardAfterJump)
            return
        }
        if (!canPostNotification(appContext)) {
            HyperLog.d(TAG, "jump notification permission missing, launch directly")
            launch(appContext, jump, clearClipboardAfterJump)
            return
        }

        val id = nextId.getAndIncrement()
        val entry = Entry(id, jump, clearClipboardAfterJump)
        entry.expireRunnable = Runnable { expire(appContext, id) }
        pending?.cancel(appContext)
        pending = entry
        createChannel(appContext, notificationMode)
        notificationHandler.post { postNotification(appContext, entry, notificationMode) }
        if (jump is PendingJump.WebViewJump) {
            entry.preload = HeadlessWebViewResolver.preload(
                appContext,
                jump.url,
                jump.packageName,
                clearClipboardAfterJump,
            )
        }
        handler.postDelayed(entry.expireRunnable, EXPIRE_MILLIS)
    }

    fun confirm(context: Context, id: Long, selectedUserId: Int? = null) {
        val appContext = context.applicationContext
        val entry = pending ?: return
        if (entry.id != id) return
        pending = null
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
        handler.removeCallbacks(entry.expireRunnable)
        when (val jump = entry.jump) {
            is PendingJump.IntentJump -> launchAfterClipboardClear(appContext, entry.clearClipboardAfterJump || isEntrustIntent(jump)) {
                // v1.108 委托直达前置：官方 entrust 机制仅冷启动(onCreate)生效
                forceStopIfEntrust(jump)
                if (jump.packageName == io.github.hypercopy.clipboard.monitor.TaobaoKoulingConfirm.TAOBAO_PACKAGE) {
                    // v1.141.63 淘宝口令弹窗自动确认：跳转淘宝后标记启动无障碍扫描
                    io.github.hypercopy.clipboard.monitor.TaobaoKoulingConfirm.markTaobaoLaunch()
                }
                // v1.85 菜鸟查件自动确认：点击通知跳菜鸟同样记录（弹窗自动确认）
                if (jump.packageName == io.github.hypercopy.clipboard.monitor.CainiaoAutoConfirm.CAINIAO_PACKAGE) {
                    // v1.141.48 修复：markCainiaoLaunch 传纯单号（非整段文本），
                    // 保证冷启动补偿写回剪贴板的是单号而非整段短信（写回整段会被菜鸟当作查询内容，
                    // 且浮动窗口嗅探回读会触发"诊断不含单号"误判）
                    val trackNo = io.github.hypercopy.data.rules.ExpressCompanyDetector
                        .extractTrackingNumber(io.github.hypercopy.clipboard.handling.ClipboardTextHandler.lastProcessedText.orEmpty())
                        ?.uppercase()
                        ?: io.github.hypercopy.clipboard.handling.ClipboardTextHandler.lastProcessedText
                    io.github.hypercopy.clipboard.monitor.CainiaoAutoConfirm.markCainiaoLaunch(trackNo)
                }
                ActivityLaunchStrategy.launch(appContext, jump.intent, selectedUserId)
            }
            is PendingJump.WebViewJump -> entry.preload?.continueLaunch(appContext, selectedUserId)
                ?: HeadlessWebViewResolver.resolveAndLaunch(appContext, jump.url, jump.packageName, entry.clearClipboardAfterJump, selectedUserId)
            is PendingJump.SystemLinkJump -> launchAfterClipboardClear(appContext, entry.clearClipboardAfterJump) {
                SystemLinkRepository(appContext).openLink(selectedUserId ?: jump.userId, jump.url)
            }
        }
    }

    private fun expire(context: Context, id: Long) {
        val entry = pending ?: return
        if (entry.id != id) return
        pending = null
        entry.cancel(context)
        HyperLog.d(TAG, "jump notification expired")
    }

    private fun launch(context: Context, jump: PendingJump, clearClipboardAfterJump: Boolean) {
        val configuredUserId = selectedClonedAppUserId(context)
        when (jump) {
            is PendingJump.IntentJump -> launchAfterClipboardClear(context, clearClipboardAfterJump || isEntrustIntent(jump)) {
                // v1.100 诊断：确认跳转分支与菜鸟包名匹配
                HyperLog.d(TAG, "跳转Intent pkg=${jump.packageName} 目标菜鸟=${jump.packageName == io.github.hypercopy.clipboard.monitor.CainiaoAutoConfirm.CAINIAO_PACKAGE}")
                // v1.108 委托直达前置：官方 entrust 机制仅冷启动(onCreate)生效
                forceStopIfEntrust(jump)
                if (jump.packageName == io.github.hypercopy.clipboard.monitor.TaobaoKoulingConfirm.TAOBAO_PACKAGE) {
                    // v1.141.63 淘宝口令弹窗自动确认：直发跳转（全局通知模式 NONE 不经 confirm）同样标记
                    io.github.hypercopy.clipboard.monitor.TaobaoKoulingConfirm.markTaobaoLaunch()
                }
                // v1.85 菜鸟查件自动确认：记录直开菜鸟时间戳（无障碍据此识别官方弹窗并自动确认）
                if (jump.packageName == io.github.hypercopy.clipboard.monitor.CainiaoAutoConfirm.CAINIAO_PACKAGE) {
                    // v1.141.48 修复：同 confirm 分支，markCainiaoLaunch 传纯单号
                    val trackNo = io.github.hypercopy.data.rules.ExpressCompanyDetector
                        .extractTrackingNumber(io.github.hypercopy.clipboard.handling.ClipboardTextHandler.lastProcessedText.orEmpty())
                        ?.uppercase()
                        ?: io.github.hypercopy.clipboard.handling.ClipboardTextHandler.lastProcessedText
                    io.github.hypercopy.clipboard.monitor.CainiaoAutoConfirm.markCainiaoLaunch(trackNo)
                }
                // v1.126 跳转增强：Intent 预检 + 失败网页兜底
                // 预检（v1.126b 修复）：显式组件/包名 intent → 检查目标包是否安装（resolveActivity 在
                // MIUI 对显式 intent 会误判 null，导致菜鸟 entrust 等正常跳转被误判"不可达"）；
                // 仅隐式 intent（无包无组件）才用 resolveActivity 判断是否有处理器
                val jumpSettings = SettingsRepository(context.applicationContext)
                var intentToLaunch = jump.intent
                if (jumpSettings.readJumpPrecheck()) {
                    val pm = context.applicationContext.packageManager
                    val canResolve = when {
                        intentToLaunch.component != null ->
                            runCatching { pm.getPackageInfo(intentToLaunch.component!!.packageName, 0) }.isSuccess
                        intentToLaunch.`package` != null ->
                            runCatching { pm.getPackageInfo(intentToLaunch.`package`!!, 0) }.isSuccess
                        else -> runCatching { intentToLaunch.resolveActivity(pm) != null }.getOrDefault(true)
                    }
                    if (!canResolve) {
                        if (jumpSettings.readJumpFallbackWeb()) {
                            val searchText = io.github.hypercopy.clipboard.handling.ClipboardTextHandler.lastProcessedText
                            val query = searchText.takeIf { !it.isNullOrBlank() } ?: jump.title
                            intentToLaunch = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.baidu.com/s?wd=${Uri.encode(query)}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            HyperLog.d(TAG, "v1.126 预检: 目标App未安装, 网页兜底搜索 query=${query.take(40)}")
                        } else {
                            HyperLog.d(TAG, "v1.126 预检: 目标App未安装 pkg=${jump.packageName}, 已放弃(网页兜底关闭)")
                            return@launchAfterClipboardClear
                        }
                    }
                }
                val launchOk = ActivityLaunchStrategy.launch(context, intentToLaunch, configuredUserId)
                // v1.139.2c 诊断增强：明确记录启动结果（成功/失败），便于定位"无反应"问题
                HyperLog.d(TAG, "启动结果: ${if (launchOk) "成功" else "失败"} pkg=${jump.packageName} 网页兜底=${intentToLaunch.`package` != jump.packageName}")
            }
            is PendingJump.WebViewJump -> HeadlessWebViewResolver.resolveAndLaunch(
                context,
                jump.url,
                jump.packageName,
                clearClipboardAfterJump,
                configuredUserId,
            )
            is PendingJump.SystemLinkJump -> launchAfterClipboardClear(context, clearClipboardAfterJump) {
                val repository = SystemLinkRepository(context)
                val userId = configuredUserId ?: jump.userId
                if (jump.packageName.isBlank() || repository.isPackageInstalledForUser(userId, jump.packageName)) {
                    repository.openLink(userId, jump.url)
                }
            }
        }
    }

    private fun postNotification(context: Context, entry: Entry, notificationMode: String) {
        if (pending?.id != entry.id) return
        val actions = jumpActions(context, entry)
        HyperLog.d(TAG, "jump notification actions target=${entry.jump.packageName} count=${actions.size} titles=${actions.joinToString { it.title }}")
        if (pending?.id != entry.id) return
        val title = appLabel(context, entry.jump.packageName).ifBlank { context.getString(R.string.notification_jump_title) }
        val content = entry.jump.title.ifBlank { context.getString(R.string.notification_jump_text) }
        val appIcon = AppIconCache.loadNow(context, entry.jump.packageName)
        val builder = NotificationCompat.Builder(context, channelId(notificationMode))
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notification_jump_text)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(EXPIRE_MILLIS)
        if (appIcon != null) builder.setLargeIcon(appIcon)
        // Live notification mode: request Android promoted ongoing behavior.
        if (notificationMode == Config.JUMP_NOTIFICATION_MODE_LIVE) {
            builder.requestPromotedOngoing()
        }
        actions.forEach { action ->
            builder.addAction(android.R.drawable.ic_menu_view, action.title, action.pendingIntent)
        }
        val notification = builder
            .build()
            .apply { flags = flags or Notification.FLAG_ONGOING_EVENT }
        // Xiaomi Super Island mode: add MIUI focus extras before notify().
        if (notificationMode == Config.JUMP_NOTIFICATION_MODE_MIUI_ISLAND) {
            MiuiSuperIslandNotification.apply(context, notification, title, content, entry.jump.packageName, actions)
        }
        // Normal notification mode reaches notify() below without live or MIUI extras.
        val notificationManager = NotificationManagerCompat.from(context)
        val settingsRepository = SettingsRepository(context)
        val shouldBypassMiuiIslandRestriction = notificationMode == Config.JUMP_NOTIFICATION_MODE_MIUI_ISLAND &&
            settingsRepository.readClipboardMonitorMode() == Config.CLIPBOARD_MONITOR_MODE_SHIZUKU &&
            settingsRepository.readMiuiIslandBypassRestriction()
        if (shouldBypassMiuiIslandRestriction) {
            MiuiXmsfNetworkBlocker.notifyWithTemporaryBlock(context) {
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun jumpActions(context: Context, entry: Entry): List<JumpAction> {
        val jump = entry.jump
        val settingsRepository = SettingsRepository(context)
        if (jump.packageName.isNotBlank() && settingsRepository.readDetectClonedApp()) {
            val repository = SystemLinkRepository(context)
            val configuredUserId = settingsRepository.readClonedAppUserId()
            val users = repository.readUsers()
            val candidateUsers = if (configuredUserId == Config.CLONED_APP_USER_AUTO) {
                users
            } else {
                users.filter { it.id == 0 || it.id == configuredUserId }
            }
            val availableUsers = candidateUsers.filter { user ->
                repository.isPackageInstalledForUser(user.id, jump.packageName)
            }
            HyperLog.d(TAG, "cloned app check target=${jump.packageName} users=${availableUsers.joinToString { it.id.toString() }}")
            if (availableUsers.size > 1) {
                return availableUsers.map { user ->
                    JumpAction(userActionTitle(context, user.id, users), confirmPendingIntent(context, entry.id, user.id))
                }
            }
            val defaultUserId = (jump as? PendingJump.SystemLinkJump)?.userId ?: 0
            if (availableUsers.size == 1 && availableUsers.first().id != defaultUserId) {
                return listOf(JumpAction(context.getString(R.string.action_jump), confirmPendingIntent(context, entry.id, availableUsers.first().id)))
            }
        }
        return listOf(JumpAction(context.getString(R.string.action_jump), confirmPendingIntent(context, entry.id, null)))
    }

    private fun userActionTitle(context: Context, userId: Int, users: List<AndroidUser>): String {
        if (userId == 0) return context.getString(R.string.action_jump_main_user)
        val name = users.firstOrNull { it.id == userId }?.name.orEmpty()
            .ifBlank { context.getString(R.string.action_jump_cloned_user) }
        return context.getString(R.string.action_jump_user_format, name, userId)
    }

    private fun selectedClonedAppUserId(context: Context): Int? {
        val settingsRepository = SettingsRepository(context.applicationContext)
        if (!settingsRepository.readDetectClonedApp()) return null
        return settingsRepository.readClonedAppUserId().takeIf { it != Config.CLONED_APP_USER_AUTO }
    }

    private fun appLabel(context: Context, packageName: String): String {
        if (packageName.isBlank()) return ""
        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    private fun confirmPendingIntent(context: Context, id: Long, userId: Int?): PendingIntent {
        val actionIntent = Intent(context, JumpConfirmReceiver::class.java).apply {
            action = Config.ACTION_CONFIRM_JUMP
            putExtra(Config.EXTRA_PENDING_JUMP_ID, id)
            if (userId != null) putExtra(Config.EXTRA_PENDING_JUMP_USER_ID, userId)
        }
        val requestCode = (id * 10 + (userId ?: 0)).toInt()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

        internal fun clearClipboardIfNeeded(context: Context, clearClipboardAfterJump: Boolean) {
        if (!clearClipboardAfterJump) return
        if (clearClipboardByLsposed(context)) return
        writeEmptyClipboardByApp(context)
    }

    /**
     * v1.108 委托直达前置：菜鸟官方 entrust 机制仅在冷启动(onCreate)处理委托 extras，
     * 热启动(onNewIntent)会丢失委托 → 跳转前 force-stop 菜鸟保证 onCreate 生效。
     * 仅对含 url+from=entrust 的委托 Intent 生效（普通跳转不受影响，保留热启动弹窗兜底）。
     * v1.109 时序修复：am force-stop 异步返回，进程可能未死透就启动导致热启动丢委托（实测偶发落首页），
     * force-stop 后轮询 pidof 直到进程完全退出（最多 2s）再继续启动。
     * v1.112 热启动优化：逆向确认 onNewIntent 也调用 startEntrustActivity（HomePageActivity.java:1949）——
     * 菜鸟进程存活时直接热启动（无 logo 秒达详情页）；仅进程已死时才 force-stop 保证冷启动 onCreate。
     */
    private fun forceStopIfEntrust(jump: PendingJump.IntentJump) {
        if (!isEntrustIntent(jump)) return
        if (isCainiaoProcessAlive()) {
            // v1.112 进程存活 → 热启动：onNewIntent 处理委托直达详情页，无冷启动 logo
            HyperLog.d(TAG, "委托直达: 菜鸟进程存活, 热启动直达(无logo)")
            return
        }
        HyperLog.d(TAG, "委托直达: 菜鸟进程已死, force-stop 保证冷启动 onCreate 处理委托")
        runCatching {
            io.github.hypercopy.clipboard.monitor.ShizukuProcess.start(
                arrayOf("am", "force-stop", io.github.hypercopy.clipboard.monitor.CainiaoAutoConfirm.CAINIAO_PACKAGE)
            )?.waitFor()
        }.onFailure { HyperLog.d(TAG, "委托直达 force-stop 失败", it) }
        waitCainiaoProcessExit()
    }

    /**
     * v1.141.52 判断菜鸟委托直达 Intent（extras url+from=entrust）。
     * 委托直达用 extras 传单号不依赖剪贴板 → 跳转前清剪贴板安全，
     * 且能根治菜鸟 JS 检测剪贴板残留单号弹「是否要查询包裹」（20:49 实锤偶发弹窗+展开收起）。
     */
    private fun isEntrustIntent(jump: PendingJump.IntentJump): Boolean =
        jump.intent.getStringExtra(io.github.hypercopy.clipboard.monitor.CainiaoAutoConfirm.ENTRUST_EXTRA_URL) != null &&
            jump.intent.getStringExtra(io.github.hypercopy.clipboard.monitor.CainiaoAutoConfirm.ENTRUST_EXTRA_FROM) ==
            io.github.hypercopy.clipboard.monitor.CainiaoAutoConfirm.ENTRUST_VALUE_FROM

    /** v1.112 菜鸟进程是否存活（pidof 判定） */
    private fun isCainiaoProcessAlive(): Boolean {
        return runCatching {
            val proc = io.github.hypercopy.clipboard.monitor.ShizukuProcess.start(
                arrayOf("pidof", io.github.hypercopy.clipboard.monitor.CainiaoAutoConfirm.CAINIAO_PACKAGE)
            )
            val out = proc?.inputStream?.bufferedReader()?.readText()?.trim().orEmpty()
            proc?.waitFor()
            out.isNotBlank()
        }.getOrDefault(false)
    }
    /** v1.109 force-stop 后轮询 pidof 直到菜鸟进程完全退出（最多 2s），保证委托冷启动 onCreate 生效 */
    private fun waitCainiaoProcessExit() {
        val deadline = System.currentTimeMillis() + 2_000L
        var exited = false
        while (System.currentTimeMillis() < deadline) {
            val alive = runCatching {
                val proc = io.github.hypercopy.clipboard.monitor.ShizukuProcess.start(
                    arrayOf("pidof", io.github.hypercopy.clipboard.monitor.CainiaoAutoConfirm.CAINIAO_PACKAGE)
                )
                val out = proc?.inputStream?.bufferedReader()?.readText()?.trim().orEmpty()
                proc?.waitFor()
                out.isNotBlank()
            }.getOrDefault(false)
            if (!alive) {
                exited = true
                break
            }
            runCatching { Thread.sleep(100) }
        }
        HyperLog.d(
            TAG,
            if (exited) "委托直达: 菜鸟进程已退出, 冷启动就绪" else "委托直达: 等待进程退出超时(2s), 继续启动(兜底弹窗)"
        )
    }

    internal fun launchAfterClipboardClear(context: Context, clearClipboardAfterJump: Boolean, launch: () -> Unit) {
        if (!clearClipboardAfterJump) {
            launch()
            return
        }
        if (clearClipboardByLsposed(context)) {
            launch()
            return
        }
        if (launchAfterShizukuForegroundClear(context, launch)) return
        clearClipboardIfNeeded(context, clearClipboardAfterJump)
        launch()
    }

    private fun clearClipboardByLsposed(context: Context): Boolean {
        // v1.140.10 放宽模式检查: LSPOSED 清理通道可用即优先使用(与 Xposed 检测通道一致)
        val latch = CountDownLatch(1)
        var cleared = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                cleared = resultCode == android.app.Activity.RESULT_OK
                latch.countDown()
            }
        }
        return runCatching {
            context.applicationContext.sendOrderedBroadcast(
                Intent(Config.ACTION_CLEAR_CLIPBOARD)
                    .setPackage("android")
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                Config.PERMISSION_CLEAR_CLIPBOARD,
                receiver,
                clipboardClearHandler,
                android.app.Activity.RESULT_CANCELED,
                null,
                null,
            )
            latch.await(CLIPBOARD_CLEAR_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            if (cleared) HyperLog.d(TAG, "clipboard cleared by LSPosed") else HyperLog.d(TAG, "LSPosed clipboard clear did not complete")
            cleared
        }.getOrElse { throwable ->
            HyperLog.d(TAG, "LSPosed clipboard clear exception", throwable)
            false
        }
    }

    private fun launchAfterShizukuForegroundClear(context: Context, launch: () -> Unit): Boolean {
        if (SettingsRepository(context.applicationContext).readClipboardMonitorMode() != Config.CLIPBOARD_MONITOR_MODE_SHIZUKU) return false
        var completed = false
        val token = ClipboardFocusRequester.requestClear(context.applicationContext) { success ->
            if (completed) return@requestClear
            completed = true
            if (success) {
                HyperLog.d(TAG, "clipboard replaced with empty text by Shizuku foreground")
            } else {
                writeEmptyClipboardByApp(context)
            }
            launch()
        } ?: return false
        handler.postDelayed({
            if (completed) return@postDelayed
            completed = true
            ClipboardFocusRequester.cancelClearToken(token)
            HyperLog.d(TAG, "Shizuku foreground clipboard clear timed out")
            writeEmptyClipboardByApp(context)
            launch()
        }, SHIZUKU_FOREGROUND_CLEAR_TIMEOUT_MILLIS)
        return true
    }

    private fun writeEmptyClipboardByApp(context: Context) {
        val clipboard = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        HyperLog.d(TAG, "clipboard replaced with empty text by app")
    }

    private fun createChannel(context: Context, notificationMode: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val (nameRes, descriptionRes) = when (notificationMode) {
            Config.JUMP_NOTIFICATION_MODE_MIUI_ISLAND -> R.string.notification_channel_jump_miui_island_name to R.string.notification_channel_jump_miui_island_description
            Config.JUMP_NOTIFICATION_MODE_NORMAL -> R.string.notification_channel_jump_normal_name to R.string.notification_channel_jump_normal_description
            else -> R.string.notification_channel_jump_live_name to R.string.notification_channel_jump_live_description
        }
        val channel = NotificationChannel(
            channelId(notificationMode),
            context.getString(nameRes),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(descriptionRes)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun channelId(notificationMode: String): String {
        return when (notificationMode) {
            Config.JUMP_NOTIFICATION_MODE_MIUI_ISLAND -> MIUI_ISLAND_CHANNEL_ID
            Config.JUMP_NOTIFICATION_MODE_NORMAL -> NORMAL_CHANNEL_ID
            else -> LIVE_CHANNEL_ID
        }
    }

    private fun NotificationCompat.Builder.requestPromotedOngoing(): NotificationCompat.Builder {
        runCatching {
            javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(this, true)
        }.onFailure {
            extras.putBoolean("android.requestPromotedOngoing", true)
        }
        return this
    }

    private fun canPostNotification(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private data class Entry(
        val id: Long,
        val jump: PendingJump,
        val clearClipboardAfterJump: Boolean,
        var preload: HeadlessWebViewResolver.Preload? = null,
        var expireRunnable: Runnable = Runnable {},
    ) {
        fun cancel(context: Context) {
            handler.removeCallbacks(expireRunnable)
            preload?.cancel()
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    data class JumpAction(
        val title: String,
        val pendingIntent: PendingIntent,
    )
}
