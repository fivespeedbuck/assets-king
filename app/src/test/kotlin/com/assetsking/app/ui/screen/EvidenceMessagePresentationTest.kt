package com.assetsking.app.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EvidenceMessagePresentationTest {
    @Test
    fun titleAndContentAreBothShown() {
        assertEquals(
            EvidenceMessagePresentation("微信支付", "付款给商户 12.00 元"),
            evidenceMessagePresentation("微信支付", "付款给商户 12.00 元")
        )
    }

    @Test
    fun blankTitleIsOmitted() {
        val message = evidenceMessagePresentation("  ", "付款成功")

        assertNull(message.title)
        assertEquals("付款成功", message.content)
    }

    @Test
    fun blankContentIsOmitted() {
        val message = evidenceMessagePresentation("交易提醒", "  ")

        assertEquals("交易提醒", message.title)
        assertNull(message.content)
    }

    @Test
    fun duplicateTitleAndContentAreShownOnlyOnce() {
        val message = evidenceMessagePresentation("交易提醒", "交易提醒")

        assertEquals("交易提醒", message.title)
        assertNull(message.content)
    }

    @Test
    fun surroundingWhitespaceIsTrimmedBeforeDeduplication() {
        assertEquals(
            EvidenceMessagePresentation("交易提醒", null),
            evidenceMessagePresentation(" 交易提醒 ", "\n交易提醒\t")
        )
    }
}
