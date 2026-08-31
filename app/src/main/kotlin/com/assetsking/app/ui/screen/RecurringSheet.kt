package com.assetsking.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assetsking.database.CategoryEntity
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.app.ui.privacy.privacyFakeAmount
import com.assetsking.app.ui.privacy.privacyFakeDateTime
import com.assetsking.app.ui.privacy.privacyObfuscatedText
import com.assetsking.model.TransactionType
import com.assetsking.ui.component.ChipRow
import com.assetsking.ui.component.FormField
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.component.IconLibrary
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import com.assetsking.ui.privacy.LocalPrivacyEnabled
import com.assetsking.ui.theme.UiTokens
import java.util.Calendar
import java.util.UUID

private val intervals = listOf("MONTHLY" to "每月", "WEEKLY" to "每周", "DAILY" to "每天", "QUARTERLY" to "每季度", "YEARLY" to "每年")
private val recurringTypes = listOf(TransactionType.EXPENSE to "支出", TransactionType.INCOME to "收入")

@Composable
private fun RecurringFormCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier.padding(UiTokens.CardPadding)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
fun RecurringRulesSection(
    rules: List<RecurringRuleEntity>,
    categories: List<CategoryEntity>,
    transactions: List<com.assetsking.database.TransactionEntity> = emptyList(),
    onSave: (RecurringRuleEntity) -> Unit,
    onDelete: (String) -> Unit,
    onClaim: ((transactionId: String, ruleId: String) -> Unit)? = null,
    fixedType: TransactionType? = null
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    var showSheet by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<RecurringRuleEntity?>(null) }

    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("周期性账单", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Button(onClick = { showSheet = true }) { Text("＋ 新增") }
    }

    Spacer(Modifier.height(8.dp))

    if (rules.isEmpty()) {
        GlassCard(Modifier.fillMaxWidth()) {
            Text("暂无周期性账单", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("新增后会在到期日展示可认领的真实流水。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        rules.forEachIndexed { index, rule ->
            val reason = rule.note?.takeIf { it.isNotBlank() } ?: rule.merchant?.takeIf { it.isNotBlank() }
            val categoryName = rule.category.takeIf { it.isNotBlank() }
                ?.let { stored -> categories.firstOrNull { it.id == stored || it.name == stored }?.name ?: stored }
            val candidates = recurringMatchCandidates(rule, transactions)
            GlassCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentPadding = Modifier.padding(UiTokens.CardPadding)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            reason?.let { if (privacyEnabled) privacyObfuscatedText(it, 2500 + index) else it } ?: "周期性${rule.type}",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "${intervals.firstOrNull { it.first == rule.interval }?.second ?: rule.interval} · 下次 ${if (privacyEnabled) privacyFakeDateTime(2560 + index) else formatTime(rule.nextRunAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        if (privacyEnabled) privacyFakeAmount(2540 + index) else formatMoney(rule.amountCents),
                        fontWeight = FontWeight.Bold,
                        color = if (rule.type == TransactionType.EXPENSE.name) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        !rule.includeInBudget -> "不纳入月度预算"
                        categoryName != null -> "月度预算 · ${if (privacyEnabled) privacyObfuscatedText(categoryName, 2580 + index) else categoryName}"
                        else -> "纳入预算，但尚未选择分类"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (candidates.isNotEmpty() && onClaim != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("待认领 ${candidates.size} 笔", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    candidates.take(3).forEach { candidate ->
                        val label = candidate.note?.takeIf { it.isNotBlank() } ?: candidate.merchant ?: "未命名流水"
                        TextButton(onClick = { onClaim(candidate.id, rule.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text("认领 ${formatTime(candidate.occurredAt)} · $label · ${formatMoney(candidate.amountCents)}", modifier = Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editingRule = rule; showSheet = true }, modifier = Modifier.weight(1f)) { Text("编辑") }
                    OutlinedButton(onClick = { onDelete(rule.id) }, modifier = Modifier.weight(1f)) { Text("删除") }
                }
            }
        }
    }

    if (showSheet) {
        RecurringRuleSheet(
            existing = editingRule,
            categories = categories,
            fixedType = fixedType,
            onSave = { onSave(it); showSheet = false; editingRule = null },
            onDismiss = { showSheet = false; editingRule = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RecurringRuleSheet(
    existing: RecurringRuleEntity?,
    categories: List<CategoryEntity>,
    fixedType: TransactionType?,
    onSave: (RecurringRuleEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var amount by remember { mutableStateOf(existing?.let { "%.2f".format(it.amountCents / 100.0) } ?: "") }
    var type by remember {
        mutableStateOf(
            fixedType ?: existing?.let { runCatching { TransactionType.valueOf(it.type) }.getOrDefault(TransactionType.EXPENSE) }
                ?: TransactionType.EXPENSE
        )
    }
    val categoryKind = if (type == TransactionType.INCOME) "INCOME" else "EXPENSE"
    val availableCategories = categories.filter { !it.isArchived && it.kind == categoryKind }
    val existingCategory = availableCategories.firstOrNull { it.id == existing?.category || it.name == existing?.category }
    var parentCategoryId by remember(existing?.id, categoryKind) {
        mutableStateOf(existingCategory?.parentId ?: existingCategory?.id.orEmpty())
    }
    var categoryId by remember(existing?.id, categoryKind) {
        mutableStateOf(existingCategory?.id.orEmpty())
    }
    // 周期规则是扣款计划，不预设实际扣款归属；新建时账户/渠道/平台均留空。
    // 旧规则的这些字段继续保留在数据库中，编辑时不被覆盖。
    var reason by remember(existing?.id) {
        mutableStateOf(existing?.note?.takeIf { it.isNotBlank() } ?: existing?.merchant.orEmpty())
    }
    var includeInBudget by remember(existing?.id) { mutableStateOf(existing?.includeInBudget ?: true) }
    var isActive by remember(existing?.id) { mutableStateOf(existing?.isActive ?: true) }
    var interval by remember { mutableStateOf(existing?.interval ?: "MONTHLY") }
    val defaultNextRun = remember(interval) {
        val cal = Calendar.getInstance()
        when (interval) {
            "DAILY" -> cal.add(Calendar.DAY_OF_MONTH, 1)
            "WEEKLY" -> cal.add(Calendar.WEEK_OF_YEAR, 1)
            "MONTHLY" -> cal.add(Calendar.MONTH, 1)
            "QUARTERLY" -> cal.add(Calendar.MONTH, 3)
            "YEARLY" -> cal.add(Calendar.YEAR, 1)
        }
        cal.timeInMillis
    }
    var nextRunAt by remember { mutableStateOf(existing?.nextRunAt ?: defaultNextRun) }
    var showDatePicker by remember { mutableStateOf(false) }

    Sheet(title = if (existing != null) "编辑周期性账单" else "新增周期性账单", onDismiss = onDismiss) {
        Spacer(Modifier.height(8.dp))
        RecurringFormCard("计划信息", "只记录计划金额、类型和原因；实际账户在流水确认时确定") {
            FormField(value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } }, label = "预计金额")
            Spacer(Modifier.height(4.dp))
            Text("实际扣款认领允许在预计金额 ±20% 内，最终保留流水真实金额", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text("账单类型", fontWeight = FontWeight.Medium)
            if (fixedType == null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    recurringTypes.forEach { (value, label) ->
                        FilterChip(selected = type == value, onClick = { type = value }, label = { Text(label) })
                    }
                }
            } else {
                Text("支出", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            FormField(value = reason, onValueChange = { reason = it }, label = if (type == TransactionType.EXPENSE) "扣款原因" else "入账原因")
            Text("例如：房租、保险、会员；这是计划说明，不要求与实际商户相同", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(8.dp))
        val parentCategories = availableCategories.filter { it.parentId == null }.sortedBy { it.sortOrder }
        RecurringFormCard("预算归属", "可选；填写后计划金额会作为只读项目进入对应月份预算，实际流水仍按确认分类") {
            if (parentCategories.isEmpty()) {
                Text("暂无可用分类", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("一级分类", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    parentCategories.forEach { parent ->
                        FilterChip(selected = parent.id == parentCategoryId, onClick = {
                            parentCategoryId = parent.id
                            categoryId = availableCategories.filter { it.parentId == parent.id }.sortedBy { it.sortOrder }.firstOrNull()?.id ?: parent.id
                        }, label = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(IconLibrary.byKey(parent.iconKey), contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(parent.shortName)
                            }
                        })
                    }
                }
                val childCategories = availableCategories.filter { it.parentId == parentCategoryId }.sortedBy { it.sortOrder }
                if (childCategories.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("二级分类", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        childCategories.forEach { child -> FilterChip(selected = child.id == categoryId, onClick = { categoryId = child.id }, label = { Text(child.name) }) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = includeInBudget, onCheckedChange = { includeInBudget = it })
                Text("纳入月度预算", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(8.dp))
        RecurringFormCard("执行规则", "短月会落在当月最后一天，后续月份恢复原设定日期") {
            Text("重复周期", fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                intervals.forEach { (value, label) ->
                    FilterChip(selected = interval == value, onClick = { interval = value }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(10.dp))
            val dateFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("下一次执行", fontWeight = FontWeight.Medium)
                OutlinedButton(onClick = { showDatePicker = true }) { Text(dateFormat.format(java.util.Date(nextRunAt))) }
            }
        }
        if (showDatePicker) {
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = nextRunAt)
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { nextRunAt = it }
                        showDatePicker = false
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
            ) { DatePicker(state = datePickerState) }
        }

        Spacer(Modifier.height(8.dp))
        RecurringFormCard("状态", "关闭后保留规则和历史流水，不再生成新的待扣提示") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isActive, onCheckedChange = { isActive = it })
                Text("启用周期账单", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val cents = runCatching {
                    java.math.BigDecimal(amount.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                }.getOrNull() ?: return@Button
                if (cents <= 0) return@Button
                val categoryName = availableCategories.firstOrNull { it.id == categoryId }?.name
                onSave(
                    RecurringRuleEntity(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        accountId = existing?.accountId.orEmpty(),
                        amountCents = cents,
                        type = type.name,
                        category = categoryName ?: existing?.category.orEmpty(),
                        merchant = existing?.merchant,
                        note = reason.trim().takeIf { it.isNotEmpty() },
                        interval = interval,
                        nextRunAt = nextRunAt,
                        isActive = isActive,
                        channel = existing?.channel,
                        orderPlatform = existing?.orderPlatform,
                        includeInBudget = includeInBudget,
                        createdAt = existing?.createdAt?.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        firstRunAt = existing?.firstRunAt?.takeIf { it > 0L } ?: nextRunAt
                    )
                )
            },
            enabled = amount.toDoubleOrNull()?.let { it > 0 } == true && reason.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存") }
    }
}
