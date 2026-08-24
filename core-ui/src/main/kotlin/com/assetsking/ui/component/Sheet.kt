package com.assetsking.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 通用底部弹窗壳 —— 标题 + 内容，样式统一。
 *
 * 长表单（贷款计划有 8 个输入框）必须能滚到底、键盘弹起时保存键仍够得着，
 * 否则下半部分显示不全、根本存不了。三件套缺一不可：
 *  - skipPartiallyExpanded：直接全屏展开，不停在半屏
 *  - verticalScroll：内容超过一屏可以滚
 *  - imePadding：软键盘顶起内容，不盖住底部保存键
 *
 * **content 里不能放 LazyColumn/LazyRow（纵向）**：外层 verticalScroll 给的是无限高约束，
 * Lazy 容器一测量就抛 IllegalStateException 直接闪退。列表用 Column + forEach，
 * 滚动交给这里的 verticalScroll。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    swipeToDismissEnabled: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = if (swipeToDismissEnabled) {
            { BottomSheetDefaults.DragHandle() }
        } else {
            null
        },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { nextValue ->
                swipeToDismissEnabled || nextValue != SheetValue.Hidden
            }
        )
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }
            content()
        }
    }
}
