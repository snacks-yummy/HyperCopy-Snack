package io.github.hypercopy.ui.components
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hypercopy.R
import io.github.hypercopy.data.rules.CloudSourceConfig
import io.github.hypercopy.data.rules.CloudSourceRegistry
import io.github.hypercopy.data.rules.displayNameText
import io.github.hypercopy.data.rules.descriptionText
import io.github.hypercopy.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * v1.139.1 云端规则源公共管理对话框（云端规则页 + 设置页共用）：
 * - 源列表（内置源 + 自定义源，当前源打勾，自定义可删除）
 * - 添加源（粘贴 GitHub 链接或加速站链接，可达性校验后自动切换）
 */
@Composable
fun CloudSourceManagerDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    currentSourceKey: String,
    settingsRepository: SettingsRepository,
    onSourceChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var allSources by remember { mutableStateOf(CloudSourceRegistry.allSources(context)) }
    var showAddDialog by remember { mutableStateOf(false) }
    // v1.140.18 人性化返回：BACK 关闭源管理对话框 / 添加源对话框
    BackHandler(enabled = show) { onDismiss() }
    if (show) {
        WindowDialog(
            title = stringResource(R.string.cloud_source_title),
            show = show,
            onDismissRequest = onDismiss,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                allSources.forEach { src ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                if (src.key != currentSourceKey) {
                                    onSourceChange(src.key)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = src.displayNameText() + if (src.isBuiltin) "" else " ★",
                            style = MiuixTheme.textStyles.body1,
                            color = if (src.key == currentSourceKey) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                        )
                        // v1.140.18 源说明：注明原作者 / 授权信息（v1.142.6e 资源化）
                        val srcDesc = src.descriptionText()
                        if (srcDesc.isNotBlank()) {
                            Text(
                                text = srcDesc,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                    }
                        if (src.key == currentSourceKey) {
                            Text(
                                text = "✓",
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                        if (!src.isBuiltin) {
                            TextButton(
                                text = stringResource(R.string.action_delete),
                                onClick = {
                                    settingsRepository.removeCustomCloudSource(src.key)
                                    allSources = CloudSourceRegistry.allSources(context)
                                    if (currentSourceKey == src.key) onSourceChange(CloudSourceRegistry.AUTHOR.key)
                                },
                            )
                        }
                    }
                }
                TextButton(
                    text = stringResource(R.string.cloud_source_add),
                    onClick = { showAddDialog = true },
                )
            }
        }
    }
    if (showAddDialog) {
        var sourceInput by remember { mutableStateOf("") }
        // v1.140.18 人性化返回：BACK 关闭添加源对话框
        BackHandler(enabled = true) { showAddDialog = false }
        WindowDialog(
            title = stringResource(R.string.cloud_source_add_title),
            show = showAddDialog,
            onDismissRequest = { showAddDialog = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.cloud_source_add_hint),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                TextField(
                    value = sourceInput,
                    onValueChange = { sourceInput = it },
                    label = stringResource(R.string.cloud_source_add_placeholder),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = { showAddDialog = false },
                    )
                    TextButton(
                        text = stringResource(R.string.action_confirm),
                        onClick = {
                            val url = sourceInput.trim()
                            val gh = CloudSourceRegistry.parseGitHubUrl(url)
                            val accel = if (gh == null) CloudSourceRegistry.parseAcceleratedUrl(url) else null
                            val cfg: CloudSourceConfig? = when {
                                gh != null -> {
                                    val (owner, repo) = gh
                                    CloudSourceConfig(
                                        key = CloudSourceRegistry.customKey(owner, repo),
                                        displayName = "$owner/$repo",
                                        repoOwner = owner,
                                        repoName = repo,
                                        isBuiltin = false,
                                    )
                                }
                                accel != null -> {
                                    val host = accel.substringAfter("//").substringBefore("/")
                                    CloudSourceConfig(
                                        key = CloudSourceRegistry.customKey("accel", host),
                                        displayName = host,
                                        repoOwner = "",
                                        repoName = "",
                                        acceleratedBase = accel,
                                        isBuiltin = false,
                                    )
                                }
                                else -> null
                            }
                            if (cfg == null) {
                                Toast.makeText(context, R.string.cloud_source_add_invalid, Toast.LENGTH_SHORT).show()
                            } else {
                                scope.launch {
                                    val reachable = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val probe = if (cfg.repoOwner.isNotBlank()) {
                                                "https://api.github.com/repos/${cfg.githubRepo}/contents/link"
                                            } else {
                                                "${cfg.acceleratedBase}/index.json"
                                            }
                                            val conn = java.net.URL(probe).openConnection() as java.net.HttpURLConnection
                                            try {
                                                conn.connectTimeout = 10_000
                                                conn.readTimeout = 10_000
                                                conn.requestMethod = "GET"
                                                conn.setRequestProperty("User-Agent", "HyperCopy")
                                                conn.responseCode in 200..299
                                            } finally {
                                                conn.disconnect()
                                            }
                                        }.getOrDefault(false)
                                    }
                                    if (!reachable) {
                                        Toast.makeText(context, R.string.cloud_source_add_unreachable, Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }
                                    settingsRepository.addCustomCloudSource(cfg)
                                    allSources = CloudSourceRegistry.allSources(context)
                                    showAddDialog = false
                                    onDismiss()
                                    onSourceChange(cfg.key)
                                    Toast.makeText(context, R.string.cloud_source_add_ok, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
