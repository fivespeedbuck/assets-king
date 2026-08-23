package com.assetsking.app.ui.screen

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.assetsking.app.LedgerUiState
import com.assetsking.app.R
import com.assetsking.app.notification.AssetsNotificationListenerService
import com.assetsking.app.notification.VaultRuntimeStatus
import com.assetsking.app.ui.privacy.LocalPrivacyChaosFrame
import com.assetsking.app.ui.privacy.animatePrivacyValue
import com.assetsking.app.ui.privacy.privacyFakeAmount
import com.assetsking.app.ui.privacy.privacyFakeCount
import com.assetsking.app.ui.privacy.privacyFakeDateTime
import com.assetsking.app.ui.privacy.privacyObfuscatedText
import com.assetsking.database.AccountEntity
import com.assetsking.database.BudgetEntity
import com.assetsking.database.CategoryEntity
import com.assetsking.database.CreditCardInstallmentEntity
import com.assetsking.database.CreditCardInstallmentScheduleEntity
import com.assetsking.database.LedgerRepository
import com.assetsking.database.LoanPlanEntity
import com.assetsking.database.RecurringRuleEntity
import com.assetsking.model.AccountType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assetsking.ui.component.GlassCard
import com.assetsking.ui.format.BigMoney
import com.assetsking.ui.format.formatMoney
import com.assetsking.ui.format.formatTime
import com.assetsking.ui.privacy.LocalPrivacyEnabled
import com.assetsking.ui.theme.ExpenseRed
import com.assetsking.ui.theme.IncomeGreen
import com.assetsking.ui.theme.PendingOrange
import com.assetsking.ui.theme.PrivacyEmblemFog
import com.assetsking.ui.theme.PrivacyEmblemPurple
import com.assetsking.ui.theme.ReimbursementYellow
import com.assetsking.ui.theme.RecurringDebitOrange
import com.assetsking.ui.theme.RepaymentPurple
import com.assetsking.ui.theme.cashBalanceColor
import com.assetsking.usecase.cashFlowSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HomeGreen = IncomeGreen
private val HomeRed = ExpenseRed
private val HomeOrange = PendingOrange

