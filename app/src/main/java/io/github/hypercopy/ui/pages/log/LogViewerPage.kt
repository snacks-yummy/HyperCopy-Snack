package io.github.hypercopy.ui.pages.log
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hypercopy.HyperLog
import io.github.hypercopy.LogEntry
import io.github.hypercopy.R
import android.widget.Toast
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
private fun levelColors(): Map<String, Pair<Color, Color>> {
    val dark = isSystemInDarkTheme()
    return mapOf(
        "E" to ((if (dark) Color(0xFF4A1F1C) else Color(0xFFFF5A52)) to Color.White),
        "W" to ((if (dark) Color(0xFF4A3A14) else Color(0xFFF5A623)) to Color.White),
        "D" to ((if (dark) Color(0xFF16324E) else Color(0xFF4A90D9)) to Color.White),
    )
}

/** 日志查看页（v1.32 升级）：级别彩色徽章 + 时间 + 标签 + 消息，支持过滤与复制 */
@Composable
fun LogViewerPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var allLogs by remember { mutableStateOf(HyperLog.recentLogs()) }
    var filter by remember { mutableStateOf("") } // "" = 全部, E/W/D
    val logs = if (filter.isBlank()) allLogs else allLogs.filter { it.level == filter }
    val filterOptions = listOf(
        "" to stringResource(R.string.log_filter_all),
        "E" to stringResource(R.string.log_filter_error),
        "W" to stringResource(R.string.log_filter_warn),
        "D" to stringResource(R.string.log_filter_debug),
    )
    // 自动刷新（2 秒轮询）
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            allLogs = HyperLog.recentLogs()
        }
    }
    // v1.77 新日志自动跟随（用户在底部附近时滚到最新）
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (lastVisible >= logs.size - 3 || lastVisible < 0) {
                listState.animateScrollToItem(logs.size - 1)
            }
        }
    }
    Scaffold { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.action_back))
                }
                Text(
                    text = stringResource(R.string.log_viewer_title),
                    style = MiuixTheme.textStyles.title1,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.action_log_copy),
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("HyperCopyLogs", HyperLog.recentLogText()))
                        Toast.makeText(context, R.string.log_copied, Toast.LENGTH_SHORT).show()
                    },
                )
                TextButton(
                    text = stringResource(R.string.action_log_clear),
                    onClick = { HyperLog.clearBuffer(); allLogs = emptyList() },
                )
            }
            // 级别过滤 chips
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                filterOptions.forEach { (value, label) ->
                    item {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (filter == value) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh)
                                .clickable { filter = value }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = label,
                                color = if (filter == value) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                // v1.39 修复：不用 timestamp 拼 key——同毫秒多条日志 key 冲突导致闪退
                itemsIndexed(logs) { _, entry ->
                    LogRow(entry)
                }
                if (logs.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.log_empty),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val badge = levelColors()[entry.level] ?: (MiuixTheme.colorScheme.onSurfaceVariantSummary to MiuixTheme.colorScheme.onSurface)
    val badgeColor = badge.first
    val badgeTextColor = badge.second
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(badgeColor)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Text(text = entry.level, color = badgeTextColor, style = MiuixTheme.textStyles.body2)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                text = entry.message,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = "${java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp))}  ${entry.tag}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}