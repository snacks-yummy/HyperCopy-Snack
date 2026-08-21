package io.github.hypercopy.ui.pages.systemlinkdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.hypercopy.HyperLog
import io.github.hypercopy.R
import io.github.hypercopy.data.rules.RuleActionMode
import io.github.hypercopy.data.rules.RuleCategory
import io.github.hypercopy.data.rules.RuleConfig
import io.github.hypercopy.data.rules.RuleRepository
import io.github.hypercopy.data.rules.RuleTarget
import io.github.hypercopy.data.rules.RuleTargetType
import io.github.hypercopy.data.systemlink.SystemLinkApp
import io.github.hypercopy.data.systemlink.SystemLinkDomain
import io.github.hypercopy.data.systemlink.SystemLinkRepository
import io.github.hypercopy.ui.components.PackageIcon
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.concurrent.thread

@Composable
fun SystemLinkAppDetailPage(
    packageName: String,
    userId: Int,
    appLabel: String,
    onBack: () -> Unit,
) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val mainHandler = remember { Handler(Looper.getMainLooper()) }
            val systemLinkRepository = remember { SystemLinkRepository(context.applicationContext) }
            var systemLinkApp by remember { mutableStateOf<SystemLinkApp?>(null) }
            var isLoading by remember { mutableStateOf(true) }

            fun loadApp(showLoading: Boolean = false) {
                if (showLoading) isLoading = true
                thread(name = "HyperCopySystemLinkDetail") {
                    val apps = runCatching { systemLinkRepository.readApps(userId) }
                        .getOrElse { throwable ->
                            HyperLog.d("HyperCopy", "load system links failed", throwable)
                            emptyList()
                        }
                    val app = apps.firstOrNull { it.packageName == packageName }
                    mainHandler.post {
                        systemLinkApp = app
                        isLoading = false
                    }
                }
            }

            LaunchedEffect(packageName) {
                loadApp(showLoading = true)
            }

            val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = appLabel.ifBlank { packageName },
                                largeTitle = appLabel.ifBlank { packageName },
                                scrollBehavior = scrollBehavior,
                                navigationIcon = {
                                    IconButton(onClick = onBack) {
                                        Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.action_back))
                                    }
                                },
                            )
                        },
                    ) { paddingValues ->
                        if (isLoading) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(paddingValues)
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(R.string.rule_system_link_title),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        } else {
                            val app = systemLinkApp
                            if (app == null) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(paddingValues)
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = stringResource(R.string.rule_system_empty_description),
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                                        .padding(paddingValues),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    item {
                                        AppInfoCard(app = app)
                                    }
                                    item {
                                        AppLinkAllowedCard(
                                            app = app,
                                            onEnabledChange = { enabled ->
                                                // v1.145.15 乐观 UI：立即更新本地状态（开关无延迟），后台 set + 重载校准
                                                systemLinkApp = systemLinkApp?.copy(linkHandlingAllowed = enabled)
                                                thread(name = "HyperCopySystemLinkAppToggle") {
                                                    runCatching { systemLinkRepository.setLinkHandlingAllowed(userId, app.packageName, enabled) }
                                                        .onFailure { HyperLog.d("HyperCopy", "toggle app system link failed", it) }
                                                    loadApp()
                                                }
                                            },
                                        )
                                    }
                                    // v1.145.15 系统链接升级：测试入口 + 一键生成链接规则
                                    item {
                                        SystemLinkActionsCard(
                                            app = app,
                                            userId = userId,
                                            repository = systemLinkRepository,
                                        )
                                    }
                                    if (app.domains.isNotEmpty()) {
                                        item {
                                            Text(
                                                text = stringResource(R.string.rule_system_app_link_allowed_summary),
                                                style = MiuixTheme.textStyles.body2,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                modifier = Modifier.padding(start = 4.dp),
                                            )
                                        }
                                        items(app.domains, key = { it.host }) { domain ->
                                            DomainCard(
                                                domain = domain,
                                                onEnabledChange = { enabled ->
                                                    // v1.145.15 乐观 UI：立即更新本地状态（开关无延迟），后台 set + 重载校准
                                                    systemLinkApp = systemLinkApp?.let { app ->
                                                        app.copy(
                                                            domains = app.domains.map {
                                                                if (it.host == domain.host) it.copy(enabled = enabled) else it
                                                            },
                                                        )
                                                    }
                                                    thread(name = "HyperCopySystemLinkDomainToggle") {
                                                        runCatching { systemLinkRepository.setDomainEnabled(userId, app.packageName, domain.host, enabled) }
                                                            .onFailure { HyperLog.d("HyperCopy", "toggle domain link failed", it) }
                                                        loadApp()
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
}

@Composable
private fun AppInfoCard(app: SystemLinkApp) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PackageIcon(packageName = app.packageName, modifier = Modifier.padding(end = 12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = app.label, style = MiuixTheme.textStyles.headline1)
                    // v1.145.15 已验证徽章：存在已验证域名 → 系统自动接管（无弹窗直达）
                    if (app.domains.any { it.state.equals("verified", ignoreCase = true) }) {
                        Text(
                            text = stringResource(R.string.app_verified_badge),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Text(
                    text = app.packageName,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * v1.145.15 系统链接操作卡：测试入口 + 一键生成链接规则。
 * 测试复用 repository.openLink（am start）；生成规则复用 saveRuleMerged 去重，
 * 与内置「淘宝 · 链接」格式同构（direct_open + \Q\E 域名正则）。
 */
@Composable
private fun SystemLinkActionsCard(
    app: SystemLinkApp,
    userId: Int,
    repository: SystemLinkRepository,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var testUrl by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.rule_system_actions_title), style = MiuixTheme.textStyles.headline1)
            TextField(
                value = testUrl,
                onValueChange = { testUrl = it },
                label = stringResource(R.string.rule_system_test_hint),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                text = stringResource(R.string.rule_system_test_button),
                enabled = testUrl.isNotBlank() && !testing,
                onClick = {
                    testing = true
                    thread(name = "HyperCopySystemLinkTest") {
                        val ok = runCatching { repository.openLink(userId, testUrl) }.getOrDefault(false)
                        mainHandler.post {
                            testing = false
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (ok) R.string.rule_system_test_started else R.string.rule_system_test_failed,
                                    userId.toString(),
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
            TextButton(
                text = stringResource(R.string.rule_system_create_rule),
                enabled = app.domains.any { it.host.isNotBlank() },
                onClick = {
                    thread(name = "HyperCopySystemLinkCreateRule") {
                        val rule = buildRuleFromSystemApp(app)
                        val result = RuleRepository(context.applicationContext).saveRuleMerged(rule)
                        // v1.145.15 诊断日志：生成规则结果（空域名/去重/成功定位）
                        HyperLog.d(
                            "HyperCopy",
                            "system link create rule: pkg=${app.packageName} label=${app.label} " +
                                "hosts=${app.domains.map { it.host }} result=$result name=${rule.name} regex=${rule.matchRegex.take(80)}",
                        )
                        val message = when (result) {
                            io.github.hypercopy.data.rules.RuleSaveResult.Duplicate ->
                                context.getString(R.string.rule_system_create_rule_duplicate, rule.name)
                            io.github.hypercopy.data.rules.RuleSaveResult.Rejected ->
                                context.getString(R.string.rule_system_create_rule_empty)
                            else -> context.getString(R.string.rule_system_create_rule_ok, rule.name)
                        }
                        mainHandler.post {
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }
    }
}

/** v1.145.15 由系统链接 App 生成链接规则：域名 \Q\E 字面量转义（同内置淘宝·链接），direct_open 直开原链接 */
private fun buildRuleFromSystemApp(app: SystemLinkApp): RuleConfig {
    val hosts = app.domains.map { it.host }.filter { it.isNotBlank() }.distinct()
    val matchRegex = if (hosts.isEmpty()) "" else ".*(?:" + hosts.joinToString("|") { Regex.escape(it) } + ").*"
    return RuleConfig(
        name = "${app.label} · 链接",
        category = RuleCategory.Link,
        actionMode = RuleActionMode.DirectOpen,
        matchRegex = matchRegex,
        parameterRegex = "",
        target = RuleTarget(
            type = RuleTargetType.Url,
            template = "",
            packageName = app.packageName,
            action = Intent.ACTION_VIEW,
        ),
        clearClipboardAfterJump = true,
    )
}

@Composable
private fun AppLinkAllowedCard(
    app: SystemLinkApp,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = stringResource(R.string.rule_system_app_link_allowed), style = MiuixTheme.textStyles.headline1)
                Text(
                    text = stringResource(R.string.rule_system_app_link_allowed_summary),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Switch(
                checked = app.linkHandlingAllowed,
                onCheckedChange = { onEnabledChange(!app.linkHandlingAllowed) },
            )
        }
    }
}

@Composable
private fun DomainCard(
    domain: SystemLinkDomain,
    onEnabledChange: (Boolean) -> Unit,
) {
    val isVerified = domain.state.equals("verified", ignoreCase = true)
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = domain.host, style = MiuixTheme.textStyles.headline1)
                Text(
                    // v1.68 verified 状态加说明文字（开关锁定原因一目了然）
                    text = if (isVerified) stringResource(R.string.domain_verified_hint) else domain.state,
                    style = MiuixTheme.textStyles.body2,
                    color = if (isVerified) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Switch(
                checked = if (isVerified) true else domain.enabled,
                enabled = !isVerified,
                onCheckedChange = {
                    if (!isVerified) onEnabledChange(!domain.enabled)
                },
            )
        }
    }
}
