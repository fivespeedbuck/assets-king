package com.assetsking.ledger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountExpressionTest {

    @Test
    fun `simple add like the REQ example`() {
        assertEquals(50.5, AmountExpression.evaluate("38.5+12"))
    }

    @Test
    fun `multiply and divide take precedence`() {
        assertEquals(14.0, AmountExpression.evaluate("2+3×4"))
        assertEquals(14.0, AmountExpression.evaluate("2+3*4"))
    }

    @Test
    fun `subtract with both minus forms`() {
        assertEquals(26.5, AmountExpression.evaluate("38.5-12"))
        assertEquals(26.5, AmountExpression.evaluate("38.5−12"))
    }

    @Test
    fun `division by zero is invalid`() {
        assertNull(AmountExpression.evaluate("10÷0"))
    }

    @Test
    fun `incomplete or invalid expressions return null`() {
        assertNull(AmountExpression.evaluate(""))
        assertNull(AmountExpression.evaluate("12+"))
        assertNull(AmountExpression.evaluate("12.3.4"))
        assertNull(AmountExpression.evaluate("abc"))
    }

    @Test
    fun `chained operations`() {
        assertEquals(100.0, AmountExpression.evaluate("10+20+30+40"))
        assertEquals(7.5, AmountExpression.evaluate("15÷2"))
    }
}
