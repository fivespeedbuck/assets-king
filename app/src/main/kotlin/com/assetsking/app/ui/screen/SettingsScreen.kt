package com.assetsking.app.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.assetsking.app.BuildConfig
import com.assetsking.app.UpdateChecker
import com.assetsking.app.UpdateInstaller
import com.assetsking.app.notification.AssetsNotificationListenerService
import com.assetsking.app.notification.VaultRuntimeStatus
import com.assetsking.app.ui.privacy.privacyFakeAmount
import com.assetsking.app.ui.privacy.privacyFakeCount
import com.assetsking.app.ui.privacy.privacyFakeDateTime
import com.assetsking.app.ui.privacy.privacyFakeYearMonth
import com.assetsking.app.ui.privacy.privacyObfuscatedText
import com.assetsking.database.AccountEntity
import com.assetsking.database.BudgetEntity
import com.assetsking.database.CategoryEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.database.TransactionEntity
import com.assetsking.database.TransferEntity
import com.assetsking.ledger.SmsSenderWhitelist
import com.assetsking.model.TransactionCategory
import com.assetsking.ui.component.ChipRow
import com.assetsking.ui.component.FormField
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.component.Sheet
import com.assetsking.ui.format.categoryLabel
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import com.assetsking.ui.privacy.LocalPrivacyEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

