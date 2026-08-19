package com.assetsking.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 5 套主题（REQ 主题§1/§12）：碳水大王式浅绿默认 + 三套浅色变体 + 「龙巢」深色特例。
 *
 * 龙巢是唯一深色主题：本文件只提供可运行的深色占位令牌（哑光古金 primary + 深灰表面），
 * 黑曜石层次/龙鳞暗纹/材质与最终色值留给 Codex（见设计文档）。业务组件一律读
 * MaterialTheme.colorScheme 语义令牌，不得硬编码颜色/圆角——这是龙巢接入的工程接口。
 */
enum class AppTheme(val key: String, val label: String) {
    LIGHT_GREEN("light_green", "浅绿"),
    SKY_BLUE("sky_blue", "天蓝"),
    VIOLET("violet", "薰衣草"),
    WARM("warm", "暖橙"),
    LONG_NEST("long_nest", "龙巢");

    companion object {
        fun byKey(key: String?): AppTheme = entries.firstOrNull { it.key == key } ?: LIGHT_GREEN
    }
}

private fun lightScheme(primary: Color, primaryContainer: Color, onPrimaryContainer: Color) = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    background = SurfaceLight,
    surface = SurfaceLight,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = OutlineLight
)

// 龙巢深色占位（Codex 后续替换完整深色令牌）
private val LongNestScheme = darkColorScheme(
    primary = ThemePrimaryLongNest,
    onPrimary = Color(0xFF1C1C1E),
    background = SurfaceDark,
    surface = SurfaceDark,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = OutlineDark
)

@Composable
fun AssetsKingTheme(
    theme: AppTheme = AppTheme.LIGHT_GREEN,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = when (theme) {
            AppTheme.LIGHT_GREEN -> lightScheme(ThemePrimaryGreen, ThemePrimaryGreenSoft, ThemePrimaryGreenSoftText)
            AppTheme.SKY_BLUE -> lightScheme(ThemePrimaryBlue, ThemePrimaryBlueSoft, ThemePrimaryBlueSoftText)
            AppTheme.VIOLET -> lightScheme(ThemePrimaryViolet, ThemePrimaryVioletSoft, ThemePrimaryVioletSoftText)
            AppTheme.WARM -> lightScheme(ThemePrimaryWarm, ThemePrimaryWarmSoft, ThemePrimaryWarmSoftText)
            AppTheme.LONG_NEST -> LongNestScheme
        },
        typography = AssetsKingTypography,
        content = content
    )
}
