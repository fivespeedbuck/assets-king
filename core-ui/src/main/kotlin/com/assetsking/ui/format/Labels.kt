package com.assetsking.ui.format

import com.assetsking.model.TransactionCategory

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

fun accountTypeLabel(type: String): String = when (type) {
    "ASSET" -> "资产账户"
    "CREDIT" -> "信用卡"
    "LOAN" -> "贷款"
    else -> type
}
