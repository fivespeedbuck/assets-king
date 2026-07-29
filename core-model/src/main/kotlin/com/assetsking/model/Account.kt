package com.assetsking.model

enum class AccountType { ASSET, CREDIT, LOAN }

enum class BalanceStatus { UNCHECKED, CONFIRMED, ESTIMATED }

data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    /** Assets store available balance; credit/loan accounts store outstanding debt. */
    val balance: Money = Money.ZERO,
    val cardTail: String? = null,
    val creditLimit: Money? = null,
    val statementDay: Int? = null,
    val dueDay: Int? = null,
    val balanceStatus: BalanceStatus = BalanceStatus.UNCHECKED,
    val lastCheckedAt: Long? = null
)
