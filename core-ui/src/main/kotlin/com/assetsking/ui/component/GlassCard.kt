package com.assetsking.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.assetsking.ui.theme.CardShape

/**
 * 实体卡片（REQ 主题§2 / 首页UI§10-11）：浅色模式不透明白底 + 细灰边框 + 大圆角，
 * 删除毛玻璃/半透明叠层/背景模糊，基本不依赖阴影。全 App 卡片共用此组件。
 * 内容区有默认 20dp padding，可通过 [contentPadding] 覆盖。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    contentPadding: Modifier = Modifier.padding(20.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outline, CardShape)
            .then(contentPadding)
    ) {
        Column { content() }
    }
}
