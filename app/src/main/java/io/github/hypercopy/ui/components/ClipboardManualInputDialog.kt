package io.github.hypercopy.ui.components
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.github.hypercopy.R
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * v1.42 剪贴板读取失败时的手动输入兜底对话框（统一组件，三处入口共用）：
 * 规则页 FAB「从剪贴板添加」/ 建议页「粘贴」/ 编辑器「智能识别」。
 * @param onConfirm 用户确认输入的非空文本
 */
@Composable
fun ClipboardManualInputDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var inputText by remember(show) { mutableStateOf("") }
    WindowDialog(
        title = stringResource(R.string.clipboard_manual_title),
        summary = stringResource(R.string.clipboard_manual_summary),
        show = show,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.action_confirm),
                    onClick = {
                        val input = inputText.trim()
                        if (input.isNotEmpty()) {
                            onConfirm(input)
                            inputText = ""
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}