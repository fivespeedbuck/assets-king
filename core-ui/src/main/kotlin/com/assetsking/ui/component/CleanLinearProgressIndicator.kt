package com.assetsking.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics

/** Material 3 进度语义与主题色不变，只移除 1.3.x 默认的无语义终点小球。 */
@Composable
fun CleanLinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val value = progress().coerceIn(0f, 1f)
    Canvas(
        modifier = modifier
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f) }
    ) {
        val radius = size.height / 2f
        drawRoundRect(
            color = trackColor,
            size = size,
            cornerRadius = CornerRadius(radius, radius)
        )
        if (value > 0f) {
            val width = size.width * value
            val barRadius = minOf(radius, width / 2f)
            drawRoundRect(
                color = color,
                size = Size(width, size.height),
                cornerRadius = CornerRadius(barRadius, barRadius)
            )
        }
    }
}
