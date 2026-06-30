package io.github.hypercopy.ui.pages.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hypercopy.R
import io.github.hypercopy.data.rules.RuleRepository
import io.github.hypercopy.data.rules.RuleStatsRepository
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 全局规则统计（v1.25）：总命中 + 规则排行 */
@Composable
fun StatsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { RuleRepository(context.applicationContext) }
    val statsRepository = remember { RuleStatsRepository(context.applicationContext) }
    val historyRepository = remember { io.github.hypercopy.data.rules.JumpHistoryRepository(context.applicationContext) }
    var refresh by remember { mutableStateOf(0) }
    // v1.65 破坏性操作确认：清空统计前弹确认
    var showClearConfirm by remember { mutableStateOf(false) }
    val rules = remember(refresh) { repository.readRules() }
    val stats = remember(refresh) { statsRepository.getAll() }
    val totalHits = remember(refresh) { stats.values.sum() }
    val history = remember(refresh) { historyRepository.read().reversed().take(10) }
    val ranked = remember(refresh) {
        rules.mapNotNull { rule ->
            val count = stats[rule.id] ?: 0
            if (count > 0) rule to count else null
        }.sortedByDescending { it.second }
    }
    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.action_back))
                }
                Text(
                    text = stringResource(R.string.stats_title),
                    style = MiuixTheme.textStyles.title1,
                    modifier = Modifier.weight(1f),
                )
                TextButton(text = stringResource(R.string.action_refresh), onClick = { refresh++ })
                TextButton(
                    text = stringResource(R.string.stats_clear),
                    onClick = { showClearConfirm = true },
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    Card {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(text = stringResource(R.string.stats_total_hits, totalHits), style = MiuixTheme.textStyles.headline1)
                            Text(
                                text = stringResource(R.string.stats_total_rules, rules.size, ranked.size),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
                if (ranked.isEmpty()) {
                    item {
                        Card {
                            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                                Text(
                                    text = stringResource(R.string.stats_empty),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                } else {
                    // v1.38 修复：不用 key={it.first.id}——历史遗留重复 id 会导致 LazyColumn key 冲突崩溃
                    itemsIndexed(ranked) { _, item ->
                        val rule = item.first
                        val count = item.second
                        Card(
                            // v1.68 排行卡片可点击：直达规则编辑
                            onClick = {
                                context.startActivity(
                                    android.content.Intent(context, io.github.hypercopy.ui.activities.RuleEditorActivity::class.java)
                                        .putExtra(io.github.hypercopy.ui.activities.RuleEditorActivity.EXTRA_RULE_ID, rule.id)
                                        .putExtra(io.github.hypercopy.ui.activities.RuleEditorActivity.EXTRA_CATEGORY, rule.category.value),
                                )
                            },
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(text = rule.name, style = MiuixTheme.textStyles.headline1)
                                Text(
                                    text = "${rule.target.packageName.ifBlank { rule.category.value }} · ${stringResource(R.string.rule_hit_count, count)}",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        text = stringResource(R.string.stats_recent_jumps),
                        style = MiuixTheme.textStyles.headline1,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (history.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.stats_recent_empty),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                } else {
                    items(history.size) { index ->
                        val entry = history[index]
                        Card {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(text = entry.ruleName, style = MiuixTheme.textStyles.headline1)
                                    Text(
                                        text = entry.packageName.ifBlank { "-" },
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                                Text(
                                    text = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp)),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                    }
                            }
                }
            }
        }
    }
    // v1.65 清空统计确认
    top.yukonga.miuix.kmp.window.WindowDialog(
        title = stringResource(R.string.stats_clear_title),
        summary = stringResource(R.string.stats_clear_summary),
        show = showClearConfirm,
        onDismissRequest = { showClearConfirm = false },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = stringResource(R.string.action_cancel),
                onClick = { showClearConfirm = false },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.action_confirm),
                onClick = {
                    showClearConfirm = false
                    rules.forEach { statsRepository.clear(it.id) }
                    // v1.31 审计修复：回收站条目统计一并清理
                    repository.readTrash().forEach { statsRepository.clear(it.rule.id) }
                    historyRepository.clear()
                    refresh++
                },
                modifier = Modifier.weight(1f),
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
