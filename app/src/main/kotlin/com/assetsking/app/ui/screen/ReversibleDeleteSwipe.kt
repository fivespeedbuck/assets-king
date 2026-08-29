package com.assetsking.app.ui.screen

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.assetsking.ui.theme.ExpenseRed
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 左滑只驻留操作按钮，不允许滑动本身触发删除。 */
@Composable
internal fun ReversibleDeleteSwipe(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val revealPx = with(LocalDensity.current) { 96.dp.toPx() }
    val scope = rememberCoroutineScope()
    var offsetX by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    fun settle(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(initialValue = offsetX, targetValue = target) { value, _ -> offsetX = value }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ExpenseRed)
            .pointerInput(revealPx) {
                detectHorizontalDragGestures(
                    onDragStart = { settleJob?.cancel() },
                    onDragCancel = { settle(if (offsetX <= -revealPx * 0.35f) -revealPx else 0f) },
                    onDragEnd = { settle(if (offsetX <= -revealPx * 0.35f) -revealPx else 0f) },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount).coerceIn(-revealPx, 0f)
                    }
                )
            }
    ) {
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            if (offsetX < -1f) {
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .fillMaxHeight()
                        .clickable {
                            settle(0f)
                            onDelete()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Text(
                        "删除",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Box(Modifier.offset { IntOffset(offsetX.roundToInt(), 0) }) { content() }
    }
}
