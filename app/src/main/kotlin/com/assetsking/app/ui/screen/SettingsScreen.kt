package com.assetsking.app.ui.screen

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assetsking.database.AccountEntity
import com.assetsking.database.BudgetEntity
import com.assetsking.database.CustomCategoryEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType
import com.assetsking.ui.component.ChipRow
import com.assetsking.ui.component.FormField
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.categoryLabel
import com.assetsking.ui.format.formatMoney
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

@Composable
fun SettingsScreen(
    budgets: List<BudgetEntity>,
    repository: LedgerRepository,
    recurringRules: List<RecurringRuleEntity>,
    accounts: List<AccountEntity>,
    customCategories: List<CustomCategoryEntity>,
    onSaveBudget: (BudgetEntity) -> Unit,
    onDeleteBudget: (String) -> Unit,
    onSaveRecurring: (RecurringRuleEntity) -> Unit,
    onDeleteRecurring: (String) -> Unit,
    onAddCustomCategory: (String) -> Unit,
    onDeleteCustomCategory: (String) -> Unit,
    monthlyIncomeCents: Long = 0,
    necessaryLivingCents: Long = 0,
    onSetMonthlyIncome: (Long) -> Unit = {},
    onSetNecessaryLiving: (Long) -> Unit = {},
    optionalCategories: Set<String> = emptySet(),
    onSetOptionalCategories: (Set<String>) -> Unit = {}
) {
    val context = LocalContext.current
    var showBudgetSheet by remember { mutableStateOf(false) }
    var editingBudget by remember { mutableStateOf<BudgetEntity?>(null) }
    val scope = remember { CoroutineScope(Dispatchers.Main) }
    val reconcilePref = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val currentInterval = reconcilePref.getLong("reconciliation_interval_ms", 7 * 24 * 60 * 60 * 1000L)
    var reconcileDays by remember { mutableStateOf((currentInterval / (24 * 60 * 60 * 1000L)).toInt()) }
    var newCatName by remember { mutableStateOf("") }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        val reader = BufferedReader(InputStreamReader(context.contentResolver.openInputStream(uri)))
                        val lines = reader.readLines()
                        reader.close()
                        var imported = 0
                        for (i in 1 until lines.size) {
                            val cols = lines[i].split(",")
                            if (cols.size < 5) continue
                            val amount = cols.getOrNull(3)?.trim()?.toDoubleOrNull() ?: continue
                            val merchant = cols.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }
                            val cents = (amount * 100).toLong()
                            if (cents <= 0) continue
                            val type = when (cols.getOrNull(1)?.trim()) {
                                "收入" -> TransactionType.INCOME
                                "退款" -> TransactionType.REFUND
                                else -> TransactionType.EXPENSE
                            }
                            val cat = cols.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() } ?: TransactionCategory.UNCATEGORIZED.name
                            try {
                                repository.addTransaction(
                                    accountId = accounts.firstOrNull()?.id ?: continue,
                                    amountCents = cents, type = type, category = cat,
                                    merchant = merchant,
                                    note = cols.getOrNull(5)?.trim()?.takeIf { it.isNotEmpty() }
                                )
                                imported++
                            } catch (_: Exception) { }
                        }
                        withContext(Dispatchers.Main) { Toast.makeText(context, "导入 $imported 条流水", Toast.LENGTH_SHORT).show() }
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) { Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── V5 现金流设置 ──
        item {
            var incomeInput by remember {
                mutableStateOf(if (monthlyIncomeCents > 0) "%.2f".format(monthlyIncomeCents / 100.0) else "")
            }
            var necessaryInput by remember {
                mutableStateOf(if (necessaryLivingCents > 0) "%.2f".format(necessaryLivingCents / 100.0) else "")
            }
            Text("V5 现金流设置", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "资金缺口 = 月收入 − 必要生活 − 本月必须还款。这两项是首页驾驶舱的数据基础。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            FormField(
                value = incomeInput,
                onValueChange = { incomeInput = it.filter { c -> c.isDigit() || c == '.' } },
                label = "稳定月收入（工资等）",
                isAmount = true
            )
            Spacer(Modifier.height(8.dp))
            FormField(
                value = necessaryInput,
                onValueChange = { necessaryInput = it.filter { c -> c.isDigit() || c == '.' } },
                label = "必要生活预算（吃饭/房租/水电/通勤）",
                isAmount = true
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    runCatching {
                        java.math.BigDecimal(incomeInput.ifBlank { "0" }).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                    }.getOrNull()?.let { onSetMonthlyIncome(it) }
                    runCatching {
                        java.math.BigDecimal(necessaryInput.ifBlank { "0" }).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                    }.getOrNull()?.let { onSetNecessaryLiving(it) }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存现金流设置") }
            Spacer(Modifier.height(8.dp))
            Text(
                "非必要消费分类（勾选后，这些分类的支出会计入实际缺口、占用自由消费）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TransactionCategory.entries.chunked(4).forEach { rowCats ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowCats.forEach { cat ->
                        androidx.compose.material3.FilterChip(
                            selected = cat.name in optionalCategories,
                            onClick = {
                                onSetOptionalCategories(
                                    if (cat.name in optionalCategories) optionalCategories - cat.name
                                    else optionalCategories + cat.name
                                )
                            },
                            label = { Text(categoryLabel(cat)) }
                        )
                    }
                }
            }
        }

        // ── System Check ──
        val notifListenerOk = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        val notifPermOk = if (android.os.Build.VERSION.SDK_INT >= 33)
            context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
        else true
        val batteryOk = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
        val allOk = notifListenerOk && notifPermOk && batteryOk
        val failCount = listOf(notifListenerOk, notifPermOk, batteryOk).count { !it }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("系统检查", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (allOk) "✅ 全部正常" else "❌ ${failCount}项异常",
                    color = if (allOk) Color(0xFF66BB6A) else Color(0xFFEF5350),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("通知监听", style = MaterialTheme.typography.bodyMedium)
                    Text(if (notifListenerOk) "✅" else "❌", style = MaterialTheme.typography.bodySmall)
                    if (!notifListenerOk) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("去开启") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("通知权限", style = MaterialTheme.typography.bodyMedium)
                    Text(if (notifPermOk) "✅" else "❌", style = MaterialTheme.typography.bodySmall)
                    if (!notifPermOk) TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }) { Text("去开启") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("电池优化豁免", style = MaterialTheme.typography.bodyMedium)
                    Text(if (batteryOk) "✅" else "❌", style = MaterialTheme.typography.bodySmall)
                    if (!batteryOk) TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(android.net.Uri.parse("package:${context.packageName}")))
                    }) { Text("去设置") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("自启动", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        try { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { }
                        Toast.makeText(context, "请搜索「自启动」并允许资产大王后台运行", Toast.LENGTH_LONG).show()
                    }) { Text("设置指南") }
                }
            }
        }

        // ── Budgets ──
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("月度预算", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Button(onClick = { showBudgetSheet = true }) { Text("＋ 新增") }
            }
        }
        if (budgets.isEmpty()) {
            item { Text("暂无预算设置", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(budgets, key = { it.id }) { b ->
                val cat = runCatching { TransactionCategory.valueOf(b.category) }.getOrDefault(TransactionCategory.UNCATEGORIZED)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (b.category == "ALL") "总预算" else categoryLabel(cat),
                            fontWeight = FontWeight.Medium
                        )
                        Text("${b.month} · 限额 ${formatMoney(b.monthlyLimitCents)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { editingBudget = b; showBudgetSheet = true }) { Text("编辑") }
                        OutlinedButton(onClick = { onDeleteBudget(b.id) }) { Text("删除") }
                    }
                }
                HorizontalDivider()
            }
        }

        // ── Custom Categories ──
        item { Spacer(Modifier.height(16.dp)) }
        item { Text("自定义分类", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FormField(value = newCatName, onValueChange = { newCatName = it }, label = "新分类名", modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (newCatName.isNotBlank()) {
                        onAddCustomCategory(newCatName.trim())
                        newCatName = ""
                    }
                }) { Text("添加") }
            }
        }
        if (customCategories.isEmpty()) {
            item { Text("暂无自定义分类", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        } else {
            items(customCategories) { cat ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("· ${cat.name}", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onDeleteCustomCategory(cat.name) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        // ── Recurring ──
        item {
            RecurringRulesSection(
                rules = recurringRules,
                accounts = accounts,
                onSave = onSaveRecurring,
                onDelete = onDeleteRecurring
            )
            // 固定收支汇总
            val fixedExpenses = recurringRules.filter { it.isActive && it.type == "EXPENSE" }
            val fixedIncomes = recurringRules.filter { it.isActive && it.type == "INCOME" || it.type == "REFUND" }
            if (fixedExpenses.isNotEmpty() || fixedIncomes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                if (fixedIncomes.isNotEmpty()) {
                    val incomeTotal = fixedIncomes.sumOf { it.amountCents }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("固定收入合计", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("+${formatMoney(incomeTotal)}", color = Color(0xFF66BB6A), fontWeight = FontWeight.Bold)
                    }
                }
                if (fixedExpenses.isNotEmpty()) {
                    val subTotal = fixedExpenses.filter { it.isSubscription }.sumOf { it.amountCents }
                    val fixedTotal = fixedExpenses.sumOf { it.amountCents }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("固定支出合计", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("-${formatMoney(fixedTotal)}", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold)
                    }
                    if (subTotal > 0) {
                        Text("含订阅 ${formatMoney(subTotal)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ── Reconciliation ──
        item { Spacer(Modifier.height(16.dp)) }
        item { Text("对账", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("对账周期")
                ChipRow(
                    items = listOf(3, 7, 14, 30),
                    selected = reconcileDays,
                    onSelected = {
                        reconcileDays = it
                        reconcilePref.edit().putLong("reconciliation_interval_ms", it * 24 * 60 * 60 * 1000L).apply()
                    },
                    label = { days -> when (days) { 3 -> "3天"; 7 -> "每周"; 14 -> "两周"; 30 -> "每月"; else -> "${days}天" } },
                    id = { days -> days.toString() }
                )
            }
        }

        // ── Backup ──
        item { Spacer(Modifier.height(16.dp)) }
        item { Text("数据管理", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
        item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { csvLauncher.launch("text/*") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("从 CSV 导入流水") }
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val json = repository.exportAllData()
                                    withContext(Dispatchers.Main) {
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(Intent.EXTRA_TEXT, json)
                                            putExtra(Intent.EXTRA_SUBJECT, "assets-king-backup.json")
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, "导出 JSON"))
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("JSON") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val csv = repository.exportCsvTransactions()
                                    withContext(Dispatchers.Main) {
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/csv"
                                            putExtra(Intent.EXTRA_TEXT, csv)
                                            putExtra(Intent.EXTRA_SUBJECT, "assets-king-transactions.csv")
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, "导出 CSV"))
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("CSV") }
                }
                OutlinedButton(
                    onClick = {
                        Toast.makeText(context, "导入：将 JSON 备份文件放到 Downloads 目录后点击", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("导入数据") }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
        item {
            Text(
                "资产大王 v0.1.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showBudgetSheet) {
        BudgetSheet(
            existing = editingBudget,
            onSave = { onSaveBudget(it); showBudgetSheet = false; editingBudget = null },
            onDismiss = { showBudgetSheet = false; editingBudget = null }
        )
    }
}

@Composable
private fun BudgetSheet(
    existing: BudgetEntity?,
    onSave: (BudgetEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val allCat = TransactionCategory.entries
    var selectedCategory by remember {
        mutableStateOf(
            existing?.let {
                runCatching { TransactionCategory.valueOf(it.category) }.getOrDefault(TransactionCategory.UNCATEGORIZED)
            } ?: TransactionCategory.UNCATEGORIZED
        )
    }
    var limit by remember {
        mutableStateOf(existing?.let { "%.2f".format(it.monthlyLimitCents / 100.0) } ?: "")
    }
    var month by remember {
        mutableStateOf(existing?.month ?: java.time.YearMonth.now().toString())
    }

    Sheet(title = if (existing != null) "编辑预算" else "新增预算", onDismiss = onDismiss) {
        Text("分类", fontWeight = FontWeight.Medium)
        ChipRow(
            items = allCat,
            selected = selectedCategory,
            onSelected = { selectedCategory = it },
            label = { categoryLabel(it) },
            id = { it.name }
        )

        Spacer(Modifier.height(8.dp))
        FormField(
            value = limit,
            onValueChange = { limit = it.filter { c -> c.isDigit() || c == '.' } },
            label = "月度限额（元）"
        )

        Spacer(Modifier.height(8.dp))
        FormField(value = month, onValueChange = { month = it }, label = "月份（yyyy-MM）")

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val cents = runCatching {
                    java.math.BigDecimal(limit.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                }.getOrNull() ?: return@Button
                if (cents <= 0) return@Button
                onSave(
                    BudgetEntity(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        category = selectedCategory.name,
                        monthlyLimitCents = cents,
                        month = month.trim()
                    )
                )
            },
            enabled = limit.toDoubleOrNull()?.let { it > 0 } == true,
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存") }
    }
}
