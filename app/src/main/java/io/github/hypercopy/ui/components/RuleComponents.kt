package io.github.hypercopy.ui.components
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.hypercopy.R
import io.github.hypercopy.data.rules.BuiltinRules
import io.github.hypercopy.data.rules.RuleCategory
import io.github.hypercopy.data.rules.RuleConfig
import io.github.hypercopy.data.rules.toJson
import io.github.hypercopy.data.systemlink.SystemLinkApp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRowColors
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RuleCategoryTabs(
    selectedCategory: RulePageCategory,
    onSelected: (RulePageCategory) -> Unit,
    modifier: Modifier = Modifier,
    includeSystem: Boolean = false,
    colors: TabRowColors = TabRowDefaults.tabRowColors(),
    // v1.76 各 Tab 数量（与 titles 顺序对应；null=不显示），如「链接 (33)」
    counts: List<Int>? = null,
) {
    val titles = if (includeSystem) localRuleCategoryTabTitles else cloudRuleCategoryTabTitles
    TabRowWithContour(
        tabs = titles.mapIndexed { index, res ->
            val title = stringResource(res)
            if (counts != null && index < counts.size) "$title (${counts[index]})" else title
        },
        selectedTabIndex = if (includeSystem) selectedCategory.tabIndex() else selectedCategory.cloudTabIndex(),
        onTabSelected = { onSelected(if (includeSystem) localRulePageCategoryFromTab(it) else cloudRulePageCategoryFromTab(it)) },
        modifier = modifier.fillMaxWidth(),
        colors = colors,
    )
}

@Composable
internal fun TestRuleCard(
    category: RulePageCategory,
    value: String,
    resultText: String,
    onValueChange: (String) -> Unit,
    onExecute: () -> Unit,
    onClear: () -> Unit = {},
) {
    // v1.77 可折叠：默认展开，点标题收起（为规则列表让出空间）
    var collapsed by remember { mutableStateOf(false) }
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { collapsed = !collapsed },
                // v1.131 标题行间距统一：收起提示与清空按钮间加 spacing，避免按钮自带内边距导致的视觉间距异常
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = stringResource(R.string.rule_test_title, stringResource(category.titleRes())), style = MiuixTheme.textStyles.title3, modifier = Modifier.weight(1f))
                // v1.77 折叠状态提示（收起时仍可点标题展开）
                Text(
                    text = stringResource(if (collapsed) R.string.rule_test_collapsed else R.string.rule_test_collapse),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                // v1.65 测试输入清空
                TextButton(
                    text = stringResource(R.string.action_clear),
                    onClick = onClear,
                )
            }
            if (!collapsed) {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(category.testHintRes()),
                    singleLine = false,
                    maxLines = 3,
                )
                Text(
                    text = resultText,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                TextButton(
                    text = stringResource(R.string.action_run_test),
                    onClick = onExecute,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
internal fun SystemLinkHandlingCard(
    checked: Boolean,
    clearClipboardAfterJump: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClearClipboardAfterJumpChange: (Boolean) -> Unit,
) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = stringResource(R.string.rule_system_link_title), style = MiuixTheme.textStyles.headline1)
                    Text(
                        text = stringResource(R.string.rule_system_link_summary),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Switch(checked = checked, onCheckedChange = { onCheckedChange(!checked) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = stringResource(R.string.rule_system_clear_clipboard_title), style = MiuixTheme.textStyles.headline1)
                    Text(
                        text = stringResource(R.string.rule_system_clear_clipboard_summary),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Switch(
                    checked = clearClipboardAfterJump,
                    onCheckedChange = { onClearClipboardAfterJumpChange(!clearClipboardAfterJump) },
                )
            }
        }
    }
}

