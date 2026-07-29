package com.assetsking.model

data class Transfer(
    val id: String,
    val fromAccountId: String,
    val toAccountId: String,
    val amount: Money,
    val occurredAt: Long,
    val note: String? = null
)
