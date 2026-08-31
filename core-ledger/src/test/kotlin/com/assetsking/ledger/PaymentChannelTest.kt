package com.assetsking.ledger

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaymentChannelTest {
    @Test
    fun legacyMissingSourceChannelCanOnlyBeExplicitlyMatchedToKnownRefundChannel() {
        assertTrue(PaymentChannel.refundSourceCompatible(null, "微信"))
        assertTrue(PaymentChannel.refundSourceCompatible("微信支付", "微信"))
        assertFalse(PaymentChannel.refundSourceCompatible(null, null))
        assertFalse(PaymentChannel.refundSourceCompatible("支付宝", "微信"))
    }

    @Test
    fun refundOrderPlatformMustMatchWhenOriginalExpenseHasOne() {
        assertTrue(OrderPlatform.refundSourceCompatible("美团", "美团"))
        assertTrue(OrderPlatform.refundSourceCompatible(null, "美团"))
        assertTrue(OrderPlatform.refundSourceCompatible(null, null))
        assertFalse(OrderPlatform.refundSourceCompatible("美团", null))
        assertFalse(OrderPlatform.refundSourceCompatible("美团", "淘宝"))
        assertTrue(OrderPlatform.isKnown("淘宝闪购"))
        assertTrue(OrderPlatform.isKnown("饿了么"))
        assertFalse(OrderPlatform.isKnown("微信"))
    }
}
