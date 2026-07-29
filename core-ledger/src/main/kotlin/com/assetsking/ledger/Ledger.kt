package com.assetsking.ledger

import com.assetsking.model.Account
import com.assetsking.model.AccountType
import com.assetsking.model.Money
import com.assetsking.model.Transaction
import com.assetsking.model.TransactionType
import com.assetsking.model.Transfer

class Ledger(accounts: Iterable<Account> = emptyList()) {
    private val accountsById = accounts.associateBy { it.id }.toMutableMap()

    fun account(id: String): Account = accountsById[id]
        ?: error("账户不存在：$id")

    fun accounts(): List<Account> = accountsById.values.toList()

    fun post(transaction: Transaction): Account {
        require(transaction.amount.cents > 0) { "流水金额必须大于 0" }
        require(transaction.accountId in accountsById) { "账户不存在：${transaction.accountId}" }
        if (transaction.status != com.assetsking.model.RecordStatus.CONFIRMED) {
            return account(transaction.accountId)
        }

        val current = account(transaction.accountId)
        val delta = when (transaction.type) {
            TransactionType.EXPENSE, TransactionType.FEE -> liabilityAwareDelta(current.type, -transaction.amount.cents)
            TransactionType.INCOME, TransactionType.REFUND -> liabilityAwareDelta(current.type, transaction.amount.cents)
        }
        val updated = current.copy(balance = Money(current.balance.cents + delta))
        accountsById[updated.id] = updated
        return updated
    }

    fun post(transfer: Transfer): Pair<Account, Account> {
        require(transfer.amount.cents > 0) { "转账金额必须大于 0" }
        require(transfer.fromAccountId != transfer.toAccountId) { "转出和转入账户不能相同" }
        val from = account(transfer.fromAccountId)
        val to = account(transfer.toAccountId)

        val fromUpdated = from.copy(
            balance = Money(from.balance.cents + outgoingDelta(from.type, transfer.amount))
        )
        val toUpdated = to.copy(
            balance = Money(to.balance.cents + incomingDelta(to.type, transfer.amount))
        )
        accountsById[fromUpdated.id] = fromUpdated
        accountsById[toUpdated.id] = toUpdated
        return fromUpdated to toUpdated
    }

    private fun liabilityAwareDelta(type: AccountType, assetDelta: Long): Long = when (type) {
        AccountType.ASSET -> assetDelta
        AccountType.CREDIT, AccountType.LOAN -> -assetDelta
    }

    private fun outgoingDelta(type: AccountType, amount: Money): Long =
        liabilityAwareDelta(type, -amount.cents)

    private fun incomingDelta(type: AccountType, amount: Money): Long =
        liabilityAwareDelta(type, amount.cents)
}
