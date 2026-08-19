package com.assetsking.app.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import com.assetsking.database.WindfallEntity
import com.assetsking.ledger.DetectedRecurring
import com.assetsking.ledger.NecessaryLivingSuggestion
import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType
import com.assetsking.ui.component.ChipRow
import com.assetsking.ui.component.FormField
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.categoryLabel
import com.assetsking.ui.format.categoryLabelOrName
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
    listenerStatus: ListenerStatus = ListenerStatus.OK,
    notificationSources: Map<String, String> = emptyMap(),
    notificationWhitelist: Set<String> = emptySet(),
    onSetNotificationWhitelist: (Set<String>) -> Unit = {},
    necessaryLivingSuggestion: NecessaryLivingSuggestion? = null,
    detectedRecurring: List<DetectedRecurring> = emptyList(),
    uncategorized: Pair<Int, Long> = 0 to 0L,
    onConfirmDetectedRecurring: (DetectedRecurring) -> Unit = {},
    onRefreshSpendPatterns: () -> Unit = {},
    windfalls: List<WindfallEntity> = emptyList(),
    currentTotalDebtCents: Long = 0,
    onSaveWindfall: (WindfallEntity) -> Unit = {},
    onDeleteWindfall: (String) -> Unit = {},
    onMarkWindfallReceived: (String, Long, String) -> Unit = { _, _, _ -> },
    freeSpendingCents: Long = 50_000,
    onSetFreeSpending: (Long) -> Unit = {},
    themeKey: String? = null,
    onSetTheme: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var showBudgetSheet by remember { mutableStateOf(false) }
    var editingBudget by remember { mutableStateOf<BudgetEntity?>(null) }
    val scope = remember { CoroutineScope(Dispatchers.Main) }
    val reconcilePref = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val currentInterval = reconcilePref.getLong("reconciliation_interval_ms", 7 * 24 * 60 * 60 * 1000L)
    var reconcileDays by remember { mutableStateOf((currentInterval / (24 * 60 * 60 * 1000L)).toInt()) }
    var newCatName by remember { mutableStateOf("") }
    var showWindfall by remember { mutableStateOf(false) }

    val smsPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 月度规划：每月的钱怎么安排 ──
        item {
            Text("月度规划", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        }

        // ── V5 现金流设置 ──
        item {
            var incomeInput by remember {
                mutableStateOf(if (monthlyIncomeCents > 0) "%.2f".format(monthlyIncomeCents / 100.0) else "")
            }
            val currentMonth = java.time.YearMonth.now().toString()
            val monthBudgets = budgets.filter { it.month == currentMonth }
            val budgetSum = monthBudgets.sumOf { it.monthlyLimitCents }
            GlassCard {
                Text("每月预期收入与必要生活", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "预期收入只用于规划和稳定覆盖估算，不计入本月实际收入：\n" +
                        "稳定覆盖 = 预期收入 − 必要生活 − 本月必须还款",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                FormField(
                    value = incomeInput,
                    onValueChange = { incomeInput = it.filter { c -> c.isDigit() || c == '.' } },
                    label = "每月预期收入（规划用）",
                    isAmount = true
                )
                Spacer(Modifier.height(12.dp))
                // 自由开销额度（REQ 统计§12 / 设置§5：最常调整的规划参数之一）
                var freeInput by remember {
                    mutableStateOf(if (freeSpendingCents > 0) "%.2f".format(freeSpendingCents / 100.0) else "")
                }
                FormField(
                    value = freeInput,
                    onValueChange = { freeInput = it.filter { c -> c.isDigit() || c == '.' } },
                    label = "自由开销额度（每月）",
                    isAmount = true
                )
                TextButton(onClick = {
                    val cents = runCatching { java.math.BigDecimal(freeInput.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull()
                    if (cents != null && cents > 0) onSetFreeSpending(cents)
                }) { Text("保存自由开销") }
                Spacer(Modifier.height(12.dp))
                Text("必要生活（自动 = 分项预算之和）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                if (monthBudgets.isEmpty()) {
                    Text(
                        "还没设分项预算。去下面「月度预算」把吃饭/交通/住房等每项填上，这里自动合计。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    monthBudgets.forEach { b ->
                        val label = runCatching { TransactionCategory.valueOf(b.category) }.getOrNull()
                            ?.let { categoryLabel(it) } ?: b.category
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 1.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(label, style = MaterialTheme.typography.bodySmall)
                            Text(formatMoney(b.monthlyLimitCents), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("必要生活合计", fontWeight = FontWeight.Bold)
                    Text(formatMoney(budgetSum), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        runCatching {
                            java.math.BigDecimal(incomeInput.ifBlank { "0" }).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                        }.getOrNull()?.let { onSetMonthlyIncome(it) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存收入") }
            }
        }

        // ── 年终奖（从首页挪过来：不是天天看的东西）──
        item {
            GlassCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("年终奖", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showWindfall = true }) { Text("管理") }
                }
                Text(
                    "未到账的年终奖不算现金；到账后记收入并用于降债（铁律 8）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Budgets ──
        item {
            GlassCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("月度预算", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { showBudgetSheet = true }) { Text("＋ 新增") }
                }
                if (budgets.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("暂无预算设置", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    budgets.forEach { b ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (b.category == "ALL") "总预算" else
                                        runCatching { TransactionCategory.valueOf(b.category) }.getOrNull()?.let { categoryLabel(it) } ?: b.category,
                                    fontWeight = FontWeight.Medium
                                )
                                Text("${b.month} · 限额 ${formatMoney(b.monthlyLimitCents)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { editingBudget = b; showBudgetSheet = true }) { Text("编辑") }
                                OutlinedButton(onClick = { onDeleteBudget(b.id) }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }

        // ── Recurring ──
        item {
            GlassCard {
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
        }

        // ── 自动记账：管道与权限 ──
        item {
            Text("自动记账", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        }

        // ── System Check ──
        val notifListenerOk = listenerStatus == ListenerStatus.OK
        val notifPermOk = if (android.os.Build.VERSION.SDK_INT >= 33)
            context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
        else true
        val batteryOk = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
        val smsPermOk = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
        val allOk = notifListenerOk && notifPermOk && batteryOk && smsPermOk
        val failCount = listOf(notifListenerOk, notifPermOk, batteryOk, smsPermOk).count { !it }
        item {
            GlassCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("系统检查", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (allOk) "✅ 全部正常" else "❌ ${failCount}项异常",
                        color = if (allOk) Color(0xFF66BB6A) else Color(0xFFEF5350),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 三态：授权还在但没绑上时，以前这里是绿勾，实际一条都收不到
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("通知监听", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when (listenerStatus) {
                                ListenerStatus.OK -> "✅"
                                ListenerStatus.DISCONNECTED -> "⚠️ 已授权未连接"
                                ListenerStatus.DISABLED -> "❌"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (listenerStatus != ListenerStatus.OK) {
                            TextButton(onClick = { openListenerSettings(context) }) {
                                Text(if (listenerStatus == ListenerStatus.DISABLED) "去开启" else "去重新绑定")
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("通知权限", style = MaterialTheme.typography.bodyMedium)
                        Text(if (notifPermOk) "✅" else "❌", style = MaterialTheme.typography.bodySmall)
                        if (!notifPermOk) TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }) { Text("去开启") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("短信读取（兜底）", style = MaterialTheme.typography.bodyMedium)
                        Text(if (smsPermOk) "✅" else "❌", style = MaterialTheme.typography.bodySmall)
                        if (!smsPermOk) TextButton(onClick = {
                            smsPermLauncher.launch(Manifest.permission.RECEIVE_SMS)
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
        }

        // ── 通知来源白名单：只有打开的 app 才会被读取入库 ──
        item {
            GlassCard {
                Text("通知来源", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "只有打开的 app 会被读取记账，其它通知（聊天、外卖、系统）直接丢弃，不进待确认箱。" +
                        "银行 app 只要推过一次通知就会出现在这里，打开即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (notificationSources.isEmpty()) {
                    Text(
                        "还没收到任何通知。开启监听后随便让哪个 app 推一条，来源就会出现在这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // 已开启的排前面，方便一眼看到哪些在生效；默认只展示已开启的，点开才看全部
                    val sorted = notificationSources.entries.sortedWith(
                        compareByDescending<Map.Entry<String, String>> { it.key in notificationWhitelist }
                            .thenBy { it.value }
                    )
                    var showAll by remember { mutableStateOf(false) }
                    val visible = if (showAll) sorted else sorted.filter { it.key in notificationWhitelist }
                    visible.forEach { (pkg, label) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Switch(
                                checked = pkg in notificationWhitelist,
                                onCheckedChange = { on ->
                                    onSetNotificationWhitelist(
                                        if (on) notificationWhitelist + pkg else notificationWhitelist - pkg
                                    )
                                }
                            )
                        }
                    }
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(
                            if (showAll) "收起" else "查看全部 ${sorted.size} 个来源",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        // ── 主题（REQ 主题§1/§12：五套直接切换立即保存；升级保留原选择）──
        item {
            GlassCard {
                Column(Modifier.fillMaxWidth()) {
                    Text("主题", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.assetsking.ui.theme.AppTheme.entries.forEach { t ->
                            FilterChip(
                                selected = themeKey == t.key || (themeKey == null && t.key == "light_green"),
                                onClick = { onSetTheme(t.key) },
                                label = { Text(t.label) }
                            )
                        }
                    }
                    Text(
                        "龙巢是唯一深色主题，视觉稿与材质由 Codex 阶段接入；其余四套为浅色。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── 其他 ──
        item {
            Text("其他", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        }

        // ── Custom Categories ──
        item {
            GlassCard {
                Text("自定义分类", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FormField(value = newCatName, onValueChange = { newCatName = it }, label = "新分类名", modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        if (newCatName.isNotBlank()) {
                            onAddCustomCategory(newCatName.trim())
                            newCatName = ""
                        }
                    }) { Text("添加") }
                }
                if (customCategories.isEmpty()) {
                    Text("暂无自定义分类", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    customCategories.forEach { cat ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("· ${cat.name}", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { onDeleteCustomCategory(cat.name) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }

        // ── Reconciliation ──
        item {
            GlassCard {
                Text("对账", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
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
        }

        // ── Backup ──
        item {
            GlassCard {
            Text("数据管理", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
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
        }

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

    if (showWindfall) {
        WindfallSheet(
            windfalls = windfalls,
            accounts = accounts,
            currentTotalDebtCents = currentTotalDebtCents,
            onSave = onSaveWindfall,
            onDelete = onDeleteWindfall,
            onMarkReceived = onMarkWindfallReceived,
            onDismiss = { showWindfall = false }
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
