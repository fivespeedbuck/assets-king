package com.assetsking.app.ui.screen

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.material3.Button
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
    optionalCategories: Set<String> = emptySet(),
    onSetOptionalCategories: (Set<String>) -> Unit = {},
    listenerStatus: ListenerStatus = ListenerStatus.OK,
    notificationSources: Map<String, String> = emptyMap(),
    notificationWhitelist: Set<String> = emptySet(),
    onSetNotificationWhitelist: (Set<String>) -> Unit = {},
    necessaryLivingSuggestion: NecessaryLivingSuggestion? = null,
    detectedRecurring: List<DetectedRecurring> = emptyList(),
    uncategorized: Pair<Int, Long> = 0 to 0L,
    onConfirmDetectedRecurring: (DetectedRecurring) -> Unit = {},
    onRefreshSpendPatterns: () -> Unit = {}
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
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── V5 现金流设置 ──
        item {
            var incomeInput by remember {
                mutableStateOf(if (monthlyIncomeCents > 0) "%.2f".format(monthlyIncomeCents / 100.0) else "")
            }
            var necessaryInput by remember {
                mutableStateOf(if (necessaryLivingCents > 0) "%.2f".format(necessaryLivingCents / 100.0) else "")
            }
            GlassCard {
                Text("每月现金流", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "首页的「资金缺口 / 自由消费 / 今日上限」都从这两个数字算出来：\n" +
                        "缺口 = 月收入 − 必要生活 − 本月必须还款",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                FormField(
                    value = incomeInput,
                    onValueChange = { incomeInput = it.filter { c -> c.isDigit() || c == '.' } },
                    label = "稳定月收入（工资等）",
                    isAmount = true
                )
                Spacer(Modifier.height(12.dp))
                FormField(
                    value = necessaryInput,
                    onValueChange = { necessaryInput = it.filter { c -> c.isDigit() || c == '.' } },
                    label = "必要生活（吃饭/房租/水电/通勤）",
                    isAmount = true
                )

                // 这个数字用户自己填不准——用实际流水算一个出来，点一下就填进去
                Spacer(Modifier.height(8.dp))
                val suggestion = necessaryLivingSuggestion
                if (suggestion != null && suggestion.hasData) {
                    var showBreakdown by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "按你近${suggestion.monthsUsed}个月实际：${formatMoney(suggestion.totalCents)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "每个分类取月度中位数，一次性大额（换手机、看牙）不会算进来",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            necessaryInput = "%.2f".format(suggestion.totalCents / 100.0)
                        }) { Text("用这个") }
                    }
                    TextButton(onClick = { showBreakdown = !showBreakdown }) {
                        Text(if (showBreakdown) "收起明细 ▲" else "看看这钱花在哪 ▼", style = MaterialTheme.typography.labelSmall)
                    }
                    if (showBreakdown) {
                        suggestion.byCategoryCents.forEach { (cat, cents) ->
                            Row(
                                Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(categoryLabelOrName(cat, customCategories.map { it.name }), style = MaterialTheme.typography.bodySmall)
                                Text(formatMoney(cents), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                } else {
                    Text(
                        "还没有满月的流水，先估一个填进去。等自动记账攒够一个月，这里会按实际算给你看。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val (uncatCount, uncatCents) = uncategorized
                if (uncatCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "本月还有 $uncatCount 笔未分类（${formatMoney(uncatCents)}）没算进上面的建议——分类之后会更准",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(12.dp))
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
                ) { Text("保存") }
            }
        }

        // ── 每笔支出算哪一边（原来那排看不懂的按钮）──
        item {
            GlassCard {
                Text("哪些消费算「零花钱」", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "你每笔支出只会算到一边：\n" +
                        "· 关掉的分类＝必要生活，不逐笔扣，已经包在上面那个预算里\n" +
                        "· 打开的分类＝享受型消费，花一笔首页的「自由消费」和「今日上限」就少一笔",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "例：自由消费还剩 ¥1,500，喝杯 ¥20 奶茶（娱乐已打开）→ 立刻变 ¥1,480。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                TransactionCategory.entries.forEach { cat ->
                    val isOptional = cat.name in optionalCategories
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(categoryLabel(cat), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (isOptional) "享受型 · 逐笔扣自由消费" else "必要生活 · 包在预算里",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOptional) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isOptional,
                            onCheckedChange = {
                                onSetOptionalCategories(
                                    if (isOptional) optionalCategories - cat.name else optionalCategories + cat.name
                                )
                            }
                        )
                    }
                }
            }
        }

        // ── 从流水里认出来的固定扣款 ──
        item {
            GlassCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("疑似固定扣款", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onRefreshSpendPatterns) { Text("重新扫描") }
                }
                Text(
                    "从流水里找「同商户 + 金额稳定 + 约一个月一次 + 至少 3 次」的扣款。" +
                        "确认后进周期性账单参与预测；真实扣款到账时会认领已有那笔，不会重复记账。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (detectedRecurring.isEmpty()) {
                    Text(
                        "暂时没认出来。需要同一个商户至少扣过 3 次才能确定是固定扣款，先让自动记账攒几个月。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    detectedRecurring.forEach { d ->
                        val account = accounts.firstOrNull { it.id == d.accountId }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${d.merchant} · ${formatMoney(d.amountCents)}/月", fontWeight = FontWeight.Medium)
                                Text(
                                    "${account?.name ?: "?"} · 每月${d.dayOfMonth}日前后 · 已扣${d.occurrences}次",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(onClick = { onConfirmDetectedRecurring(d) }) { Text("确认") }
                        }
                    }
                }
            }
        }

        // ── System Check ──
        val notifListenerOk = listenerStatus == ListenerStatus.OK
        val notifPermOk = if (android.os.Build.VERSION.SDK_INT >= 33)
            context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
        else true
        val batteryOk = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
        val allOk = notifListenerOk && notifPermOk && batteryOk
        val failCount = listOf(notifListenerOk, notifPermOk, batteryOk).count { !it }
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
                    // 已开启的排前面，方便一眼看到哪些在生效
                    val sorted = notificationSources.entries.sortedWith(
                        compareByDescending<Map.Entry<String, String>> { it.key in notificationWhitelist }
                            .thenBy { it.value }
                    )
                    sorted.forEach { (pkg, label) ->
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
                }
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
                        val cat = runCatching { TransactionCategory.valueOf(b.category) }.getOrDefault(TransactionCategory.UNCATEGORIZED)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
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
                    }
                }
            }
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