private fun formatUpdateBytes(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f MB", bytes.coerceAtLeast(0L) / 1024.0 / 1024.0)

private fun parseSmsSenderWhitelistInput(input: String): Set<String>? {
    val senders = input
        .split(',', '\n', '\r')
        .map(String::trim)
        .filter(String::isNotEmpty)
    if (senders.isEmpty() || senders.any { it.length !in 3..20 || it.any { c -> !c.isDigit() } }) {
        return null
    }
    return senders.toSet()
}

internal enum class SettingsPipelineSeverity { OK, WARNING, ERROR }

internal enum class SettingsHomeBehavior { SECONDARY_PAGE }

internal enum class SettingsSection(val title: String) {
    MONTHLY_PLAN("月度规划"),
    AUTOMATIC_INTAKE("自动入库"),
    DATA_PRIVACY("数据与隐私")
}

internal data class SettingsHomeSectionSpec(
    val section: SettingsSection,
    val behavior: SettingsHomeBehavior
)

internal val settingsHomeSectionSpecs = listOf(
    SettingsHomeSectionSpec(SettingsSection.MONTHLY_PLAN, SettingsHomeBehavior.SECONDARY_PAGE),
    SettingsHomeSectionSpec(SettingsSection.AUTOMATIC_INTAKE, SettingsHomeBehavior.SECONDARY_PAGE),
    SettingsHomeSectionSpec(SettingsSection.DATA_PRIVACY, SettingsHomeBehavior.SECONDARY_PAGE)
)

internal const val GUARDIAN_MODE_QUOTE =
    "我们是守护者，也是一群时刻对抗着危险和疯狂的可怜虫。——邓恩·史密斯"

internal fun settingsMutationEnabled(privacyEnabled: Boolean): Boolean = !privacyEnabled

internal fun settingsPipelineSeverity(
    listenerStatus: ListenerStatus,
    runtimeStatus: VaultRuntimeStatus,
    notificationPermissionGranted: Boolean,
    smsFallbackGranted: Boolean,
    batteryExemptionGranted: Boolean
): SettingsPipelineSeverity = when {
    listenerStatus != ListenerStatus.OK || runtimeStatus == VaultRuntimeStatus.ERROR -> SettingsPipelineSeverity.ERROR
    !notificationPermissionGranted || !smsFallbackGranted || !batteryExemptionGranted -> SettingsPipelineSeverity.WARNING
    else -> SettingsPipelineSeverity.OK
}

@Composable
private fun SettingsCapabilityRow(
    label: String,
    hint: String,
    status: String,
    statusColor: Color,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(status, color = statusColor, style = MaterialTheme.typography.bodySmall)
            actionLabel?.let { action ->
                TextButton(onClick = onAction, enabled = actionEnabled) { Text(action) }
            }
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .defaultMinSize(minHeight = 64.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "进入$title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GuardianModeCard() {
    GlassCard(Modifier.fillMaxWidth(), contentPadding = Modifier) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                GUARDIAN_MODE_QUOTE,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Switch(
                checked = true,
                onCheckedChange = null
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置")
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SettingsScreen(
    budgets: List<BudgetEntity>,
    categories: List<CategoryEntity>,
    repository: LedgerRepository,
    accounts: List<AccountEntity>,
    onSaveBudget: (BudgetEntity) -> Unit,
    onDeleteBudget: (String) -> Unit,
    monthlyIncomeCents: Long = 0,
    onSetMonthlyIncome: (Long) -> Unit = {},
    listenerStatus: ListenerStatus = ListenerStatus.OK,
    lastReceivedAt: Long = 0L,
    notificationSources: Map<String, String> = emptyMap(),
    notificationWhitelist: Set<String> = emptySet(),
    onSetNotificationWhitelist: (Set<String>) -> Unit = {},
    smsSenderWhitelist: Set<String> = emptySet(),
    onSetSmsSenderWhitelist: (Set<String>) -> Unit = {},
    freeSpendingCents: Long = 50_000,
    onSetFreeSpending: (Long) -> Unit = {},
    deletedTransactions: List<TransactionEntity> = emptyList(),
    deletedTransfers: List<TransferEntity> = emptyList(),
    onRestoreTransaction: (String, (Result<Unit>) -> Unit) -> Unit = { _, callback -> callback(Result.failure(IllegalStateException("恢复服务未连接"))) },
    onPermanentlyDeleteTransaction: (String, (Result<Unit>) -> Unit) -> Unit = { _, callback -> callback(Result.failure(IllegalStateException("删除服务未连接"))) },
    onRestoreTransfer: (String, (Result<Unit>) -> Unit) -> Unit = { _, callback -> callback(Result.failure(IllegalStateException("恢复划转服务未连接"))) },
    onPermanentlyDeleteTransfer: (String, (Result<Unit>) -> Unit) -> Unit = { _, callback -> callback(Result.failure(IllegalStateException("删除划转服务未连接"))) },
    onRootStateChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val privacyEnabled = LocalPrivacyEnabled.current
    val settingsWritable = settingsMutationEnabled(privacyEnabled)
    var showBudgetSheet by remember { mutableStateOf(false) }
    var editingBudget by remember { mutableStateOf<BudgetEntity?>(null) }
    val scope = remember { CoroutineScope(Dispatchers.Main) }
    val reconcilePref = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val currentInterval = reconcilePref.getLong("reconciliation_interval_ms", 7 * 24 * 60 * 60 * 1000L)
    var reconcileDays by remember { mutableStateOf((currentInterval / (24 * 60 * 60 * 1000L)).toInt()) }
    var backupMsg by remember { mutableStateOf("") }
    var smsSenderInput by remember(smsSenderWhitelist) {
        mutableStateOf(smsSenderWhitelist.joinToString("\n"))
    }
    var smsWhitelistMessage by remember { mutableStateOf<String?>(null) }
    var smsWhitelistMessageIsError by remember { mutableStateOf(false) }
    // 检查更新（REQ 设置§13）
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf("") }
    var latestRelease by remember { mutableStateOf<UpdateChecker.Release?>(null) }
    var downloadingRelease by remember { mutableStateOf<UpdateChecker.Release?>(null) }
    var downloadedUpdateApk by remember { mutableStateOf<File?>(null) }
    var updateDownloadedBytes by remember { mutableStateOf(0L) }
    var updateTotalBytes by remember { mutableStateOf(0L) }
    var guideDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var activeSection by remember { mutableStateOf<SettingsSection?>(null) }
    var showTransactionTrash by remember { mutableStateOf(false) }
    LaunchedEffect(activeSection) { onRootStateChanged(activeSection == null) }

    BackHandler(enabled = activeSection != null) { activeSection = null }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && settingsWritable) {
            scope.launch {
                val ok = repository.restoreFromPicked(uri, repository.backupPin())
                if (ok) {
                    // 恢复完成：杀掉进程重启，让 Room 重新打开恢复后的库（ponytail: 粗暴但可靠）
                    android.os.Process.killProcess(android.os.Process.myPid())
                } else {
                    backupMsg = "恢复失败：检查备份密码与文件"
                }
            }
        }
    }

    val smsPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val installPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val apk = downloadedUpdateApk
        if (apk != null && UpdateInstaller.canRequestPackageInstalls(context)) {
            runCatching { UpdateInstaller.launchSystemInstaller(context, apk) }
                .onSuccess {
                    downloadedUpdateApk = null
                    updateMsg = "安装包已校验，已打开系统安装器"
                }
                .onFailure { updateMsg = it.message ?: "无法打开系统安装器" }
        } else if (apk != null) {
            updateMsg = "安装权限未开启，安装包已保留"
        }
    }

    // ON_RESUME 重读：授权弹窗关掉后回来立即刷新（REQ 监听§14）
    val smsPermOk = rememberSmsGranted()
    val runtimeStatus by AssetsNotificationListenerService.runtimeStatusFlow.collectAsStateWithLifecycle()
    val notifListenerOk = listenerStatus == ListenerStatus.OK
    val lifecycleOwner = LocalLifecycleOwner.current
    fun appNotificationPermissionGranted(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
    fun batteryExemptionGranted(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
    var notifPermOk by remember { mutableStateOf(appNotificationPermissionGranted()) }
    var batteryOk by remember { mutableStateOf(batteryExemptionGranted()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifPermOk = appNotificationPermissionGranted()
                batteryOk = batteryExemptionGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 备份目录 SAF 选择（REQ 备份§2）：拿持久化授权，之后每日自动备份写所选目录
    val backupDirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && settingsWritable) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            repository.setBackupDirUri(uri)
            backupMsg = "备份目录已更新"
        }
    }

    val pipelineSeverity = settingsPipelineSeverity(
        listenerStatus = listenerStatus,
        runtimeStatus = runtimeStatus,
        notificationPermissionGranted = notifPermOk,
        smsFallbackGranted = smsPermOk,
        batteryExemptionGranted = batteryOk
    )
    val pipelineSummary = when {
        pipelineSeverity == SettingsPipelineSeverity.ERROR -> "入库链路异常"
        runtimeStatus == VaultRuntimeStatus.RECOVERING -> "恢复中"
        pipelineSeverity == SettingsPipelineSeverity.WARNING -> "核心监听正常"
        else -> "全部正常"
    }
    val latestIntakeText = if (privacyEnabled) {
        "最近入库 ${privacyFakeDateTime(900)}"
    } else if (lastReceivedAt <= 0L) {
        "等待第一笔账目"
    } else {
        "${if (runtimeStatus == VaultRuntimeStatus.RECOVERING) "补扫中 · " else ""}最近入库 ${formatTime(lastReceivedAt)}"
    }
    val currentMonth = java.time.YearMonth.now().toString()
    val currentBudgets = budgets.filter { it.month == currentMonth }
    val currentMonthBudgetCount = budgets.count { it.month == currentMonth }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(if (activeSection == null) 8.dp else 16.dp)
    ) {
        if (activeSection == null) {
            item {
                Text("设置", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
            }
            item {
                SettingsGroupCard(
                    title = SettingsSection.MONTHLY_PLAN.title,
                    summary = if (privacyEnabled) {
                        "预期收入 ${privacyFakeAmount(901)} · ${privacyFakeCount(902)} 项本月预算"
                    } else {
                        "预期收入 ${formatMoney(monthlyIncomeCents)} · $currentMonthBudgetCount 项本月预算"
                    },
                    onClick = { activeSection = SettingsSection.MONTHLY_PLAN }
                )
            }
            item {
                SettingsGroupCard(
                    title = SettingsSection.AUTOMATIC_INTAKE.title,
                    summary = "$pipelineSummary · $latestIntakeText",
                    onClick = { activeSection = SettingsSection.AUTOMATIC_INTAKE }
                )
            }
            item {
                SettingsGroupCard(
                    title = SettingsSection.DATA_PRIVACY.title,
                    summary = "加密备份、恢复、CSV 导出与隐私说明",
                    onClick = { activeSection = SettingsSection.DATA_PRIVACY }
                )
            }
            item {
                GuardianModeCard()
            }
        } else {
            item {
                SettingsSectionHeader(activeSection!!.title) { activeSection = null }
            }
        }

        // ── V5 现金流设置 ──
        if (activeSection == SettingsSection.MONTHLY_PLAN) { item {
            var incomeInput by remember(monthlyIncomeCents) {
                mutableStateOf(if (monthlyIncomeCents > 0) "%.2f".format(monthlyIncomeCents / 100.0) else "")
            }
            val budgetSum = currentBudgets.sumOf { it.monthlyLimitCents }
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
                    isAmount = true,
                    readOnly = !settingsWritable
                )
                Spacer(Modifier.height(12.dp))
                // 自由开销额度（REQ 统计§12 / 设置§5：最常调整的规划参数之一）
                var freeInput by remember(freeSpendingCents) {
                    mutableStateOf(if (freeSpendingCents > 0) "%.2f".format(freeSpendingCents / 100.0) else "")
                }
                FormField(
                    value = freeInput,
                    onValueChange = { freeInput = it.filter { c -> c.isDigit() || c == '.' } },
                    label = "自由开销额度（每月）",
                    isAmount = true,
                    readOnly = !settingsWritable
                )
                Spacer(Modifier.height(12.dp))
                Text("必要生活（自动 = 分项预算之和）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                if (currentBudgets.isEmpty()) {
                    Text(
                        "还没设分项预算。去下面「月度预算」把吃饭/交通/住房等每项填上，这里自动合计。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        if (privacyEnabled) "已汇总 ${privacyFakeCount(903)} 项分项预算" else "已汇总 ${currentBudgets.size} 项分项预算",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("必要生活合计", fontWeight = FontWeight.Bold)
                    Text(
                        if (privacyEnabled) privacyFakeAmount(904) else formatMoney(budgetSum),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val incomeCents = runCatching {
                            java.math.BigDecimal(incomeInput.ifBlank { "0" }).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                        }.getOrNull()
                        val freeCents = runCatching {
                            java.math.BigDecimal(freeInput.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                        }.getOrNull()
                        if (incomeCents != null && incomeCents >= 0L) onSetMonthlyIncome(incomeCents)
                        if (freeCents != null && freeCents > 0L) onSetFreeSpending(freeCents)
                    },
                    enabled = settingsWritable,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存月度规划") }
            }
        } }

        // 自动记账是设置页第二优先级：用户先看到金库是否可靠，再处理低频配置。
        if (activeSection == SettingsSection.AUTOMATIC_INTAKE) { item {
            val summaryColor = when (pipelineSeverity) {
                SettingsPipelineSeverity.ERROR -> MaterialTheme.colorScheme.error
                SettingsPipelineSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                SettingsPipelineSeverity.OK -> MaterialTheme.colorScheme.primary
            }
            GlassCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("金库状态", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(latestIntakeText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(pipelineSummary, color = summaryColor, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("核心入库", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    SettingsCapabilityRow(
                        label = "通知监听",
                        hint = "读取已授权来源的银行与支付通知",
                        status = when (listenerStatus) {
                            ListenerStatus.OK -> "监听中"
                            ListenerStatus.DISCONNECTED -> "已授权，连接中断"
                            ListenerStatus.DISABLED -> "未开启"
                        },
                        statusColor = if (notifListenerOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        actionLabel = when (listenerStatus) {
                            ListenerStatus.OK -> "查看指引"
                            ListenerStatus.DISABLED -> "去开启"
                            ListenerStatus.DISCONNECTED -> "立即恢复"
                        },
                        actionEnabled = settingsWritable,
                        onAction = {
                            when (listenerStatus) {
                                ListenerStatus.DISABLED -> openListenerSettings(context)
                                ListenerStatus.DISCONNECTED -> AssetsNotificationListenerService.recoverNow(context)
                                ListenerStatus.OK -> guideDialog = "通知监听" to
                                    "系统设置搜索“通知使用权”，确认资产大王已允许。若显示已授权但长期收不到，请回到这里使用“立即恢复”，再发送一条真实支付通知验证。"
                            }
                        }
                    )
                    if (runtimeStatus == VaultRuntimeStatus.ERROR) {
                        SettingsCapabilityRow(
                            label = "最近一次入库",
                            hint = "补收或证据处理没有完整结束",
                            status = "处理失败",
                            statusColor = MaterialTheme.colorScheme.error,
                            actionLabel = "立即恢复",
                            actionEnabled = settingsWritable,
                            onAction = { AssetsNotificationListenerService.recoverNow(context) }
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("漏收与后台保障", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    SettingsCapabilityRow(
                        label = "短信补扫",
                        hint = "监听中断后从银行短信补回遗漏",
                        status = if (smsPermOk) "已开启" else "未开启",
                        statusColor = if (smsPermOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        actionLabel = if (smsPermOk) "查看指引" else "去开启",
                        actionEnabled = settingsWritable,
                        onAction = {
                            if (smsPermOk) {
                                guideDialog = "短信补扫" to
                                    "资产大王需要“接收短信”和“访问短信/彩信”两项权限。短信补扫用于监听中断后的漏单恢复；只解析下方白名单短号。"
                            } else {
                                smsPermLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
                            }
                        }
                    )
                    SettingsCapabilityRow(
                        label = "通知提醒",
                        hint = "只影响待确认提醒，不影响核心入库",
                        status = if (notifPermOk) "已开启" else "未开启",
                        statusColor = if (notifPermOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        actionLabel = "去设置",
                        actionEnabled = settingsWritable,
                        onAction = {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }
                    )
                    SettingsCapabilityRow(
                        label = "Android 电池优化",
                        hint = "已豁免 = Android 待机省电不再限制；厂商后台策略仍需单独确认",
                        status = if (batteryOk) "已豁免" else "待设置",
                        statusColor = if (batteryOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        actionLabel = if (batteryOk) "查看说明" else "去设置",
                        actionEnabled = settingsWritable,
                        onAction = {
                            if (batteryOk) {
                                guideDialog = "Android 电池优化" to
                                    "“已豁免”只代表 Android 的 Doze/待机省电不再按普通应用延迟资产大王；vivo 后台耗电、自启动和关联启动仍是独立设置。"
                            } else {
                                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:${context.packageName}")))
                            }
                        }
                    )
                    SettingsCapabilityRow(
                        label = "vivo 后台耗电",
                        hint = "设置 > 电池 > 后台耗电管理 > 允许后台耗电",
                        status = "需在系统中确认",
                        statusColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        actionLabel = "设置指南",
                        actionEnabled = settingsWritable,
                        onAction = {
                            try { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { }
                            Toast.makeText(context, "请搜索「后台耗电管理」，为资产大王选择「允许后台耗电」", Toast.LENGTH_LONG).show()
                        }
                    )
                    SettingsCapabilityRow(
                        label = "设备自启动",
                        hint = "允许系统事件在进程退出后重新拉起应用",
                        status = "需在系统中确认",
                        statusColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        actionLabel = "设置指南",
                        actionEnabled = settingsWritable,
                        onAction = {
                            try { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { }
                            Toast.makeText(context, "请搜索「自启动」，允许资产大王自启动", Toast.LENGTH_LONG).show()
                        }
                    )
                    SettingsCapabilityRow(
                        label = "设备关联启动",
                        hint = "允许系统组件和关联事件拉起监听；App 无法读取厂商开关状态",
                        status = "需在系统中确认",
                        statusColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        actionLabel = "查看指引",
                        actionEnabled = settingsWritable,
                        onAction = {
                            guideDialog = "设备关联启动" to
                                "系统设置搜索“自启动”，进入资产大王后同时允许“自启动”和“关联启动”。它只增加外部事件拉起机会，最终仍以真实通知入库验证为准。"
                        }
                    )
                    SettingsCapabilityRow(
                        label = "vivo 财务信息保护",
                        hint = "可能拦截第三方读取银行金融短信",
                        status = "需在系统中确认",
                        statusColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        actionLabel = "查看指引",
                        actionEnabled = settingsWritable,
                        onAction = {
                            guideDialog = "财务信息保护" to
                                "系统设置搜索“财务信息保护”。若真实银行短信始终无法补扫，可在理解隐私风险后仅为排障临时关闭并复测；验证码安全保护无需关闭。"
                        }
                    )
                }
            }
        } }

        // ── Budgets ──
        if (activeSection == SettingsSection.MONTHLY_PLAN) { item {
            GlassCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("月度预算", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { showBudgetSheet = true }, enabled = settingsWritable) { Text("＋ 新增") }
                }
                if (currentBudgets.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("暂无预算设置", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    currentBudgets.forEachIndexed { index, b ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (privacyEnabled) {
                                        privacyObfuscatedText(
                                            if (b.category == "ALL") "总预算" else
                                                runCatching { TransactionCategory.valueOf(b.category) }.getOrNull()?.let { categoryLabel(it) } ?: b.category,
                                            910 + index
                                        )
                                    } else if (b.category == "ALL") "总预算" else {
                                        runCatching { TransactionCategory.valueOf(b.category) }.getOrNull()?.let { categoryLabel(it) } ?: b.category
                                    },
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    if (privacyEnabled) {
                                        "限额 ${privacyFakeAmount(940 + index)}"
                                    } else {
                                        "限额 ${formatMoney(b.monthlyLimitCents)}"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { editingBudget = b; showBudgetSheet = true }, enabled = settingsWritable) { Text("编辑") }
                                OutlinedButton(onClick = { onDeleteBudget(b.id) }, enabled = settingsWritable) { Text("删除") }
                            }
                        }
                    }
                }
            }
        } }

        // ── 通知来源白名单：只有打开的 app 才会被读取入库 ──
        if (activeSection == SettingsSection.AUTOMATIC_INTAKE) { item {
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
                                enabled = settingsWritable,
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
        } }

        // ── 短信发送方白名单：实时接收与历史补扫共用 ──
        if (activeSection == SettingsSection.AUTOMATIC_INTAKE) { item {
            GlassCard {
                Text("短信发送方白名单", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "只解析这些短号发来的短信；陌生发送方不会进入实时接收或历史补扫。支持逗号或换行分隔，每项 3–20 位数字。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = smsSenderInput,
                    onValueChange = {
                        smsSenderInput = it
                        smsWhitelistMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("短信短号") },
                    placeholder = { Text("95555\n95533") },
                    minLines = 3,
                    maxLines = 6,
                    readOnly = !settingsWritable
                )
                smsWhitelistMessage?.let { message ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (smsWhitelistMessageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val defaults = SmsSenderWhitelist.defaults
                        onSetSmsSenderWhitelist(defaults)
                        smsSenderInput = defaults.joinToString("\n")
                        smsWhitelistMessageIsError = false
                        smsWhitelistMessage = "已恢复默认（${defaults.size} 个发送方）"
                    }, enabled = settingsWritable) { Text("恢复默认") }
                    Button(onClick = {
                        val senders = parseSmsSenderWhitelistInput(smsSenderInput)
                        if (senders == null) {
                            smsWhitelistMessageIsError = true
                            smsWhitelistMessage = "请输入至少一个 3–20 位数字短号，使用逗号或换行分隔"
                        } else {
                            onSetSmsSenderWhitelist(senders)
                            smsWhitelistMessageIsError = false
                            smsWhitelistMessage = "已保存 ${senders.size} 个发送方"
                        }
                    }, enabled = settingsWritable) { Text("保存") }
                }
            }
        } }

        // ── 数据管理与安全（备份、恢复、CSV 合并展示，避免两个重复模块）──
        if (activeSection == SettingsSection.DATA_PRIVACY) { item {
            GlassCard {
                Column(Modifier.fillMaxWidth()) {
                    Text("数据管理与安全", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "6 位备份密码主要防止文件被随手打开，强度低于长密码；密码遗忘后备份无法恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    var pinInput by remember { mutableStateOf(repository.backupPin()) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FormField(
                            value = pinInput,
                            onValueChange = { pinInput = it.filter { c -> c.isDigit() }.take(6) },
                            label = "6 位备份密码",
                            modifier = Modifier.weight(1f),
                            readOnly = !settingsWritable
                        )
                        TextButton(onClick = {
                            if (pinInput.length == 6) {
                                repository.setBackupPin(pinInput)
                                backupMsg = "密码已保存"
                            } else backupMsg = "密码必须是 6 位数字"
                        }, enabled = settingsWritable) { Text("保存") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                backupMsg = if (repository.backupNow(manual = true)) "备份完成" else "请先设置 6 位备份密码"
                            }
                        }, enabled = settingsWritable) { Text("立即备份") }
                        OutlinedButton(onClick = { backupLauncher.launch(arrayOf("*/*")) }, enabled = settingsWritable) { Text("恢复备份") }
                    }
                    Spacer(Modifier.height(8.dp))
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
                        enabled = settingsWritable,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("导出流水 CSV") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showTransactionTrash = true },
                        enabled = settingsWritable,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val trashCount = deletedTransactions.size + deletedTransfers.size
                        Text(if (trashCount == 0) "流水垃圾箱（空）" else "流水垃圾箱（${trashCount}）")
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "备份目录：${
                                repository.backupDirUri()?.let { u ->
                                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, u)?.name
                                } ?: "应用私有目录"
                            }",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { backupDirLauncher.launch(null) }, enabled = settingsWritable) { Text("选择目录") }
                    }
                    if (backupMsg.isNotEmpty()) {
                        Text(backupMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        } }

        // ── Reconciliation ──
        if (activeSection == SettingsSection.AUTOMATIC_INTAKE) { item {
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
                        enabled = settingsWritable,
                        label = { days -> when (days) { 3 -> "3天"; 7 -> "每周"; 14 -> "两周"; 30 -> "每月"; else -> "${days}天" } },
                        id = { days -> days.toString() }
                    )
                }
            }
        } }

        if (activeSection == SettingsSection.DATA_PRIVACY) { item {
            GlassCard {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "资产大王 v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 检查更新（REQ 设置§13）：API 失败时回退公开清单。
                        TextButton(
                            enabled = settingsWritable && !checkingUpdate && downloadingRelease == null,
                            onClick = {
                                checkingUpdate = true
                                updateMsg = ""
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
                                    checkingUpdate = false
                                    when (result) {
                                        is UpdateChecker.FetchResult.Failure -> updateMsg = result.message
                                        is UpdateChecker.FetchResult.Success -> {
                                            if (UpdateChecker.isNewer(result.release.tag, BuildConfig.VERSION_NAME)) {
                                                latestRelease = result.release
                                            } else {
                                                updateMsg = if (result.source == UpdateChecker.Source.PUBLIC_MANIFEST) {
                                                    "已是最新版本（经备用地址确认）"
                                                } else {
                                                    "已是最新版本"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        ) { Text(if (checkingUpdate) "检查中…" else "检查更新") }
                    }
                    if (updateMsg.isNotEmpty()) {
                        Text(updateMsg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    downloadedUpdateApk?.let { apk ->
                        TextButton(
                            enabled = settingsWritable && downloadingRelease == null,
                            onClick = {
                                if (UpdateInstaller.canRequestPackageInstalls(context)) {
                                    runCatching { UpdateInstaller.launchSystemInstaller(context, apk) }
                                        .onSuccess {
                                            downloadedUpdateApk = null
                                            updateMsg = "安装包已校验，已打开系统安装器"
                                        }
                                        .onFailure { updateMsg = it.message ?: "无法打开系统安装器" }
                                } else {
                                    installPermissionLauncher.launch(UpdateInstaller.unknownSourcesIntent(context))
                                }
                            }
                        ) { Text("安装已下载版本") }
                    }
                }
            }
        } }
    }

    // 新版本说明弹窗：用户确认后留在 App 内下载并校验 APK。
    latestRelease?.let { rel ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { latestRelease = null },
            title = { Text("发现新版本 ${rel.tag}", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(rel.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        rel.body.ifBlank { "（无版本说明）" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    latestRelease = null
                    downloadingRelease = rel
                    updateDownloadedBytes = 0L
                    updateTotalBytes = rel.apkSize
                    updateMsg = ""
                    scope.launch {
                        try {
                            val artifact = UpdateInstaller.download(context, rel) { downloaded, total ->
                                scope.launch {
                                    updateDownloadedBytes = downloaded
                                    updateTotalBytes = total
                                }
                            }
                            downloadingRelease = null
                            downloadedUpdateApk = artifact.file
                            updateMsg = "下载与校验完成"
                            if (UpdateInstaller.canRequestPackageInstalls(context)) {
                                UpdateInstaller.launchSystemInstaller(context, artifact.file)
                                downloadedUpdateApk = null
                                updateMsg = "安装包已校验，已打开系统安装器"
                            } else {
                                updateMsg = "安装包已校验，请允许资产大王安装应用"
                                installPermissionLauncher.launch(UpdateInstaller.unknownSourcesIntent(context))
                            }
                        } catch (error: Throwable) {
                            downloadingRelease = null
                            updateMsg = error.message ?: "下载失败，请重试"
                        }
                    }
                }) { Text("立即更新") }
            },
            dismissButton = { TextButton(onClick = { latestRelease = null }) { Text("取消") } }
        )
    }

    downloadingRelease?.let { rel ->
        val total = updateTotalBytes.coerceAtLeast(rel.apkSize)
        val progress = if (total > 0L) {
            (updateDownloadedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text("正在下载 ${rel.tag}", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${formatUpdateBytes(updateDownloadedBytes)} / ${formatUpdateBytes(total)}（${(progress * 100).toInt()}%）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("下载完成后会校验安装包，再打开系统安装器。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {}
        )
    }

    if (showBudgetSheet) {
        BudgetSheet(
            existing = editingBudget,
            categories = categories,
            onSave = { onSaveBudget(it); showBudgetSheet = false; editingBudget = null },
            onDismiss = { showBudgetSheet = false; editingBudget = null }
        )
    }

    guideDialog?.let { (title, body) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { guideDialog = null },
            title = { Text(title, fontWeight = FontWeight.Bold) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = { guideDialog = null }) { Text("知道了") }
            }
        )
    }

    if (showTransactionTrash) {
        TransactionTrashSheet(
            transactions = deletedTransactions,
            accounts = accounts,
            onRestore = onRestoreTransaction,
            onPermanentlyDelete = onPermanentlyDeleteTransaction,
            transfers = deletedTransfers,
            onRestoreTransfer = onRestoreTransfer,
            onPermanentlyDeleteTransfer = onPermanentlyDeleteTransfer,
            onDismiss = { showTransactionTrash = false }
        )
    }
}

@Composable
private fun BudgetSheet(
    existing: BudgetEntity?,
    categories: List<CategoryEntity>,
    onSave: (BudgetEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val expenseCategories = categories.filter { !it.isArchived && it.kind == "EXPENSE" }
    val primaryCategories = expenseCategories.filter { it.parentId == null }.sortedBy { it.sortOrder }
    val existingCategory = existing?.let { budget ->
        expenseCategories.firstOrNull { it.name == budget.category || it.id == budget.category }
    }
    val initialParentId = existingCategory?.parentId ?: existingCategory?.id ?: primaryCategories.firstOrNull()?.id
    var selectedParentId by remember(existing?.id, categories) { mutableStateOf(initialParentId) }
    val initialChildren = expenseCategories.filter { it.parentId == initialParentId }.sortedBy { it.sortOrder }
    var selectedCategoryId by remember(existing?.id, categories) {
        mutableStateOf(existingCategory?.id ?: initialChildren.firstOrNull()?.id ?: initialParentId)
    }
    var limit by remember {
        mutableStateOf(existing?.let { "%.2f".format(it.monthlyLimitCents / 100.0) } ?: "")
    }

    Sheet(title = if (existing != null) "编辑预算" else "新增预算", onDismiss = onDismiss) {
        Text("一级分类", fontWeight = FontWeight.Medium)
        primaryCategories.firstOrNull { it.id == selectedParentId }?.let { selectedParent ->
            ChipRow(
                items = primaryCategories,
                selected = selectedParent,
                onSelected = { parent ->
                    selectedParentId = parent.id
                    selectedCategoryId = expenseCategories
                        .filter { it.parentId == parent.id }
                        .sortedBy { it.sortOrder }
                        .firstOrNull()?.id ?: parent.id
                },
                label = { it.name },
                id = { it.id }
            )
        } ?: Text("暂无可用消费分类", color = MaterialTheme.colorScheme.error)

        val secondaryCategories = expenseCategories
            .filter { it.parentId == selectedParentId }
            .sortedBy { it.sortOrder }
        if (secondaryCategories.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("二级分类", fontWeight = FontWeight.Medium)
            secondaryCategories.firstOrNull { it.id == selectedCategoryId }?.let { selectedSecondary ->
                ChipRow(
                    items = secondaryCategories,
                    selected = selectedSecondary,
                    onSelected = { selectedCategoryId = it.id },
                    label = { it.name },
                    id = { it.id }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        FormField(
            value = limit,
            onValueChange = { limit = it.filter { c -> c.isDigit() || c == '.' } },
            label = "月度限额（元）"
        )

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val cents = runCatching {
                    java.math.BigDecimal(limit.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                }.getOrNull() ?: return@Button
                if (cents <= 0) return@Button
                val selectedCategory = expenseCategories.firstOrNull { it.id == selectedCategoryId }
                    ?: return@Button
                onSave(
                    BudgetEntity(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        category = selectedCategory.name,
                        monthlyLimitCents = cents,
                        month = java.time.YearMonth.now().toString()
                    )
                )
            },
            enabled = selectedCategoryId != null && limit.toDoubleOrNull()?.let { it > 0 } == true,
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存") }
    }
}
