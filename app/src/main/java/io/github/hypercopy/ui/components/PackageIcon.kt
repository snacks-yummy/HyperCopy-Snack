package io.github.hypercopy.ui.components
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import io.github.hypercopy.AppIconCache

// v1.81 图标补充：目标 App 未安装时显示「首字符圆形兜底图标」
// （云规则下载/内置规则的目标应用常未安装，真实图标不可得 → 用规则名首字符+品牌色系）
private val FALLBACK_COLORS = listOf(
    Color(0xFF4C6FFF), Color(0xFF00B578), Color(0xFFFF5F6B),
    Color(0xFFFF8F1F), Color(0xFF8B5CF6), Color(0xFF06B6D4),
    Color(0xFFF43F5E), Color(0xFF10B981),
)

@Composable
fun PackageIcon(packageName: String, modifier: Modifier = Modifier, fallbackText: String = "") {
    val context = LocalContext.current.applicationContext
    var icon by remember(packageName) { mutableStateOf(AppIconCache.get(packageName)) }
    LaunchedEffect(packageName) {
        if (packageName.isBlank()) return@LaunchedEffect
        AppIconCache.get(packageName)?.let {
            icon = it
            return@LaunchedEffect
        }
        if (AppIconCache.hasResult(packageName)) return@LaunchedEffect
        icon = AppIconCache.load(context, packageName)
    }
    Box(modifier = modifier.size(40.dp), contentAlignment = Alignment.Center) {
        icon?.let {
            Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp))
        } ?: FallbackIcon(packageName, fallbackText)
    }
}

/** 首字符圆形兜底图标：未安装应用/无真实图标时展示 */
@Composable
private fun FallbackIcon(packageName: String, fallbackText: String) {
    val label = fallbackText.ifBlank { packageName }.trim().firstOrNull()?.toString()?.uppercase() ?: "?"
    val color = FALLBACK_COLORS[(packageName.hashCode().and(Int.MAX_VALUE)) % FALLBACK_COLORS.size]
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.75f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
