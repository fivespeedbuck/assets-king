package com.assetsking.ui.theme

import androidx.compose.ui.graphics.Color

// ── Brand ──
val BrandGreen = Color(0xFF42D0A3)

// ── 浅色主题（REQ 主题§2：不透明实体卡片，删除毛玻璃/半透明叠层）──
val GlassCardLight = Color.White                     // 实体白卡
val GlassBorderLight = Color(0xFFE3E3E8)             // 卡片细边框（iOS 浅灰）
val SurfaceLight = Color(0xFFF2F2F7)                 // 浅灰白页面背景
val OutlineLight = Color(0xFFC7C7CC)                 // 输入框边框（修复记录批次 F：不再用毛玻璃色）

// ── 深色主题（龙巢预留：Codex 接管深色令牌与材质，这里是可运行占位）──
val GlassCardDark = Color(0xFF242426)                // 占位深色卡片（龙巢将替换为黑曜石层次）
val GlassBorderDark = Color(0xFF3A3A3C)
val SurfaceDark = Color(0xFF1C1C1E)
val OutlineDark = Color(0xFF48484A)

// ── 语义色（REQ 首页UI§16：固定不随主题切换）──
val IncomeGreen = Color(0xFF34C759)                  // 收入/正结余/完成
val ExpenseRed = Color(0xFFFF3B30)                   // 支出/欠款/赤字/错误
val PendingOrange = Color(0xFFFF9500)                // 待处理/临近到期
val InfoBlue = Color(0xFF007AFF)                     // 信息/补扫/系统状态
val TextPrimaryLight = Color(0xFF1D1D1F)
val TextSecondaryLight = Color(0xFF8E8E93)
val TextPrimaryDark = Color(0xFFF5F5F7)
val TextSecondaryDark = Color(0xFF98989D)

// ── 5 套主题的 primary（REQ 主题§1/§12）：浅绿默认 + 三套浅色变体；龙巢深色占位 ──
val ThemePrimaryGreen = Color(0xFF42D0A3)
val ThemePrimaryBlue = Color(0xFF4A90D9)
val ThemePrimaryViolet = Color(0xFF9B8AFB)
val ThemePrimaryWarm = Color(0xFFF2A93B)
val ThemePrimaryLongNest = Color(0xFFC9A24B)         // 哑光古金占位（龙巢最终色值由 Codex 定）
