package com.assetsking.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assetsking.database.AccountEntity
import com.assetsking.model.AccountType
import com.assetsking.model.TransactionCategory
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val model: LedgerViewModel = viewModel(
                    factory = LedgerViewModel.factory((application as AssetsKingApplication).repository)
                )
                AssetsKingScreen(model)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssetsKingScreen(model: LedgerViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showNewRecord by remember { mutableStateOf(false) }
    val listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    Scaffold(
        topBar = { TopAppBar(title = { Text("资产大王", fontWeight = FontWeight.Black) }) },
        floatingActionButton = { FloatingActionButton(onClick = { showNewRecord = true }) { Text("记一笔") } }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!listenerEnabled) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("自动记账尚未开启", fontWeight = FontWeight.Bold)
                            Text("开启通知读取后，即使从最近任务划掉界面，系统仍可继续投递通知。")
                            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                                Text("去开启")
                            }
                        }
                    }
                }
            }
            item { OverviewCard(state) }
            item { Text("账户", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(state.accounts, key = { it.id }) { AccountRow(it) }
            item { Text("最近流水", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (state.transactions.isEmpty()) {
                item { Text("还没有流水，点右下角“记一笔”开始。") }
            } else {
                items(state.transactions.take(20), key = { it.id }) { transaction ->
                    val accountName = state.accounts.firstOrNull { it.id == transaction.accountId }?.name.orEmpty()
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(transaction.merchant ?: categoryLabel(TransactionCategory.valueOf(transaction.category)))
                            Text(formatMoney(transaction.amountCents), fontWeight = FontWeight.Bold)
                        }
                        Text("$accountName · ${transaction.type} · ${formatTime(transaction.occurredAt)}", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showNewRecord) {
        NewRecordSheet(
            accounts = state.accounts,
            categorize = model::categorize,
            onSaveTransaction = { account, amount, mode, category, merchant, note ->
                model.addTransaction(account, amount, mode, category, merchant, note)
                showNewRecord = false
            },
            onSaveTransfer = { from, to, amount, note ->
                model.addTransfer(from, to, amount, note)
                showNewRecord = false
            },
            onDismiss = { showNewRecord = false }
        )
    }
}

@Composable
private fun OverviewCard(state: LedgerUiState) {
    val assets = state.accounts.filter { it.type == AccountType.ASSET.name }.sumOf { it.balanceCents }
    val debts = state.accounts.filter { it.type != AccountType.ASSET.name }.sumOf { it.balanceCents }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("当前净资产", style = MaterialTheme.typography.labelLarge)
            Text(formatMoney(assets - debts), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text("银行卡 ${formatMoney(assets)}  ·  待还 ${formatMoney(debts)}")
            if (state.unprocessedNotifications > 0) Text("待处理通知 ${state.unprocessedNotifications} 条")
        }
    }
}

@Composable
private fun AccountRow(account: AccountEntity) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(account.name, fontWeight = FontWeight.Medium)
            Text(if (account.type == AccountType.ASSET.name) "资产账户" else "待还负债", style = MaterialTheme.typography.bodySmall)
        }
        Text(formatMoney(account.balanceCents), fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewRecordSheet(
    accounts: List<AccountEntity>,
    categorize: (String?, String?) -> TransactionCategory,
    onSaveTransaction: (String, String, RecordMode, TransactionCategory, String?, String?) -> Unit,
    onSaveTransfer: (String, String, String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(RecordMode.EXPENSE) }
    var amount by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var accountId by remember(accounts) { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }
    var targetId by remember(accounts) { mutableStateOf(accounts.drop(1).firstOrNull()?.id ?: accounts.firstOrNull()?.id.orEmpty()) }
    var category by remember { mutableStateOf(TransactionCategory.UNCATEGORIZED) }
    var categoryWasChosen by remember { mutableStateOf(false) }

    LaunchedEffect(merchant, note) {
        if (!categoryWasChosen) category = categorize(merchant, note)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text("记一笔", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(RecordMode.entries) { item ->
                        FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item.label) })
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("金额") },
                    prefix = { Text("¥") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }
            item { Text(if (mode == RecordMode.TRANSFER) "转出账户" else "账户", fontWeight = FontWeight.Medium) }
            item { AccountChips(accounts, accountId) { accountId = it } }
            if (mode == RecordMode.TRANSFER) {
                item { Text("转入账户", fontWeight = FontWeight.Medium) }
                item { AccountChips(accounts, targetId) { targetId = it } }
            } else {
                item {
                    OutlinedTextField(value = merchant, onValueChange = { merchant = it }, modifier = Modifier.fillMaxWidth(), label = { Text("商户/来源") }, singleLine = true)
                }
                if (mode == RecordMode.EXPENSE || mode == RecordMode.REFUND) {
                    item { Text("分类（可修改）", fontWeight = FontWeight.Medium) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(TransactionCategory.entries) { item ->
                                FilterChip(
                                    selected = category == item,
                                    onClick = { category = item; categoryWasChosen = true },
                                    label = { Text(categoryLabel(item)) }
                                )
                            }
                        }
                    }
                }
            }
            item { OutlinedTextField(value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(), label = { Text("备注（可选）") }) }
            item {
                Button(
                    onClick = {
                        if (mode == RecordMode.TRANSFER) onSaveTransfer(accountId, targetId, amount, note)
                        else onSaveTransaction(accountId, amount, mode, category, merchant, note)
                    },
                    enabled = amount.toDoubleOrNull()?.let { it > 0 } == true && accountId.isNotBlank() && (mode != RecordMode.TRANSFER || targetId != accountId),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存") }
            }
        }
    }
}

@Composable
private fun AccountChips(accounts: List<AccountEntity>, selected: String, onSelected: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(accounts, key = { it.id }) { account ->
            FilterChip(selected = selected == account.id, onClick = { onSelected(account.id) }, label = { Text(account.name) })
        }
    }
}

private fun categoryLabel(category: TransactionCategory) = when (category) {
    TransactionCategory.UNCATEGORIZED -> "未分类"
    TransactionCategory.DINING -> "餐饮"
    TransactionCategory.TRANSPORT -> "交通"
    TransactionCategory.SHOPPING -> "购物"
    TransactionCategory.HOUSING -> "住房"
    TransactionCategory.UTILITIES -> "生活缴费"
    TransactionCategory.MEDICAL -> "医疗"
    TransactionCategory.EDUCATION -> "教育"
    TransactionCategory.ENTERTAINMENT -> "娱乐"
    TransactionCategory.DIGITAL_SERVICES -> "数字服务"
    TransactionCategory.FINANCIAL_FEES -> "金融费用"
    TransactionCategory.OTHER -> "其他"
}

private fun formatMoney(cents: Long): String = NumberFormat.getCurrencyInstance(Locale.CHINA).format(cents / 100.0)
private fun formatTime(time: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(time))
