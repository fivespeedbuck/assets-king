package com.assetsking.ledger

import com.assetsking.model.Account
import com.assetsking.model.AccountType
import com.assetsking.model.Money
import com.assetsking.model.Transaction
import com.assetsking.model.TransactionType
import com.assetsking.model.Transfer
import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerTest {
    private fun ledger() = Ledger(
        listOf(
            Account("cmb", "招商银行", AccountType.ASSET, Money.yuan(1000)),
            Account("cgb", "广发信用卡", AccountType.CREDIT, Money.yuan(300)),
            Account("huabei", "花呗", AccountType.LOAN, Money.yuan(100))
        )
    )

    @Test
    fun `asset expense reduces available balance`() {
        val ledger = ledger()
        ledger.post(Transaction("t1", "cmb", Money.yuan(50), TransactionType.EXPENSE, 1))
        assertEquals(Money.yuan(950), ledger.account("cmb").balance)
    }

    @Test
    fun `income increases asset balance but is not an expense`() {
        val ledger = ledger()
        ledger.post(Transaction("t1", "cmb", Money.yuan(200), TransactionType.INCOME, 1))
        assertEquals(Money.yuan(1200), ledger.account("cmb").balance)
    }

    @Test
    fun `credit and loan expense increase outstanding debt`() {
        val ledger = ledger()
        ledger.post(Transaction("t1", "cgb", Money.yuan(50), TransactionType.EXPENSE, 1))
        ledger.post(Transaction("t2", "huabei", Money.yuan(50), TransactionType.EXPENSE, 1))
        assertEquals(Money.yuan(350), ledger.account("cgb").balance)
        assertEquals(Money.yuan(150), ledger.account("huabei").balance)
    }

    @Test
    fun `repayment is a transfer and does not create a second expense`() {
        val ledger = ledger()
        ledger.post(Transaction("t1", "cgb", Money.yuan(200), TransactionType.EXPENSE, 1))
        ledger.post(Transfer("x1", "cmb", "cgb", Money.yuan(200), 2))
        assertEquals(Money.yuan(800), ledger.account("cmb").balance)
        assertEquals(Money.yuan(300), ledger.account("cgb").balance)
    }

    @Test
    fun `refund restores asset balance and reduces credit debt`() {
        val ledger = ledger()
        ledger.post(Transaction("t1", "cmb", Money.yuan(50), TransactionType.EXPENSE, 1))
        ledger.post(Transaction("t2", "cmb", Money.yuan(20), TransactionType.REFUND, 2))
        ledger.post(Transaction("t3", "cgb", Money.yuan(50), TransactionType.EXPENSE, 1))
        ledger.post(Transaction("t4", "cgb", Money.yuan(20), TransactionType.REFUND, 2))
        assertEquals(Money.yuan(970), ledger.account("cmb").balance)
        assertEquals(Money.yuan(330), ledger.account("cgb").balance)
    }

    @Test
    fun `loan disbursement increases asset cash`() {
        val ledger = ledger()
        ledger.post(Transaction("t1", "cmb", Money.yuan(300), TransactionType.LOAN_DISBURSEMENT, 1))
        assertEquals(Money.yuan(1300), ledger.account("cmb").balance)
    }

    @Test
    fun `loan payment decreases asset cash`() {
        val ledger = ledger()
        ledger.post(Transaction("t1", "cmb", Money.yuan(110), TransactionType.LOAN_PAYMENT, 1))
        assertEquals(Money.yuan(890), ledger.account("cmb").balance)
    }

    @Test
    fun `loan prepayment decreases asset cash`() {
        val ledger = ledger()
        ledger.post(Transaction("t1", "cmb", Money.yuan(300), TransactionType.LOAN_PREPAYMENT, 1))
        assertEquals(Money.yuan(700), ledger.account("cmb").balance)
    }
}
