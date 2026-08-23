package com.assetsking.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.assetsking.ui.theme.PrivacyGothicBorderMist
import com.assetsking.ui.theme.PrivacyGothicBorderSilver

/**
 * 隐秘模式统一卡片边框：细暗银主线、低透明内线和四角尖拱。
 *
 * 只负责绘制，不改变布局边界、点击区域或内容 padding；普通模式不会调用此 Modifier。
 */
fun Modifier.privacyGothicBorder(): Modifier = drawWithCache {
    val inset = 2.dp.toPx()
    val cut = 8.dp.toPx()
    val mainStroke = 0.9.dp.toPx()
    val detailStroke = 0.55.dp.toPx()
    val mainColor = PrivacyGothicBorderSilver.copy(alpha = 0.28f)
    val detailColor = PrivacyGothicBorderMist.copy(alpha = 0.14f)

    val outline = gothicCutPath(size.width, size.height, inset, cut)
    val innerOutline = gothicCutPath(
        size.width,
        size.height,
        inset + 3.dp.toPx(),
        cut * 0.68f
    )
    val corners = gothicCornerPath(size.width, size.height, inset, cut)

    onDrawWithContent {
        drawContent()
        drawPath(
            path = outline,
            color = mainColor,
            style = Stroke(width = mainStroke, join = StrokeJoin.Miter)
        )
        drawPath(
            path = innerOutline,
            color = detailColor,
            style = Stroke(width = detailStroke, join = StrokeJoin.Miter)
        )
        drawPath(
            path = corners,
            color = mainColor,
            style = Stroke(width = mainStroke, join = StrokeJoin.Miter)
        )
    }
}

private fun gothicCutPath(width: Float, height: Float, inset: Float, cut: Float): Path =
    Path().apply {
        moveTo(inset + cut, inset)
        lineTo(width - inset - cut, inset)
        lineTo(width - inset, inset + cut)
        lineTo(width - inset, height - inset - cut)
        lineTo(width - inset - cut, height - inset)
        lineTo(inset + cut, height - inset)
        lineTo(inset, height - inset - cut)
        lineTo(inset, inset + cut)
        close()
    }

private fun gothicCornerPath(width: Float, height: Float, inset: Float, cut: Float): Path =
    Path().apply {
        val left = inset
        val top = inset
        val right = width - inset
        val bottom = height - inset
        val point = cut * 0.38f

        // 四个克制的内向尖拱，不形成厚重花纹。
        moveTo(left, top + cut)
        lineTo(left + point, top + point)
        lineTo(left + cut, top)

        moveTo(right - cut, top)
        lineTo(right - point, top + point)
        lineTo(right, top + cut)

        moveTo(right, bottom - cut)
        lineTo(right - point, bottom - point)
        lineTo(right - cut, bottom)

        moveTo(left + cut, bottom)
        lineTo(left + point, bottom - point)
        lineTo(left, bottom - cut)

        // 每条长边一枚极短荆棘尖，保持统一节奏而不过度装饰。
        val sidePoint = cut * 0.375f
        val midX = width / 2f
        val midY = height / 2f
        moveTo(midX - sidePoint, top)
        lineTo(midX, top + sidePoint)
        lineTo(midX + sidePoint, top)
        moveTo(midX - sidePoint, bottom)
        lineTo(midX, bottom - sidePoint)
        lineTo(midX + sidePoint, bottom)
        moveTo(left, midY - sidePoint)
        lineTo(left + sidePoint, midY)
        lineTo(left, midY + sidePoint)
        moveTo(right, midY - sidePoint)
        lineTo(right - sidePoint, midY)
        lineTo(right, midY + sidePoint)
    }
