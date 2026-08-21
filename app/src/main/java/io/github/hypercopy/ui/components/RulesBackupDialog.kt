package io.github.hypercopy.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hypercopy.R
import io.github.hypercopy.data.rules.RuleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * v1.145.16 规则备份对话框（设置页入口）：
 * - 立即导出：当前规则备份到工作区 _archive/rules_backup/（公共存储，清除数据/卸载不丢）
 * - 从备份恢复：读取外部备份覆盖内部规则库（防规则丢失自愈）
 */
@Composable
fun RulesBackupDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(enabled = show) { onDismiss() }
    if (show) {
        WindowDialog(
            title = stringResource(R.string.rules_backup_title),
            show = show,
            onDismissRequest = onDismiss,
            modifier = modifier,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.rules_backup_summary),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = stringResource(R.string.rules_backup_export),
                        onClick = {
                            scope.launch {
                                val n = withContext(Dispatchers.IO) {
                                    RuleRepository(context).exportRulesBackup()
                                }
                                Toast.makeText(
                                    context,
                                    if (n > 0) context.getString(R.string.rules_backup_exported, n)
                                    else context.getString(R.string.rules_backup_missing),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                    TextButton(
                        text = stringResource(R.string.rules_backup_restore),
                        onClick = {
                            scope.launch {
                                val n = withContext(Dispatchers.IO) {
                                    RuleRepository(context).restoreFromBackup()
                                }
                                Toast.makeText(
                                    context,
                                    if (n > 0) context.getString(R.string.rules_backup_restored, n)
                                    else context.getString(R.string.rules_backup_missing),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }
            }
        }
    }
}