@Composable
internal fun SystemLinkAppListCard(
    app: SystemLinkApp,
    onClick: () -> Unit,
    onAppEnabledChange: (Boolean) -> Unit,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PackageIcon(packageName = app.packageName, modifier = Modifier.padding(end = 12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = app.label, style = MiuixTheme.textStyles.headline1)
                Text(
                    text = app.packageName,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = app.linkHandlingAllowed,
                onCheckedChange = { onAppEnabledChange(!app.linkHandlingAllowed) },
            )
            IconButton(
                onClick = onClick,
                minWidth = 32.dp,
                minHeight = 32.dp,
                cornerRadius = 16.dp,
                backgroundColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.08f),
                modifier = Modifier.padding(start = 10.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = stringResource(R.string.action_open),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
@Composable
internal fun EmptyRulesCard(
    category: RulePageCategory,
    onAddClick: (() -> Unit)? = null,
) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.rule_empty_title, stringResource(category.titleRes())), style = MiuixTheme.textStyles.title3)
            Text(
                text = stringResource(category.emptyDescriptionRes()),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            // v1.65 空态引导：一键添加规则
            if (onAddClick != null) {
                TextButton(
                    text = stringResource(R.string.rule_empty_add),
                    onClick = onAddClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
internal fun RuleSelectionBar(
    selectedCount: Int,
    allSelected: Boolean,
    modifier: Modifier = Modifier,
    onCloseClick: () -> Unit,
    onSelectAllClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onEnableClick: () -> Unit = {},
    onDisableClick: () -> Unit = {},
    // v1.141.22 批量复制选中规则（JSON 到剪贴板）
    onCopyClick: () -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            // 第一行：关闭 + 已选数量 + 全选/取消全选
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onCloseClick,
                    minWidth = 36.dp,
                    minHeight = 36.dp,
                    cornerRadius = 18.dp,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Close,
                        contentDescription = stringResource(R.string.action_cancel_selection),
                        tint = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.rule_selected_count, selectedCount),
                    style = MiuixTheme.textStyles.headline1,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                TextButton(
                    text = stringResource(if (allSelected) R.string.action_select_none else R.string.action_select_all),
                    onClick = onSelectAllClick,
                )
            }
            // 第二行：操作按钮（图标 + 文字，直观不误点）
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SelectionActionButton(
                    icon = MiuixIcons.SelectAll,
                    text = stringResource(R.string.action_enable),
                    onClick = onEnableClick,
                    modifier = Modifier.weight(1f),
                )
                SelectionActionButton(
                    icon = MiuixIcons.Copy,
                    text = stringResource(R.string.action_copy_rule),
                    onClick = onCopyClick,
                    modifier = Modifier.weight(1f),
                )
                SelectionActionButton(
                    icon = MiuixIcons.Close,
                    text = stringResource(R.string.action_disable),
                    onClick = onDisableClick,
                    modifier = Modifier.weight(1f),
                )
                SelectionActionButton(
                    icon = MiuixIcons.Delete,
                    text = stringResource(R.string.action_trash),
                    onClick = onDeleteClick,
                    danger = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** v1.37 选择操作按钮：图标 + 文字（红色为危险操作） */
@Composable
private fun SelectionActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val tint = if (danger) Color(0xFFF44336) else MiuixTheme.colorScheme.primary
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(imageVector = icon, contentDescription = text, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = tint,
            maxLines = 1,
        )
    }
}

@Composable
internal fun RuleEditBar(
    modifier: Modifier = Modifier,
    onCloseClick: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(
                onClick = onCloseClick,
                minWidth = 36.dp,
                minHeight = 36.dp,
                cornerRadius = 18.dp,
            ) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = stringResource(R.string.rule_sort_tip),
                style = MiuixTheme.textStyles.headline1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** v1.63 回收站顶栏：返回按钮 + 标题 + 数量（修复回收站无法返回规则列表） */
@Composable
internal fun RuleTrashBar(
    modifier: Modifier = Modifier,
    count: Int,
    onBackClick: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(
                onClick = onBackClick,
                minWidth = 36.dp,
                minHeight = 36.dp,
                cornerRadius = 18.dp,
            ) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = stringResource(R.string.rule_trash_bar_title, count),
                style = MiuixTheme.textStyles.headline1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RuleCard(
    rule: RuleConfig,
    selected: Boolean,
    selectionMode: Boolean,
    sortMode: Boolean,
    dragging: Boolean,
    dragOffsetY: Float,
    hitCount: Int = 0,
    // v1.139.1c 用户修改过的内置规则 id（修改过=内置/我的；未修改的作者原版=云端）
    modifiedBuiltinIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    onEnabledChange: (Boolean) -> Unit,
    onEditClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectionToggle: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    // v1.142.6h 左滑删除：null=禁用滑动（System 分类/无删除语义）；排序/选择模式下自动禁用
    onDeleteClick: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // v1.142.6h 左滑删除状态：swipeOffset>0 = 内容左移露出右侧删除按钮
    val deleteBtnWidth = 84.dp
    val deleteBtnPx = with(density) { deleteBtnWidth.toPx() }
    val swipeEnabled = onDeleteClick != null && !sortMode && !selectionMode
    var swipeOffset by remember(rule.id) { mutableStateOf(0f) }
    // v1.139.1b 规则来源：我的内置(蓝) / 作者原版内置+云端下载(橙, 细化源名) / 自定义(绿)
    val isBuiltin = rule.id in MY_BUILTIN_RULE_IDS || rule.id in modifiedBuiltinIds
    val isCloud = !isBuiltin && (rule.id.startsWith(BuiltinRules.ID_PREFIX) || rule.id.startsWith("cloud_"))
    val isCustom = !isBuiltin && !isCloud
    // v1.139.1b 云端源名细化：cloud_1812z_/作者原版内置=作者 / cloud_snacks_=零食仓库 / cloud_custom_=自定义源（v1.142.6e 资源化）
    val cloudBadgeText = when {
        rule.id.startsWith("cloud_1812z_") || (rule.id.startsWith(BuiltinRules.ID_PREFIX) && !isBuiltin) ->
            stringResource(R.string.badge_cloud_author)
        rule.id.startsWith(BuiltinRules.ID_PREFIX) -> stringResource(R.string.badge_builtin)
        rule.id.startsWith("cloud_snacks_") -> stringResource(R.string.badge_cloud_snacks)
        rule.id.startsWith("cloud_custom_") -> stringResource(R.string.badge_cloud_custom)
        else -> stringResource(R.string.rule_cloud_badge)
    }
    Card(
        modifier = modifier
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = dragOffsetY },
        // v1.142.6l 显式卡片背景色：内容 Row 同色不透明 → 左滑时红色按钮区才逐渐露出（跟手变色，GitHub 同款）
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
    ) {
        Box {
            // v1.142.6j 修复：左滑删除按钮应为「右侧固定宽度红色按钮区」（iOS 风格），
            // 不是整卡变红——此前 matchParentSize 整卡红 + 内容 Row 透明 → 左滑时整卡变红（用户截图确认）
            if (swipeEnabled && swipeOffset > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(deleteBtnWidth)
                            .align(Alignment.CenterEnd)
                            .background(Color(0xFFFF5A52))
                            .clickable(enabled = swipeOffset > 0f) {
                                swipeOffset = 0f
                                onDeleteClick?.invoke()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.action_delete),
                            style = MiuixTheme.textStyles.body2,
                            color = Color.White,
                            // v1.142.6m 修复：删除按钮加图标 + 更明显（右对齐，文字居中）
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        // v1.142.6m 删除按钮加图标（MiuixIcons.Delete）
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
            }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // v1.142.6l 内容层不透明卡片背景（与 Card 同色）→ 平时盖住红色按钮区，左滑移开才跟手露出（GitHub 同款）
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .let { rowModifier ->
                    if (sortMode) {
                        rowModifier
                    } else {
                        rowModifier.combinedClickable(
                            onClick = { if (selectionMode) onSelectionToggle() else onEditClick() },
                            onLongClick = onLongClick,
                        )
                    }
                }
                .padding(16.dp)
                // v1.142.6h 左滑露出删除按钮：内容左移 swipeOffset，手势与点击/长按/垂直滚动天然区分
                .graphicsLayer { translationX = -swipeOffset }
                .then(
                    if (swipeEnabled) {
                        // v1.142.6h 修复：pointerInput key 不能含 swipeOffset（每次偏移变化会重启手势协程，拖拽被不断打断）
                        // key 仅 rule.id，手势闭包内通过 state 委托读取实时偏移
                        Modifier.pointerInput(rule.id) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    val target = when {
                                        // 完全滑动（超过 1.3 倍按钮宽）→ 直接弹删除确认
                                        swipeOffset > deleteBtnPx * 1.3f -> {
                                            onDeleteClick?.invoke()
                                            0f
                                        }
                                        // 超过半宽 → 展开露出删除按钮
                                        swipeOffset > deleteBtnPx * 0.5f -> deleteBtnPx
                                        else -> 0f
                                    }
                                    scope.launch {
                                        animate(swipeOffset, target, animationSpec = tween(180)) { value, _ -> swipeOffset = value }
                                    }
                                },
                                onDragCancel = {
                                    scope.launch {
                                        animate(swipeOffset, 0f, animationSpec = tween(180)) { value, _ -> swipeOffset = value }
                                    }
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    // 手指左滑 dragAmount<0 → swipeOffset 增大（内容左移露出右侧删除）
                                    swipeOffset = (swipeOffset - dragAmount).coerceIn(0f, deleteBtnPx * 1.6f)
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PackageIcon(
                packageName = rule.target.packageName,
                fallbackText = rule.name,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // v1.142.6 名称满宽独占一行（徽章全部移至动作行，彻底解决徽章挤压名称截断）
                Text(
                    text = rule.name,
                    style = MiuixTheme.textStyles.headline1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                // v1.142.6 来源徽章 + 场景组徽章 + 动作标签合并一行（后续新增徽章自动落入此行，不挤压名称）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isBuiltin) {
                        RuleBadge(
                            text = stringResource(R.string.rule_builtin_badge),
                            contentColor = MiuixTheme.colorScheme.primary,
                            containerColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.10f),
                        )
                    } else if (isCloud) {
                        // v1.139.1 作者云端规则：橙色徽标（与内置/自定义区分，便于识别不适配规则）
                        val cloudColor = Color(0xFFF97316)
                        RuleBadge(
                            text = cloudBadgeText,
                            contentColor = cloudColor,
                            containerColor = cloudColor.copy(alpha = 0.12f),
                        )
                    } else if (isCustom) {
                        // v1.139.1 自定义规则：绿色徽标（v1.142.6c 深色模式可读性优化 0xFF16A34A→0xFF22C55E）
                        val customColor = Color(0xFF22C55E)
                        RuleBadge(
                            text = stringResource(R.string.rule_custom_badge),
                            contentColor = customColor,
                            containerColor = customColor.copy(alpha = 0.12f),
                        )
                    }
                    if (rule.group.isNotBlank()) {
                        RuleBadge(
                            text = rule.group,
                            contentColor = MiuixTheme.colorScheme.onSurface,
                            containerColor = MiuixTheme.colorScheme.surfaceContainerHigh,
                        )
                    }
                    // v1.142.6b 徽章与动作文字间的语义分隔符（区分「来源/场景」与「行为」）
                    Text(
                        text = "·",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    // v1.142.6d 动作文字恢复 primary 蓝（用户反馈灰色不显眼；与内置徽章蓝区分靠徽章背景框）
                    Text(
                        text = stringResource(ruleActionLabelRes(rule)),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                if (hitCount > 0) {
                    Text(
                        text = stringResource(R.string.rule_hit_count, hitCount),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (sortMode) {
                Icon(
                    imageVector = MiuixIcons.ListView,
                    contentDescription = stringResource(R.string.action_sort_rule),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(24.dp)
                        .pointerInput(rule.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { onDragStart() },
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragEnd,
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDrag(dragAmount.y)
                                },
                            )
                        },
                )
            } else if (selectionMode) {
                Checkbox(
                    state = if (selected) ToggleableState.On else ToggleableState.Off,
                    onClick = onSelectionToggle,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            if (!selectionMode && !sortMode) {
                // v1.142.6g 卡片右侧仅保留 Switch（最常用开关）
                // 删除冗余入口：①复制按钮（复制 JSON 是极客操作，编辑器「更多」菜单/选择模式批量复制已覆盖，三重重复）
                // ②编辑箭头（与整卡点击进编辑器 100% 重复）——卡片交互收敛为：点击=编辑 / 长按=多选 / 开关=启停
                Switch(checked = rule.enabled, onCheckedChange = { onEnabledChange(!rule.enabled) })
            }
        }
        }
    }
}

/** 复制规则（JSON）到剪贴板 */
/**
 * v1.142.6b 统一徽章组件：严格 20dp 高度 + 文字垂直居中 + 统一圆角/内边距，
 * 保证列表内所有徽章（来源/场景/后续新增）高度完全一致、视觉对齐。
 */
@Composable
private fun RuleBadge(
    text: String,
    contentColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = contentColor,
        modifier = modifier
            .background(containerColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp)
            .height(20.dp)
            .wrapContentHeight(Alignment.CenterVertically),
    )
}
/** 复制规则（JSON）到剪贴板 —— v1.142.6g 移除：卡片复制按钮已删（与编辑器更多菜单/选择模式批量复制三重重复），此函数不再被引用 */

@Composable
internal fun AddRuleMenu(
    category: RulePageCategory,
    modifier: Modifier = Modifier,
    onBrowserClick: () -> Unit,
    onLinkRuleClick: () -> Unit,
    onExpressRuleClick: () -> Unit,
    onClipboardClick: () -> Unit,
    onMergeDuplicateClick: () -> Unit = {},
    onExportAllClick: () -> Unit = {},
    onImportFileClick: () -> Unit = {},
    onExportFileClick: () -> Unit = {},
    onRestoreBuiltinClick: () -> Unit = {},
    onShareRuleClick: () -> Unit = {},
) {
    var showPopup by remember { mutableStateOf(false) }
    // v1.141.17 修复：文本列表页＋也弹菜单（之前只有 Link 弹，「从剪贴板添加规则」只对链接可用）。
    // 菜单项按分类适配：Link 含「模拟浏览器」；Text（含 Address/Express）去掉浏览器，保留其余通用项。
    val menuItems: List<Pair<Int, Int>> = if (category == RulePageCategory.Link) {
        listOf(
            R.string.rule_menu_browser to 0,
            R.string.action_add_rule to 1,
            R.string.rule_menu_clipboard to 2,
            R.string.action_merge_duplicate to 3,
            R.string.action_export_all to 4,
            R.string.action_export_file to 5,
            R.string.action_import_file to 6,
            R.string.action_restore_builtin to 7,
            R.string.action_share_rule_cloud to 8,
        )
    } else {
        listOf(
            R.string.action_add_rule to 1,
            R.string.rule_menu_clipboard to 2,
            R.string.action_merge_duplicate to 3,
            R.string.action_export_all to 4,
            R.string.action_export_file to 5,
            R.string.action_import_file to 6,
            R.string.action_restore_builtin to 7,
            R.string.action_share_rule_cloud to 8,
        )
    }

    Box(modifier = modifier) {
        FloatingActionButton(onClick = { if (category != RulePageCategory.System) showPopup = true else onExpressRuleClick() }) {
            Icon(
                imageVector = MiuixIcons.Add,
                contentDescription = stringResource(R.string.action_add_rule),
                tint = MiuixTheme.colorScheme.onPrimary,
                modifier = Modifier.size(28.dp),
            )
        }
        OverlayListPopup(
            show = showPopup && category != RulePageCategory.System,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { showPopup = false },
        ) {
            ListPopupColumn {
                menuItems.forEachIndexed { index, (res, actionId) ->
                    DropdownImpl(
                        text = stringResource(res),
                        optionSize = menuItems.size,
                        isSelected = false,
                        index = index,
                        onSelectedIndexChange = {
                            showPopup = false
                            when (actionId) {
                                0 -> onBrowserClick()
                                1 -> onLinkRuleClick()
                                2 -> onClipboardClick()
                                3 -> onMergeDuplicateClick()
                                4 -> onExportAllClick()
                                5 -> onExportFileClick()
                                6 -> onImportFileClick()
                                7 -> onRestoreBuiltinClick()
                                else -> onShareRuleClick()
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 生成 HyperCopy_Rules 云规则仓库提交链接（v1.27 云分享） */
internal fun githubRuleSubmissionUri(rule: io.github.hypercopy.data.rules.RuleConfig): android.net.Uri {
    val folder = if (rule.category == io.github.hypercopy.data.rules.RuleCategory.Link) "link" else "text"
    val safeName = rule.name.toRuleFileNamePart().ifBlank { "rule" }
    val safePackageName = rule.target.packageName.toRuleFileNamePart()
    val fileName = listOf(safeName, safePackageName).filter { it.isNotBlank() }.joinToString("_") + ".json"
    return android.net.Uri.Builder()
        .scheme("https")
        .authority("github.com")
        .appendPath("1812z")
        .appendPath("HyperCopy_Rules")
        .appendPath("new")
        .appendPath("main")
        .appendPath(folder)
        .appendQueryParameter("filename", fileName)
        .appendQueryParameter("value", rule.toJson().toString(2))
        .appendQueryParameter("message", "Add $fileName")
        .build()
}
internal fun String.toRuleFileNamePart(): String = trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")

/** v1.126 跳转方式徽标（v1.127b：去掉 emoji 前缀防豆腐块渲染，纯文字+颜色区分；v1.142.6e 返回 StringRes） */
internal fun jumpModeBadge(rule: io.github.hypercopy.data.rules.RuleConfig): Pair<Int, Color>? {
    if (rule.actionMode == io.github.hypercopy.data.rules.RuleActionMode.ClipboardWrite) return R.string.badge_clipboard_write to Color(0xFF9C6ADE)
    if (rule.actionMode == io.github.hypercopy.data.rules.RuleActionMode.NotifyOnly) return R.string.badge_notify_only to Color(0xFF00A0E9)
    val template = rule.target.template
    return when {
        template.isBlank() && rule.target.packageName.isNotBlank() -> R.string.badge_direct_open to Color(0xFF6C8EF5)
        template.startsWith("http", ignoreCase = true) -> R.string.badge_web to Color(0xFF00B578)
        template.isNotBlank() -> R.string.badge_scheme to Color(0xFFF5A623)
        else -> null
    }
}
