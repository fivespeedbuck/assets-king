package com.assetsking.ui.format

import com.assetsking.model.TransactionCategory
import com.assetsking.model.TransactionType

fun categoryLabel(category: TransactionCategory): String = when (category) {
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

fun categoryLabel(storedCategory: String): String =
    runCatching { TransactionCategory.valueOf(storedCategory) }
        .getOrNull()
        ?.let(::categoryLabel)
        ?: storedCategory

fun transactionCategoryLabel(transactionType: String, storedCategory: String): String? =
    if (
        transactionType in setOf(
            TransactionType.REFUND.name,
            TransactionType.REIMBURSEMENT.name,
            TransactionType.LOAN_DISBURSEMENT.name,
            TransactionType.LOAN_PAYMENT.name,
            TransactionType.LOAN_PREPAYMENT.name
        ) &&
        storedCategory == TransactionCategory.UNCATEGORIZED.name
    ) {
        null
    } else {
        categoryLabel(storedCategory)
    }

fun accountTypeLabel(type: String): String = when (type) {
    "ASSET" -> "资产账户"
    "CREDIT" -> "信用卡"
    "LOAN" -> "贷款"
    else -> type
}
