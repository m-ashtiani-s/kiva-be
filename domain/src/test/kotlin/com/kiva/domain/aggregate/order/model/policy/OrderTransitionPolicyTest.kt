package com.kiva.domain.aggregate.order.model.policy

import com.kiva.domain.aggregate.order.model.valueobject.CancelReason
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.CANCELLED
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.DELIVERED
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderTransitionPolicyTest {

    @Test
    fun `allowedTransitions - CANCELLED from the four paid statuses before shipping, DELIVERED from SHIPPED, nothing from the rest`() {
        val expected = mapOf(
            OrderStatus.RESERVED to setOf(CANCELLED),
            OrderStatus.PROCESSING to setOf(CANCELLED),
            OrderStatus.PHOTO_SENT to setOf(CANCELLED),
            OrderStatus.CHANGE_REQUESTED to setOf(CANCELLED),
            OrderStatus.SHIPPED to setOf(DELIVERED),
        )

        OrderStatus.entries.forEach { assertEquals(expected[it] ?: emptySet(), OrderTransitionPolicy.allowedFrom(it), "$it") }
    }

    @Test
    fun `the customer cancels a RESERVED or PROCESSING order no photo was ever sent for - nothing else`() {
        assertTrue(OrderTransitionPolicy.customerMayCancel(OrderStatus.RESERVED, photoEverSent = false))
        assertTrue(OrderTransitionPolicy.customerMayCancel(OrderStatus.PROCESSING, photoEverSent = false))
        assertFalse(OrderTransitionPolicy.customerMayCancel(OrderStatus.PROCESSING, photoEverSent = true))
        OrderStatus.entries.filter { it != OrderStatus.RESERVED && it != OrderStatus.PROCESSING }.forEach {
            assertFalse(OrderTransitionPolicy.customerMayCancel(it, photoEverSent = false), "$it")
        }
    }

    @Test
    fun `a purchase that stands is paid and not cancelled - a cancelled one was still paid once`() {
        assertTrue(CANCELLED.isPaid)
        assertFalse(CANCELLED.countsAsPurchase)
        assertTrue(OrderStatus.PROCESSING.countsAsPurchase)
        assertFalse(OrderStatus.EXPIRED.countsAsPurchase)
    }

    @Test
    fun `a tracking code is 6 to 40 Latin letters, digits and dashes, kept in capitals - Persian digits taken as Latin`() {
        assertEquals("TPX4821760", TrackingCode.makeNew(" tpx۴۸۲۱۷۶۰ ").getOrNull()?.value)
        assertEquals("183920674512039845612307", TrackingCode.makeNew("183920674512039845612307").getOrNull()?.value)
        assertEquals("TRACKING_CODE_REQUIRED", TrackingCode.makeNew("  ").failureOrNull()?.code)
        listOf("TPX12", "1".repeat(41), "TPX 482176", "TPX_482176").forEach {
            assertEquals("TRACKING_CODE_INVALID", TrackingCode.makeNew(it).failureOrNull()?.code, it)
        }
    }

    @Test
    fun `a status note is optional and at most 500 - a cancel reason is one of the contract's`() {
        assertNull(StatusNote.makeNew(" ").getOrNull())
        assertEquals("STATUS_NOTE_TOO_LONG", StatusNote.makeNew("ا".repeat(501)).failureOrNull()?.code)
        assertEquals("StatusNote(***)", StatusNote.constructFromPersisted("خصوصی").toString())
        assertEquals(CancelReason.FOUND_CHEAPER, CancelReason.makeNew("FOUND_CHEAPER").getOrNull())
        assertEquals("CANCEL_REASON_INVALID", CancelReason.makeNew("BORED").failureOrNull()?.code)
    }
}
