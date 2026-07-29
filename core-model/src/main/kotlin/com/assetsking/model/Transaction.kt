package com.assetsking.model

enum class TransactionType { EXPENSE, INCOME, REFUND, FEE }

enum class TransactionCategory {
    UNCATEGORIZED,
    DINING,
    TRANSPORT,
    SHOPPING,
    HOUSING,
    UTILITIES,
    MEDICAL,
    EDUCATION,
    ENTERTAINMENT,
    DIGITAL_SERVICES,
    FINANCIAL_FEES,
    OTHER
}

enum class RecordStatus { CONFIRMED, PENDING_CONFIRMATION, IGNORED }

data class Transaction(
    val id: String,
    val accountId: String,
    val amount: Money,
    val type: TransactionType,
    val occurredAt: Long,
    val merchant: String? = null,
    val note: String? = null,
    val category: TransactionCategory = TransactionCategory.UNCATEGORIZED,
    val status: RecordStatus = RecordStatus.CONFIRMED
)