/** 首页固定核心区 + 固定信息模块。 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HomeTab(
    padding: PaddingValues,
    state: LedgerUiState,
    listenerStatus: ListenerStatus,
    lastReceivedAt: Long,
    context: Context,
    budgets: List<BudgetEntity>,
    categories: List<CategoryEntity>,
    recurringRules: List<RecurringRuleEntity>,
    loanPlans: List<LoanPlanEntity>,
    cardInstallments: List<CreditCardInstallmentEntity>,
    cardInstallmentSchedules: List<CreditCardInstallmentScheduleEntity>,
    freeSpendingCents: Long,
    privacyEnabled: Boolean,
    onTogglePrivacy: () -> Unit,
    onShowPending: () -> Unit,
    onShowReconciliation: () -> Unit,
    onGotoStats: () -> Unit,
    onGotoLoans: () -> Unit,
    onGotoBills: () -> Unit,
    onGotoReimbursement: () -> Unit,
    onEditAccount: (AccountEntity?) -> Unit,
    onAddAccount: (AccountType) -> Unit
) {
    var showAssetAccounts by remember { mutableStateOf(false) }
    var showDebtAccounts by remember { mutableStateOf(false) }
    val privacyFrame = LocalPrivacyChaosFrame.current
    fun money(cents: Long, slot: Int) = if (privacyEnabled) {
        privacyFrame.fakeAmounts[Math.floorMod(slot, privacyFrame.fakeAmounts.size)]
    } else {
        formatMoney(cents)
    }

    // 本月收支（REQ 首页信息优先级§6/收入§2-3）：实际收入只算 INCOME；支出扣已关联退款与已报销
    val monthStart = YearMonth.now().atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val monthEnd = YearMonth.now().plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
    val monthTxs = state.transactions.filter { it.status == "CONFIRMED" && it.occurredAt in monthStart..monthEnd }
    val monthCashFlow = cashFlowSummary(
        transactions = monthTxs,
        transfers = state.transfers.filter { it.occurredAt in monthStart..monthEnd },
        accounts = state.accounts
    )
    val monthIncome = monthCashFlow.incomeCents
    val monthExpense = monthCashFlow.expenseCents
    val monthBalance = monthCashFlow.balanceCents
    val totalDueItems = monthRepaymentItems(
        plans = loanPlans,
        accounts = state.accounts,
        cardInstallments = cardInstallments,
        cardSchedules = cardInstallmentSchedules,
        cardRemainingDueByCard = state.v5?.cardRemainingDueByCard.orEmpty(),
        transactions = state.transactions,
        transfers = state.transfers
    )
    val outstandingDueItems = totalDueItems.filterNot { it.paid }
    val paidDueItems = totalDueItems.filter { it.paid }
    val repaymentPages = homeRepaymentPages(
        totalDueCount = outstandingDueItems.size,
        totalDueCents = outstandingDueItems.sumOf { it.amount },
        paidCount = paidDueItems.size,
        paidCents = paidDueItems.sumOf { it.amount },
        totalDebtCents = state.v5?.totalDebtCents ?: 0L
    )

    // 近期提醒与本月卡片共用同一聚合器，只改变截止窗口。
    val today = java.time.LocalDate.now()
    val dueSoon = monthRepaymentItems(
        plans = loanPlans,
        accounts = state.accounts,
        cardInstallments = cardInstallments,
        cardSchedules = cardInstallmentSchedules,
        cardRemainingDueByCard = state.v5?.cardRemainingDueByCard.orEmpty(),
        transactions = state.transactions,
        transfers = state.transfers,
        today = today,
        outstandingThrough = today.plusDays(2)
    ).filterNot { it.paid }
    val dueTotal = dueSoon.sumOf { it.amount }
    val dueEarliest = dueSoon.minOfOrNull { it.dueDay }
    val anyOverdue = dueSoon.any { it.dueDay < today.toEpochDay() }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── 第一层：财务总览卡（REQ 首页UI§1-2/§17-18）──
        item(span = { GridItemSpan(maxLineSpan) }) {
            GlassCard(contentPadding = Modifier) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "资产概览",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = onTogglePrivacy,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_privacy_emblem_fog),
                                    contentDescription = if (privacyEnabled) "退出隐私模式" else "进入隐私模式",
                                    tint = if (privacyEnabled) PrivacyEmblemFog else PrivacyEmblemPurple,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HomeHeroMetric(
                            label = "总资产",
                            cents = state.v5?.availableCashCents ?: 0L,
                            hidden = privacyEnabled,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            onClick = { showAssetAccounts = true }
                        )
                        VerticalDivider(Modifier.height(66.dp))
                        HomeHeroMetric(
                            label = "总欠款",
                            cents = state.v5?.totalDebtCents ?: 0L,
                            hidden = privacyEnabled,
                            color = HomeRed,
                            modifier = Modifier.weight(1f),
                            onClick = { showDebtAccounts = true }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    // 本月现金流固定 2×2：收入/支出；待还（还清后为已还）/结余。
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HomeMetric("收入", money(monthIncome, 0), HomeGreen, Modifier.weight(1f), onGotoStats)
                            VerticalDivider(Modifier.height(30.dp))
                            HomeMetric("支出", money(monthExpense, 1), HomeRed, Modifier.weight(1f), onGotoStats)
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HomeRepaymentPager(
                                pages = repaymentPages,
                                money = ::money,
                                privacyEnabled = privacyEnabled,
                                onClick = onGotoLoans,
                                modifier = Modifier.weight(1f)
                            )
                            VerticalDivider(Modifier.height(30.dp))
                            HomeMetric(
                                "结余",
                                money(monthBalance, 4),
                                cashBalanceColor(monthBalance),
                                Modifier.weight(1f),
                                onGotoStats
                            )
                        }
                    }
                    // 最近还款提醒（REQ 首页UI§5-7）：到期前 3 天窗口或逾期
                    if (dueSoon.isNotEmpty() || privacyEnabled) {
                        Row(
                            Modifier.fillMaxWidth().clickable { onGotoLoans() }.background(
                                if (anyOverdue) HomeRed.copy(alpha = 0.12f) else HomeOrange.copy(alpha = 0.16f),
                                RoundedCornerShape(8.dp)
                            ).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (privacyEnabled) "即将还款" else if (anyOverdue) "已逾期" else "即将还款",
                                fontWeight = FontWeight.Bold,
                                color = if (anyOverdue) HomeRed else HomeOrange
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                if (privacyEnabled) {
                                    "${privacyFakeCount(12)} 笔 合计 ${money(dueTotal, 5)} · 最近 ${privacyFakeDateTime(13).substringBefore(' ')}"
                                } else {
                                    "${dueSoon.size} 笔 合计 ${money(dueTotal, 5)}" + (dueEarliest?.let { " · 最近 ${DateTimeFormatter.ofPattern("M月d日", Locale.CHINA).format(java.time.LocalDate.ofEpochDay(it))}" } ?: "")
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // ── 第二层：金库状态卡（REQ 首页UI§3/§19-22）──
        item(span = { GridItemSpan(maxLineSpan) }) {
            val pendingNetCents = state.pendingItems.sumOf { pending ->
                val amount = pending.parsed.amountCents ?: 0L
                when (pending.parsed.isExpense) { true -> -amount; false -> amount; null -> 0L }
            }
            VaultStatusCard(
                listenerStatus = listenerStatus,
                lastReceivedAt = lastReceivedAt,
                pendingCount = state.pendingItems.size + state.unprocessedNotifications,
                unprocessedCount = state.unprocessedNotifications,
                pendingNetCents = pendingNetCents,
                needsReconciliationCount = state.accounts.filter {
                    it.balanceStatus == "DISCREPANCY" || System.currentTimeMillis() - (it.lastCheckedAt ?: 0L) > 7 * 24 * 60 * 60 * 1000L
                }.size,
                onShowPending = onShowPending,
                onShowReconciliation = onShowReconciliation,
                context = context
            )
        }

        // ── 固定首页信息区：待报销/本月待扣/预算/分账户 ──
        LedgerRepository.defaultModuleOrder.forEachIndexed { index, module ->
            val compactKeys = setOf("reimbursement", "recurring")
            val hasAdjacentCompactPartner = module in compactKeys && (
                LedgerRepository.defaultModuleOrder.getOrNull(index - 1) in compactKeys ||
                    LedgerRepository.defaultModuleOrder.getOrNull(index + 1) in compactKeys
            )
            val moduleSpan = if (hasAdjacentCompactPartner) 1 else 2
            item(key = "module-$module", span = { GridItemSpan(moduleSpan) }) {
                HomeModuleCard(
                    modifier = Modifier.fillMaxWidth().then(if (moduleSpan == 1) Modifier.height(124.dp) else Modifier),
                    key = module,
                    state = state,
                    budgets = budgets,
                    categories = categories,
                    recurringRules = recurringRules,
                    freeSpendingCents = freeSpendingCents,
                    money = ::money,
                    onGotoStats = onGotoStats,
                    onGotoBills = onGotoBills,
                    onGotoReimbursement = onGotoReimbursement,
                    onGotoAccounts = { showAssetAccounts = true }
                )
            }
        }
    }
    if (showAssetAccounts) {
        AccountListDialog(
            title = "资产账户",
            accounts = state.accounts.filter { it.type == AccountType.ASSET.name && !it.archived },
            onEdit = { showAssetAccounts = false; onEditAccount(it) },
            onAdd = { showAssetAccounts = false; onAddAccount(AccountType.ASSET) },
            onDismiss = { showAssetAccounts = false }
        )
    }
    if (showDebtAccounts) {
        AccountListDialog(
            title = "欠款账户",
            accounts = visibleDebtAccounts(state.accounts),
            onEdit = { showDebtAccounts = false; onEditAccount(it) },
            onAdd = { showDebtAccounts = false; onAddAccount(AccountType.CREDIT) },
            onDismiss = { showDebtAccounts = false }
        )
    }
}

/** 4 个固定模块（待报销/待扣/预算/分账户），全部可下钻。 */
@Composable
private fun HomeModuleCard(
    modifier: Modifier = Modifier,
    key: String,
    state: LedgerUiState,
    budgets: List<BudgetEntity>,
    categories: List<CategoryEntity>,
    recurringRules: List<RecurringRuleEntity>,
    freeSpendingCents: Long,
    money: (Long, Int) -> String,
    onGotoStats: () -> Unit,
    onGotoBills: () -> Unit,
    onGotoReimbursement: () -> Unit,
    onGotoAccounts: () -> Unit
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    val privacyFrame = LocalPrivacyChaosFrame.current
    val monthStart = YearMonth.now().atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val monthEnd = YearMonth.now().plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
    val monthTxs = state.transactions.filter { it.occurredAt in monthStart..monthEnd }
    val compact = key == "reimbursement" || key == "recurring"
    GlassCard(
        modifier.clickable {
            when (key) {
                "recurring" -> onGotoBills()
                "reimbursement" -> onGotoReimbursement()
                "accounts" -> onGotoAccounts()
                else -> onGotoStats()
            }
        },
        contentPadding = Modifier
    ) {
        Column(
            modifier = Modifier
                .then(if (compact) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .padding(14.dp),
            verticalArrangement = if (compact) Arrangement.SpaceBetween else Arrangement.Top
        ) {
            when (key) {
                "budget" -> {
                    val budgetSum = necessaryBudgetCents(budgets, categories, YearMonth.now().toString())
                    val monthSpending = monthSpendingBreakdown(state.transactions, categories, monthStart, monthEnd)
                    val necessaryProgress = animatePrivacyValue(
                        if (privacyEnabled) privacyFrame.progressFractions[1]
                        else if (budgetSum > 0L) (monthSpending.necessaryCents.toFloat() / budgetSum).coerceIn(0f, 1f) else 0f,
                        "privacy-home-necessary"
                    )
                    val optionalProgress = animatePrivacyValue(
                        if (privacyEnabled) privacyFrame.progressFractions[2]
                        else if (freeSpendingCents > 0L) (monthSpending.optionalCents.toFloat() / freeSpendingCents).coerceIn(0f, 1f) else 0f,
                        "privacy-home-optional"
                    )
                    HomeModuleHeader("本月预算")
                    Spacer(Modifier.height(8.dp))
                    Text("必要消费", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (privacyEnabled) "${privacyFakeAmount(1)} / ${privacyFakeAmount(2)}" else "${money(monthSpending.necessaryCents, 1)} / ${money(budgetSum, 2)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { necessaryProgress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.height(5.dp))
                    Text("自由开销（非必要）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (privacyEnabled) "${privacyFakeAmount(3)} / ${privacyFakeAmount(4)}" else "${money(monthSpending.optionalCents, 3)} / ${money(freeSpendingCents, 4)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { optionalProgress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                        color = HomeOrange,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                "reimbursement" -> {
                    val pending = outstandingReimbursements(state.transactions)
                    val reimbursed = monthTxs.filter { it.type == "REIMBURSEMENT" }.sumOf { it.amountCents }
                    val pendingCents = pending.sumOf(::reimbursementRemainingCents)
                    HomeModuleHeader("待报销")
                    Text(
                        money(pendingCents, 6),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = ReimbursementYellow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (privacyEnabled) "${privacyFakeCount(21)}笔 · 已报 ${money(reimbursed, 7)}"
                        else "${pending.size}笔 · 已报 ${money(reimbursed, 7)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                "recurring" -> {
                    val debitSummary = recurringDebitMonthSummary(recurringRules, monthTxs, monthStart, monthEnd)
                    HomeModuleHeader("本月待扣")
                    Text(
                        money(debitSummary.pendingCents, 8),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = RecurringDebitOrange,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        when {
                            privacyEnabled -> "已扣 ${money(debitSummary.deductedCents, 9)} · ${privacyFakeCount(22)} 笔"
                            debitSummary.deductedCents > 0L -> "已扣 ${money(debitSummary.deductedCents, 9)}"
                            debitSummary.pendingRules.isNotEmpty() -> "${debitSummary.pendingRules.size} 笔待扣"
                            else -> "本月暂无扣款"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                "accounts" -> {
                    val assetAccounts = state.accounts.filter { it.type == AccountType.ASSET.name && !it.archived }
                    HomeModuleHeader("分账户余额")
                    Spacer(Modifier.height(8.dp))
                    assetAccounts.take(3).forEachIndexed { index, account ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (privacyEnabled) privacyObfuscatedText(account.name, 100 + index) else account.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                money(account.balanceCents, 10 + index),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = cashBalanceColor(account.balanceCents),
                                maxLines = 1
                            )
                        }
                        if (index < minOf(assetAccounts.size, 3) - 1) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                        }
                    }
                    if (assetAccounts.size > 3) {
                        Text(
                            if (privacyEnabled) "另有 ${privacyFakeCount(24)} 个账户" else "另有 ${assetAccounts.size - 3} 个账户",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                    }
                    if (assetAccounts.isEmpty()) {
                        Text("暂无账户", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeRepaymentPager(
    pages: List<HomeRepaymentPage>,
    money: (Long, Int) -> String,
    privacyEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()
    val carouselAlpha = remember { Animatable(1f) }
    // 手势使用 Pager 原生横滑；自动轮播只做淡出→完整换页→淡入，彻底消除动画中断后的半页状态。
    LaunchedEffect(isDragged, pages.size) {
        try {
            while (pages.size > 1 && !isDragged) {
                delay(4_500L)
                if (!pagerState.isScrollInProgress) {
                    carouselAlpha.animateTo(0f, tween(durationMillis = 220))
                    pagerState.scrollToPage(nextHomeRepaymentPage(pagerState.settledPage, pages.size))
                    carouselAlpha.animateTo(1f, tween(durationMillis = 320))
                }
            }
        } finally {
            // 用户在淡出阶段开始拖动时，协程会被取消；无论在哪一帧中断都立即恢复完整可见。
            withContext(NonCancellable) { carouselAlpha.snapTo(1f) }
        }
    }
    Row(
        modifier.graphicsLayer { alpha = carouselAlpha.value },
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { pageIndex ->
            val page = pages[pageIndex]
            HomeMetric(
                label = if (privacyEnabled) "待还 ${privacyFakeCount(30 + pageIndex)} 笔" else page.label,
                value = money(page.amountCents, 30 + pageIndex),
                color = RepaymentPurple,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val offset = kotlin.math.abs(
                            (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction
                        )
                        alpha = (1f - offset).coerceIn(0.20f, 1f)
                    },
                onClick = onClick
            )
        }
    }
}

internal fun nextHomeRepaymentPage(currentPage: Int, pageCount: Int): Int =
    if (pageCount <= 1) 0 else (currentPage + 1).mod(pageCount)

@Composable
private fun HomeModuleHeader(title: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun AccountListDialog(
    title: String,
    accounts: List<AccountEntity>,
    onEdit: (AccountEntity) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (accounts.isEmpty()) {
                    Text(
                        "暂无账户",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                accounts.forEachIndexed { index, a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onEdit(a) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (privacyEnabled) privacyObfuscatedText(a.name, 200 + index) else a.name,
                            Modifier.weight(1f)
                        )
                        Text(
                            if (privacyEnabled) privacyFakeAmount(200 + index) else formatMoney(a.balanceCents),
                            fontWeight = FontWeight.Medium
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (privacyEnabled) "查看并编辑账户" else "查看并编辑${a.name}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("新建账户")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 首页总览的等宽指标；金额严格单行，避免窄屏把小数挤成竖排。 */
@Composable
private fun HomeMetric(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val interactiveModifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    Column(interactiveModifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }
}

/** 首页最重要的两项金额等宽同级展示，避免金额被挤到卡片角落。 */
@Composable
private fun HomeHeroMetric(
    label: String,
    cents: Long,
    hidden: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        if (hidden) {
            Text(
                privacyFakeAmount(label.hashCode()),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center
            )
        } else {
            BigMoney(
                cents = cents,
                color = color,
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }
}

/** 金库状态卡（REQ 首页UI §19-22）：状态与监听详情入口。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun VaultStatusCard(
    listenerStatus: ListenerStatus,
    lastReceivedAt: Long,
    pendingCount: Int,
    unprocessedCount: Int,
    pendingNetCents: Long,
    needsReconciliationCount: Int,
    onShowPending: () -> Unit,
    onShowReconciliation: () -> Unit,
    context: Context
) {
    val privacyEnabled = LocalPrivacyEnabled.current
    val runtimeStatus by AssetsNotificationListenerService.runtimeStatusFlow.collectAsStateWithLifecycle()
    val smsGranted = rememberSmsGranted()
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    // 金库详情弹窗（审核 J-1 修复：入库状态点击区原为空实现，REQ 首页UI§19 要求可进入金库详情）
    var showDetail by remember { mutableStateOf(false) }

    val presentation = homeVaultPresentation(listenerStatus, runtimeStatus, smsGranted)
    val statusColor = when (presentation.severity) {
        HomeVaultSeverity.ERROR -> HomeRed
        HomeVaultSeverity.WARNING, HomeVaultSeverity.RECOVERING -> HomeOrange
        HomeVaultSeverity.NORMAL -> MaterialTheme.colorScheme.primary
    }
    val cardTint = when (presentation.severity) {
        HomeVaultSeverity.ERROR -> HomeRed.copy(alpha = 0.07f)
        HomeVaultSeverity.WARNING, HomeVaultSeverity.RECOVERING -> HomeOrange.copy(alpha = 0.07f)
        HomeVaultSeverity.NORMAL -> MaterialTheme.colorScheme.surfaceContainer
    }
    val recentValue = when {
        privacyEnabled -> privacyFakeDateTime(300)
        lastReceivedAt <= 0L -> "等待第一笔账目"
        runtimeStatus == VaultRuntimeStatus.RECOVERING -> "补收中 · ${formatTime(lastReceivedAt)}"
        else -> formatTime(lastReceivedAt)
    }
    GlassCard(contentPadding = Modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (privacyEnabled) Color.Transparent else cardTint)
                .padding(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.AccountBalance,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column(
                    Modifier.weight(1f).clickable { showDetail = true },
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        if (privacyEnabled && presentation.title == "金库正常") "金库异常" else presentation.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (presentation.severity == HomeVaultSeverity.WARNING) MaterialTheme.colorScheme.onSurface else statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (presentation.badge.isNotBlank()) {
                    Text(
                        presentation.badge,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier
                            .background(statusColor.copy(alpha = 0.13f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
            presentation.gapHint?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                VaultMetric(
                    label = "入库状态",
                    value = "最近入库",
                    detail = recentValue,
                    icon = Icons.Filled.AccountBalance,
                    color = statusColor,
                    modifier = Modifier.weight(1f),
                    onClick = { showDetail = true }
                )
                VerticalDivider(Modifier.height(86.dp), color = MaterialTheme.colorScheme.outlineVariant)
                VaultMetric(
                    label = "待确认",
                    value = if (privacyEnabled) "${privacyFakeCount(301)} 笔" else "$pendingCount 笔",
                    detail = if (privacyEnabled) {
                        "${privacyFakeCount(302)} 条 · ${privacyFakeAmount(303)}"
                    } else if (unprocessedCount > 0) {
                        "$unprocessedCount 条待恢复"
                    } else {
                        "${if (pendingNetCents > 0L) "+" else ""}${formatMoney(pendingNetCents)}"
                    },
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    color = if (privacyEnabled) MaterialTheme.colorScheme.primary else if (pendingCount > 0) HomeOrange else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = onShowPending
                )
                VerticalDivider(Modifier.height(86.dp), color = MaterialTheme.colorScheme.outlineVariant)
                VaultMetric(
                    label = "需核对",
                    value = if (privacyEnabled) "${privacyFakeCount(304)} 项" else "$needsReconciliationCount 项",
                    detail = if (privacyEnabled) privacyObfuscatedText("状态变动", 305) else if (needsReconciliationCount > 0) "查看账户" else "无需处理",
                    icon = Icons.AutoMirrored.Filled.FactCheck,
                    color = if (privacyEnabled) MaterialTheme.colorScheme.primary else if (needsReconciliationCount > 0) HomeOrange else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = onShowReconciliation
                )
            }
            if (listenerStatus != ListenerStatus.OK || runtimeStatus == VaultRuntimeStatus.ERROR || !smsGranted) {
                TextButton(onClick = {
                    when {
                        listenerStatus == ListenerStatus.DISABLED -> openListenerSettings(context)
                        listenerStatus == ListenerStatus.DISCONNECTED || runtimeStatus == VaultRuntimeStatus.ERROR ->
                            AssetsNotificationListenerService.recoverNow(context)
                        !smsGranted -> smsPermissionLauncher.launch(
                            arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
                        )
                    }
                }) {
                    Text(
                        when {
                            listenerStatus == ListenerStatus.DISABLED -> "去开启入库"
                            listenerStatus == ListenerStatus.DISCONNECTED || runtimeStatus == VaultRuntimeStatus.ERROR -> "立即恢复"
                            else -> "开启短信补收"
                        }
                    )
                }
            }
        }
    }

    // 金库详情弹窗（REQ 首页UI§19「入库状态进入金库详情」）
    if (showDetail) {
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text("金库状态", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("入库状态：${presentation.title}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (privacyEnabled) "最近入库：${privacyFakeDateTime(306)}"
                        else if (lastReceivedAt > 0) "最近入库：${formatTime(lastReceivedAt)}" else "最近入库：等待第一笔账目",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (smsGranted) "短信补收：已开启" else "短信补收：未开启",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    presentation.gapHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = statusColor) }
                    Text(
                        "银行短信和支付消息先入库，你确认后才正式记账。入库中断期间可由短信补收遗漏账目。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                if (listenerStatus != ListenerStatus.OK || runtimeStatus == VaultRuntimeStatus.ERROR || !smsGranted) {
                    TextButton(onClick = {
                        when {
                            listenerStatus == ListenerStatus.DISABLED -> openListenerSettings(context)
                            listenerStatus == ListenerStatus.DISCONNECTED || runtimeStatus == VaultRuntimeStatus.ERROR ->
                                AssetsNotificationListenerService.recoverNow(context)
                            !smsGranted -> smsPermissionLauncher.launch(
                                arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
                            )
                        }
                        showDetail = false
                    }) {
                        Text(
                            when {
                                listenerStatus == ListenerStatus.DISABLED -> "打开系统设置"
                                listenerStatus == ListenerStatus.DISCONNECTED || runtimeStatus == VaultRuntimeStatus.ERROR -> "立即恢复"
                                else -> "开启短信补收"
                            }
                        )
                    }
                } else {
                    TextButton(onClick = { showDetail = false }) { Text("知道了") }
                }
            },
            dismissButton = {}
        )
    }
}

@Composable
private fun VaultMetric(
    label: String,
    value: String,
    detail: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(
            detail.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
