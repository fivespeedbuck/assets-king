package com.assetsking.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

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
    // Material 3 的默认 secondary 是紫色；未显式覆盖时 FilterChip 等组件会
    // 在浅绿主题里突然出现紫块，破坏整套效果图的单一色相。
    secondary = primary,
    onSecondary = Color.White,
    secondaryContainer = primaryContainer,
    onSecondaryContainer = onPrimaryContainer,
    tertiary = primary,
    onTertiary = Color.White,
    tertiaryContainer = primaryContainer,
    onTertiaryContainer = onPrimaryContainer,
    background = SurfaceLight,
    surface = SurfaceLight,
    surfaceBright = GlassCardLight,
    surfaceDim = SurfaceVariantLight,
    surfaceContainerLowest = GlassCardLight,
    surfaceContainerLow = SurfaceLight,
    surfaceContainer = GlassCardLight,
    surfaceContainerHigh = SurfaceVariantLight,
    surfaceContainerHighest = SurfaceVariantLight,
    surfaceVariant = SurfaceVariantLight,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    inverseSurface = TextPrimaryLight,
    inverseOnSurface = SurfaceLight,
    inversePrimary = primary
)

// 龙巢深色占位（Codex 后续替换完整深色令牌）
private val LongNestScheme = darkColorScheme(
    primary = ThemePrimaryLongNest,
    onPrimary = Color(0xFF1C1C1E),
    secondary = ThemePrimaryLongNest,
    onSecondary = Color(0xFF1C1C1E),
    background = SurfaceDark,
    surface = SurfaceDark,
    surfaceContainer = GlassCardDark,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = OutlineDark
)

@Composable
fun AssetsKingTheme(
    theme: AppTheme = AppTheme.LIGHT_GREEN,
    transitionTo: AppTheme = theme,
    transitionProgress: Float = 1f,
    contentAlpha: Float = 1f,
    content: @Composable () -> Unit
) {
    val interpolated = lerpColorScheme(
        from = colorSchemeFor(theme),
        to = colorSchemeFor(transitionTo),
        fraction = transitionProgress
    )
    MaterialTheme(
        colorScheme = fadeContentColors(interpolated, contentAlpha),
        typography = AssetsKingTypography,
        shapes = AssetsKingShapes,
        content = content
    )
}

private fun fadeContentColors(scheme: ColorScheme, alpha: Float): ColorScheme {
    val value = alpha.coerceIn(0f, 1f)
    fun Color.faded() = copy(alpha = this.alpha * value)
    return scheme.copy(
        onPrimary = scheme.onPrimary.faded(),
        onPrimaryContainer = scheme.onPrimaryContainer.faded(),
        onSecondary = scheme.onSecondary.faded(),
        onSecondaryContainer = scheme.onSecondaryContainer.faded(),
        onTertiary = scheme.onTertiary.faded(),
        onTertiaryContainer = scheme.onTertiaryContainer.faded(),
        onBackground = scheme.onBackground.faded(),
        onSurface = scheme.onSurface.faded(),
        onSurfaceVariant = scheme.onSurfaceVariant.faded(),
        onError = scheme.onError.faded(),
        onErrorContainer = scheme.onErrorContainer.faded(),
        inverseOnSurface = scheme.inverseOnSurface.faded()
    )
}

private fun colorSchemeFor(theme: AppTheme): ColorScheme = when (theme) {
    AppTheme.LIGHT_GREEN -> lightScheme(ThemePrimaryGreen, ThemePrimaryGreenSoft, ThemePrimaryGreenSoftText)
    AppTheme.SKY_BLUE -> lightScheme(ThemePrimaryBlue, ThemePrimaryBlueSoft, ThemePrimaryBlueSoftText)
    AppTheme.VIOLET -> lightScheme(ThemePrimaryViolet, ThemePrimaryVioletSoft, ThemePrimaryVioletSoftText)
    AppTheme.WARM -> lightScheme(ThemePrimaryWarm, ThemePrimaryWarmSoft, ThemePrimaryWarmSoftText)
    AppTheme.LONG_NEST -> LongNestScheme
}

private fun lerpColorScheme(from: ColorScheme, to: ColorScheme, fraction: Float): ColorScheme {
    val t = fraction.coerceIn(0f, 1f)
    return from.copy(
        primary = lerp(from.primary, to.primary, t),
        onPrimary = lerp(from.onPrimary, to.onPrimary, t),
        primaryContainer = lerp(from.primaryContainer, to.primaryContainer, t),
        onPrimaryContainer = lerp(from.onPrimaryContainer, to.onPrimaryContainer, t),
        inversePrimary = lerp(from.inversePrimary, to.inversePrimary, t),
        secondary = lerp(from.secondary, to.secondary, t),
        onSecondary = lerp(from.onSecondary, to.onSecondary, t),
        secondaryContainer = lerp(from.secondaryContainer, to.secondaryContainer, t),
        onSecondaryContainer = lerp(from.onSecondaryContainer, to.onSecondaryContainer, t),
        tertiary = lerp(from.tertiary, to.tertiary, t),
        onTertiary = lerp(from.onTertiary, to.onTertiary, t),
        tertiaryContainer = lerp(from.tertiaryContainer, to.tertiaryContainer, t),
        onTertiaryContainer = lerp(from.onTertiaryContainer, to.onTertiaryContainer, t),
        background = lerp(from.background, to.background, t),
        onBackground = lerp(from.onBackground, to.onBackground, t),
        surface = lerp(from.surface, to.surface, t),
        onSurface = lerp(from.onSurface, to.onSurface, t),
        surfaceVariant = lerp(from.surfaceVariant, to.surfaceVariant, t),
        onSurfaceVariant = lerp(from.onSurfaceVariant, to.onSurfaceVariant, t),
        surfaceTint = lerp(from.surfaceTint, to.surfaceTint, t),
        inverseSurface = lerp(from.inverseSurface, to.inverseSurface, t),
        inverseOnSurface = lerp(from.inverseOnSurface, to.inverseOnSurface, t),
        error = lerp(from.error, to.error, t),
        onError = lerp(from.onError, to.onError, t),
        errorContainer = lerp(from.errorContainer, to.errorContainer, t),
        onErrorContainer = lerp(from.onErrorContainer, to.onErrorContainer, t),
        outline = lerp(from.outline, to.outline, t),
        outlineVariant = lerp(from.outlineVariant, to.outlineVariant, t),
        scrim = lerp(from.scrim, to.scrim, t),
        surfaceBright = lerp(from.surfaceBright, to.surfaceBright, t),
        surfaceDim = lerp(from.surfaceDim, to.surfaceDim, t),
        surfaceContainer = lerp(from.surfaceContainer, to.surfaceContainer, t),
        surfaceContainerHigh = lerp(from.surfaceContainerHigh, to.surfaceContainerHigh, t),
        surfaceContainerHighest = lerp(from.surfaceContainerHighest, to.surfaceContainerHighest, t),
        surfaceContainerLow = lerp(from.surfaceContainerLow, to.surfaceContainerLow, t),
        surfaceContainerLowest = lerp(from.surfaceContainerLowest, to.surfaceContainerLowest, t)
    )
}
