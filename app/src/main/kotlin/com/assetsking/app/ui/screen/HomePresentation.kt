package com.assetsking.app.ui.screen

import com.assetsking.database.CategoryEntity
import com.assetsking.database.TransactionEntity
import com.assetsking.app.notification.VaultRuntimeStatus

internal data class HomeRepaymentPage(
    val label: String,
    val amountCents: Long
)

internal fun homeRepaymentPages(
    totalDueCount: Int,
    totalDueCents: Long,
    paidCount: Int,
    paidCents: Long,
    totalDebtCents: Long = totalDueCents
): List<HomeRepaymentPage> = listOf(
    HomeRepaymentPage(
        label = when {
            totalDueCount > 0 -> "本月待还 ${totalDueCount}笔"
            totalDebtCents <= 0L -> "全部还清"
            else -> "待完善还款信息"
        },
        amountCents = if (totalDueCount > 0) totalDueCents else totalDebtCents
    ),
    HomeRepaymentPage("本月已还 ${paidCount}笔", paidCents)
)

internal enum class HomeVaultSeverity { NORMAL, WARNING, RECOVERING, ERROR }

internal data class HomeVaultPresentation(
    val title: String,
    val badge: String,
    val severity: HomeVaultSeverity,
    val gapHint: String? = null
)

internal fun homeVaultPresentation(
    listenerStatus: ListenerStatus,
    runtimeStatus: VaultRuntimeStatus,
    smsGranted: Boolean
): HomeVaultPresentation = when {
    listenerStatus == ListenerStatus.DISABLED -> HomeVaultPresentation(
        title = "入库已中断",
        badge = "中断",
        severity = HomeVaultSeverity.ERROR,
        gapHint = "有一段时间未能入库，请检查"
    )
    listenerStatus == ListenerStatus.DISCONNECTED -> HomeVaultPresentation(
        title = "入库暂时中断",
        badge = "中断",
        severity = HomeVaultSeverity.ERROR,
        gapHint = if (smsGranted) "正在等待恢复，期间账目将由短信补收" else "有一段时间未能入库，请检查"
    )
    runtimeStatus == VaultRuntimeStatus.ERROR -> HomeVaultPresentation(
        title = "补收入库失败",
        badge = "需处理",
        severity = HomeVaultSeverity.ERROR,
        gapHint = "最近一次补收或入库未完成，请立即恢复"
    )
    runtimeStatus == VaultRuntimeStatus.RECOVERING -> HomeVaultPresentation(
        title = "金库恢复中",
        badge = "补收中",
        severity = HomeVaultSeverity.RECOVERING
    )
    !smsGranted -> HomeVaultPresentation(
        title = "金库正常",
        badge = "待完善",
        severity = HomeVaultSeverity.WARNING,
        gapHint = "短信补收未开启，不影响当前入库"
    )
    else -> HomeVaultPresentation(
        title = "金库正常",
        badge = "",
        severity = HomeVaultSeverity.NORMAL
    )
}

internal data class HomeSpendingBreakdown(
    val totalCents: Long,
    val necessaryCents: Long,
    val optionalCents: Long
)

internal fun effectiveNecessary(
    transaction: com.assetsking.database.TransactionEntity,
    categories: List<CategoryEntity>
): Boolean {
    val categoryDefault = categories.firstOrNull {
        !it.isArchived && (it.id == transaction.category || it.name == transaction.category)
    }?.defaultNecessary
    return transaction.necessity ?: categoryDefault ?: false
}

internal data class HomeModulePreviewSpec(
    val key: String,
    val title: String,
    val hint: String,
    val primary: String,
    val secondary: String
)

internal val homeModulePreviewSpecs = listOf(
    HomeModulePreviewSpec("reimbursement", "待报销", "待报销金额、笔数与状态", "2 笔 · 待报 ¥320", "本月已报 ¥680"),
    HomeModulePreviewSpec("recurring", "周期扣款", "本月周期扣款金额与笔数", "3 笔 · 待扣 ¥268", "查看本月扣款计划"),
    HomeModulePreviewSpec("budget", "本月预算", "必要预算与非必要消费进度", "必要预算 68%", "非必要消费 32%"),
    HomeModulePreviewSpec("accounts", "分账户余额", "常用资产账户余额", "工资卡 ¥4,700", "现金 ¥300")
)

internal fun monthSpendingBreakdown(
    transactions: List<TransactionEntity>,
    categories: List<CategoryEntity>,
    fromInclusive: Long,
    toInclusive: Long
): HomeSpendingBreakdown {
    val inWindow = transactions.filter { it.status == "CONFIRMED" && it.occurredAt in fromInclusive..toInclusive }
    val refundsByExpense = inWindow
        .filter { it.type == "REFUND" && it.refundOfId != null }
        .groupBy { it.refundOfId!! }
        .mapValues { (_, refunds) -> refunds.sumOf { it.amountCents } }
    var necessary = 0L
    var optional = 0L
    inWindow.filter { it.type == "EXPENSE" || it.type == "FEE" }.forEach { expense ->
        val net = (
            expense.amountCents -
                expense.reimbursedCents -
                (refundsByExpense[expense.id] ?: 0L)
            ).coerceAtLeast(0L)
        val isNecessary = effectiveNecessary(expense, categories)
        if (isNecessary) necessary += net else optional += net
    }
    return HomeSpendingBreakdown(
        totalCents = necessary + optional,
        necessaryCents = necessary,
        optionalCents = optional
    )
}

internal fun necessaryBudgetCents(
    budgets: List<com.assetsking.database.BudgetEntity>,
    categories: List<CategoryEntity>,
    month: String
): Long {
    val necessaryByCategory = buildMap<String, Boolean> {
        categories.filterNot { it.isArchived }.forEach { category ->
            val necessary = category.defaultNecessary ?: return@forEach
            put(category.id, necessary)
            put(category.name, necessary)
        }
    }
    return budgets
        .filter { it.month == month && necessaryByCategory[it.category] == true }
        .sumOf { it.monthlyLimitCents }
}

internal fun visibleDebtAccounts(accounts: List<com.assetsking.database.AccountEntity>) =
    accounts.filter {
        !it.archived && when (it.type) {
            com.assetsking.model.AccountType.CREDIT.name -> it.balanceCents > 0L
            com.assetsking.model.AccountType.LOAN.name -> true
            else -> false
        }
    }
