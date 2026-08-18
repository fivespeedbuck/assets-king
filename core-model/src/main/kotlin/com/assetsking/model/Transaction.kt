package com.assetsking.model

enum class TransactionType {
    EXPENSE,
    INCOME,
    REFUND,
    FEE,
    LOAN_DISBURSEMENT, // 借款到账：现金+、负债+，不是收入
    LOAN_PAYMENT,      // 贷款还款：现金-、负债-，不是消费
    LOAN_PREPAYMENT,   // 提前还款：只减本金、不标普通期次、不当消费；结清走 settleLoanPlan
    REIMBURSEMENT      // 报销到账：现金+，不是普通收入（REQ 待确认§9/报销§5）
}

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
