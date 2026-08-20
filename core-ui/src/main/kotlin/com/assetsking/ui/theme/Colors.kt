package com.assetsking.ui.theme

import androidx.compose.ui.graphics.Color

// ── Brand ──
val BrandGreen = Color(0xFF42D0A3)

// ── 浅色主题（REQ 主题§2：不透明实体卡片，删除毛玻璃/半透明叠层）──
val GlassCardLight = Color.White                     // 实体白卡
val GlassBorderLight = Color(0xFFDBE5E1)             // 原浅绿色效果图卡片细边框
val SurfaceLight = Color(0xFFF7F8F8)                 // 浅灰白页面背景（对齐碳水大王 container #F7F8F8）
val OutlineLight = Color(0xFFDBE5E1)                 // 原浅绿色效果图输入框边框
val SurfaceVariantLight = Color(0xFFEEF3F1)          // 进度轨道/辅助表面，避免 Material 默认紫灰
val OutlineVariantLight = Color(0xFFDBE5E1)          // 辅助边框，保持浅绿色基线

// ── 深色主题（龙巢预留：Codex 接管深色令牌与材质，这里是可运行占位）──
val GlassCardDark = Color(0xFF242426)                // 占位深色卡片（龙巢将替换为黑曜石层次）
val GlassBorderDark = Color(0xFF3A3A3C)
val SurfaceDark = Color(0xFF1C1C1E)
val OutlineDark = Color(0xFF48484A)

// ── 语义色（REQ 首页UI§16：固定不随主题切换）──
val IncomeGreen = Color(0xFF22A06B)                  // 收入/正结余/完成
val ExpenseRed = Color(0xFFE4573D)                   // 支出/欠款/赤字/错误
val PendingOrange = Color(0xFFFF9500)                // 待处理/临近到期
val InfoBlue = Color(0xFF007AFF)                     // 信息/补扫/系统状态
val TextPrimaryLight = Color(0xFF182420)             // 对齐碳水大王 on_surface #182420
val TextSecondaryLight = Color(0xFF6B7A75)           // 原浅绿色效果图次要文字
val TextPrimaryDark = Color(0xFFF5F5F7)
val TextSecondaryDark = Color(0xFF98989D)

// ── 4 套浅色主题令牌（REQ 主题§1/§12）：对齐碳水大王真实色值，浅绿默认 ──
// 映射：primary→colorScheme.primary，soft→primaryContainer，soft_text→onPrimaryContainer
val ThemePrimaryGreen = Color(0xFF2A806B)
val ThemePrimaryGreenSoft = Color(0xFFF1F7F5)
val ThemePrimaryGreenSoftText = Color(0xFF19634F)

val ThemePrimaryBlue = Color(0xFF438BD1)
val ThemePrimaryBlueSoft = Color(0xFFEEF5FC)
val ThemePrimaryBlueSoftText = Color(0xFF2E6FA9)

val ThemePrimaryViolet = Color(0xFF8464C2)
val ThemePrimaryVioletSoft = Color(0xFFF5F1FA)
val ThemePrimaryVioletSoftText = Color(0xFF664A9A)

val ThemePrimaryWarm = Color(0xFFC4932E)
val ThemePrimaryWarmSoft = Color(0xFFFBF5E9)
val ThemePrimaryWarmSoftText = Color(0xFF8A671C)

val ThemePrimaryLongNest = Color(0xFFC9A24B)         // 哑光古金占位（龙巢最终色值由 Codex 定）
