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
// v1.141.34 品牌专属色：从"随机 hash 颜色"升级为"品牌主色 + 平台字符"，
// 使美团/淘宝/抖音等规则显示稳定的品牌色标签（美团黄、淘宝橙、抖音黑），辨识度远超随机色。
private val FALLBACK_COLORS = listOf(
    Color(0xFF4C6FFF), Color(0xFF00B578), Color(0xFFFF5F6B),
    Color(0xFFFF8F1F), Color(0xFF8B5CF6), Color(0xFF06B6D4),
    Color(0xFFF43F5E), Color(0xFF10B981),
)

/** v1.141.34 品牌专属色映射：packageName → 品牌主色（未命中用 FALLBACK_COLORS 兜底） */
private val BRAND_PACKAGE_COLORS: Map<String, Color> = mapOf(
    "com.sankuai.meituan" to Color(0xFFFFD100),            // 美团黄
    "com.sankuai.meituan.takeoutnew" to Color(0xFFFFB800), // 美团外卖橙黄
    "com.dianping.v1" to Color(0xFFE1251B),                // 大众点评红
    "me.ele" to Color(0xFF1D9E99),                          // 饿了么青
    "com.taobao.taobao" to Color(0xFFFF4400),              // 淘宝橙
    "com.tmall.wireless" to Color(0xFFFF5000),              // 天猫红
    "com.taobao.idlefish" to Color(0xFFFFE500),             // 闲鱼黄
    "com.jingdong.app.mall" to Color(0xFFE1251B),           // 京东红
    "com.xunmeng.pinduoduo" to Color(0xFFE82E2E),           // 拼多多红
    "com.ss.android.ugc.aweme" to Color(0xFF161823),        // 抖音黑
    "tv.danmaku.bili" to Color(0xFFFB7299),                 // B站粉
    "com.xingin.xhs" to Color(0xFFFF2442),                  // 小红书红
    "com.smile.gifmaker" to Color(0xFFFF4215),              // 快手橙
    "com.sina.weibo" to Color(0xFFE6162D),                  // 微博红
    "com.lcw.easydownload" to Color(0xFF3B82F6),            // 便捷下载蓝
    "com.cainiao.wireless" to Color(0xFFFF8C00),            // 菜鸟橙
    "com.sf.activity" to Color(0xFF155FA2),                 // 顺丰蓝
    "com.tencent.mm" to Color(0xFF07C160),                  // 微信绿
    "com.tencent.mobileqq" to Color(0xFF12B7F5),            // QQ蓝
    "com.alipay.android" to Color(0xFF1677FF),              // 支付宝蓝
    "com.android.chrome" to Color(0xFF4285F4),              // Chrome蓝
    "com.MobileTicket" to Color(0xFF2196F3),                // 12306蓝
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

/** 首字符圆形兜底图标：未安装应用/无真实图标时展示（v1.141.34 用品牌专属色提高辨识度） */
@Composable
private fun FallbackIcon(packageName: String, fallbackText: String) {
    val label = fallbackText.ifBlank { packageName }.trim().firstOrNull()?.toString()?.uppercase() ?: "?"
    // v1.141.34 优先品牌专属色（美团→黄、淘宝→橙），未命中用随机色
    val brandColor = BRAND_PACKAGE_COLORS[packageName]
    val color = brandColor ?: FALLBACK_COLORS[(packageName.hashCode().and(Int.MAX_VALUE)) % FALLBACK_COLORS.size]
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.75f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (brandColor != null && (packageName.contains("meituan") || packageName.contains("idlefish"))) Color(0xFF4A3D00) else Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
