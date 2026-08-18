package com.assetsking.ledger

import com.assetsking.model.AccountType
import com.assetsking.model.TransactionType
import kotlin.test.Test
import kotlin.test.assertEquals

class BalanceMathTest {

    // ── 交易方向 ──

    @Test
    fun `asset expense decreases balance`() {
        assertEquals(-3500L, BalanceMath.transactionDelta(AccountType.ASSET, TransactionType.EXPENSE, 3500))
    }

    @Test
    fun `asset income increases balance`() {
        assertEquals(10000L, BalanceMath.transactionDelta(AccountType.ASSET, TransactionType.INCOME, 10000))
    }

    @Test
    fun `credit expense increases debt`() {
        // 信用卡余额越大 = 欠款越多，消费使负债 +金额
        assertEquals(3500L, BalanceMath.transactionDelta(AccountType.CREDIT, TransactionType.EXPENSE, 3500))
    }

    @Test
    fun `loan payment on cash account decreases cash`() {
        // 贷款还款记在现金账户上：现金减少；本金/利息的负债变化另在贷款计划里维护
        assertEquals(-5000L, BalanceMath.transactionDelta(AccountType.ASSET, TransactionType.LOAN_PAYMENT, 5000))
    }

    @Test
    fun `loan disbursement is not income but increases cash`() {
        assertEquals(20000L, BalanceMath.transactionDelta(AccountType.ASSET, TransactionType.LOAN_DISBURSEMENT, 20000))
    }

    // ── 转账方向 ──

    @Test
    fun `transfer moves money out of asset and into asset`() {
        assertEquals(-1000L, BalanceMath.transferOutDelta(AccountType.ASSET, 1000))
        assertEquals(1000L, BalanceMath.transferInDelta(AccountType.ASSET, 1000))
    }

    @Test
    fun `repay credit card from asset is a transfer`() {
        // 资产转出给信用卡：资产 -1000，信用卡负债 -1000
        assertEquals(-1000L, BalanceMath.transferOutDelta(AccountType.ASSET, 1000))
        assertEquals(-1000L, BalanceMath.transferInDelta(AccountType.CREDIT, 1000))
    }

    // ── 余额重算 ──

    @Test
    fun `no checkpoint applies all deltas on opening balance`() {
        val deltas = listOf(
            LedgerDelta(occurredAt = 1000, deltaCents = -3500),
            LedgerDelta(occurredAt = 2000, deltaCents = 10000),
        )
        assertEquals(6500L, BalanceMath.balance(openingBalanceCents = 0, checkpoint = null, deltas = deltas))
    }

    @Test
    fun `checkpoint bakes in earlier events and replays later ones`() {
        // 银行短信在 T=2000 报余额 65709（已含 T=1500 的 -3500 消费）。
        // T=3000 又确认一笔 -500。最终 = 65709 - 500。
        val checkpoint = BalanceCheckpoint(balanceCents = 65709, checkedAt = 2000)
        val deltas = listOf(
            LedgerDelta(occurredAt = 1500, deltaCents = -3500), // baked in，不计
            LedgerDelta(occurredAt = 3000, deltaCents = -500),  // 之后，计
        )
        assertEquals(65209L, BalanceMath.balance(0, checkpoint, deltas))
    }

    @Test
    fun `random confirmation order is deterministic`() {
        val checkpoint = BalanceCheckpoint(balanceCents = 10000, checkedAt = 5000)
        val a = LedgerDelta(occurredAt = 6000, deltaCents = -1000)
        val b = LedgerDelta(occurredAt = 4000, deltaCents = -2000) // 在检查点之前，baked in
        // 无论列表顺序如何，结果一致：只有 a（occurredAt > 5000）计入
        assertEquals(9000L, BalanceMath.balance(0, checkpoint, listOf(a, b)))
        assertEquals(9000L, BalanceMath.balance(0, checkpoint, listOf(b, a)))
    }

    @Test
    fun `the double-count fix — transaction at checkpoint time is baked in`() {
        // 复现原 bug：银行短信报「支出35.00，余额657.09」，occurredAt == postedAt == checkedAt。
        // 消费那笔的 occurredAt 不大于检查点时刻，不得再扣一次。
        val checkpoint = BalanceCheckpoint(balanceCents = 65709, checkedAt = 100_000)
        val deltas = listOf(
            LedgerDelta(occurredAt = 100_000, deltaCents = -3500), // 同一时刻，baked in
        )
        assertEquals(65709L, BalanceMath.balance(0, checkpoint, deltas))
    }

    // ── 差额核对（REQ 账户对账 §4）：上次权威余额 + 期间变化 = 本次银行余额 ──

    @Test
    fun `expected balance chains two checkpoints with confirmed deltas`() {
        val prev = BalanceCheckpoint(balanceCents = 65709, checkedAt = 100_000)
        val deltas = listOf(
            LedgerDelta(occurredAt = 90_000, deltaCents = -3500), // 检查点之前，baked in
            LedgerDelta(occurredAt = 150_000, deltaCents = -2498), // 期间，计
            LedgerDelta(occurredAt = 300_000, deltaCents = -500),  // 本次检查点之后，不计
        )
        assertEquals(63211L, BalanceMath.expectedBalance(prev, deltas, newCheckedAt = 200_000))
    }

    @Test
    fun `expected balance includes evidence at the new checkpoint time itself`() {
        // 银行短信「支出24.98 余额657.09」：余额是扣款后的值，本条证据发生在检查点时刻，须计入
        val prev = BalanceCheckpoint(balanceCents = 68207, checkedAt = 100_000)
        val deltas = listOf(LedgerDelta(occurredAt = 200_000, deltaCents = -2498))
        assertEquals(65709L, BalanceMath.expectedBalance(prev, deltas, newCheckedAt = 200_000))
    }

    // ── 余额校验 ──

    @Test
    fun `balance check matches when bank equals expected`() {
        val r = BalanceMath.checkBalance(65709, -3500, 62209)
        assertEquals(true, r.matches)
        assertEquals(0L, r.diffCents)
    }

    @Test
    fun `balance check detects mismatch`() {
        val r = BalanceMath.checkBalance(65709, -3500, 62000)
        assertEquals(false, r.matches)
        assertEquals(-209L, r.diffCents)
    }

    @Test
    fun `no bank balance means no check needed`() {
        val r = BalanceMath.checkBalance(65709, -3500, null)
        assertEquals(true, r.matches)
        assertEquals(null, r.bankCents)
    }
}
