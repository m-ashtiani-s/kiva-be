package com.kiva.domain.aggregate.returnrequest

import com.kiva.common.railway.Result
import com.kiva.common.railway.TextInvalid
import com.kiva.common.railway.TextTooLong
import com.kiva.domain.aggregate.returnrequest.model.policy.ReturnPolicy
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReceiptLine
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnCode
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnItem
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnOutcome
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnPickup
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnReason
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnReceipt
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnShippingPayer
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnText
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReturnValueObjectsTest {

    @Test
    fun `a return id is a positive whole number - anything else is RETURN_NOT_FOUND, never a 400`() {
        assertEquals(12L, ReturnId.makeNew(" 12 ").getOrNull()?.value)
        listOf(null, "", "0", "-3", "RT-1012", "1.5").forEach { raw ->
            assertEquals(Result.failure(ReturnId.Failure.Invalid), ReturnId.makeNew(raw), "$raw")
        }
        assertEquals("RETURN_NOT_FOUND", ReturnId.Failure.Invalid.code)
    }

    @Test
    fun `a return's code is RT- and 1000 plus its id`() {
        assertEquals("RT-1001", ReturnCode.forId(ReturnId.constructFromPersisted(1)).value)
        assertEquals("RT-1012", ReturnCode.forId(ReturnId.constructFromPersisted(12)).value)
        assertEquals("RT-10000", ReturnCode.forId(ReturnId.constructFromPersisted(9_000)).value)
        assertThrows<IllegalArgumentException> { ReturnCode.constructFromPersisted("RT-12") }
    }

    @Test
    fun `the names are taken exactly as the contract has them - anything else is the field's own code`() {
        assertEquals(ReturnReason.DAMAGED_IN_TRANSIT, ReturnReason.makeNew("DAMAGED_IN_TRANSIT").getOrNull())
        assertEquals(listOf("reason", "RETURN_REASON_INVALID"), ReturnReason.makeNew("damaged").failureOrNull()?.let { listOf(it.field, it.code) })
        assertEquals(ReturnShippingPayer.CUSTOMER, ReturnShippingPayer.makeNew("CUSTOMER").getOrNull())
        assertEquals(listOf("shippingPaidBy", "SHIPPING_PAYER_INVALID"), ReturnShippingPayer.makeNew("BOTH").failureOrNull()?.let { listOf(it.field, it.code) })
        assertEquals(ReturnStatus.PICKUP_SCHEDULED, ReturnStatus.makeNew("PICKUP_SCHEDULED").getOrNull())
        assertEquals(listOf("status", "RETURN_STATUS_INVALID"), ReturnStatus.makeNew("FOO").failureOrNull()?.let { listOf(it.field, it.code) })
    }

    @Test
    fun `only the shop's four faults count as its fault, and only a refunded or closed return settles the order`() {
        assertEquals(
            listOf(ReturnReason.NOT_AS_PICTURED, ReturnReason.MANUFACTURING_DEFECT, ReturnReason.WRONG_ITEM, ReturnReason.DAMAGED_IN_TRANSIT),
            ReturnReason.entries.filter { it.isKivaFault },
        )
        assertEquals(listOf(ReturnOutcome.CLOSED, ReturnOutcome.REFUNDED), ReturnOutcome.entries.filter { it.settlesReturn })
        assertEquals(listOf(ReturnStatus.REJECTED, ReturnStatus.REFUNDED, ReturnStatus.CLOSED), ReturnStatus.entries.filter { it.isFinal })
    }

    @Test
    fun `a return's text - blank is none, a line break becomes a space, too long TEXT_TOO_LONG with its length, a control character TEXT_INVALID`() {
        assertNull(ReturnText.makeNew("   ").getOrNull())
        assertEquals("رنگش فرق داشت ولی سالمه", ReturnText.makeNew("رنگش فرق داشت\nولی سالمه").getOrNull()?.value)
        assertEquals(Result.failure(TextTooLong(500)), ReturnText.makeNew("ا".repeat(501), 500))
        assertTrue(ReturnText.makeNew("ا".repeat(1000)).isSuccess)
        assertEquals(Result.failure(TextTooLong(1000)), ReturnText.makeNew("ا".repeat(1001)))
        assertEquals(Result.failure(TextInvalid), ReturnText.makeNew("سلام\u0007"))
        assertEquals("ReturnText(***)", ReturnText.constructFromPersisted("رنگش فرق داشت").toString())
    }

    @Test
    fun `a reason is 3 to 500 long - missing or shorter is the reason's own REQUIRED code, longer TEXT_TOO_LONG`() {
        val missing = ReturnText.Failure.RejectionReasonRequired

        assertEquals("نرسید", ReturnText.makeReason("نرسید", missing).getOrNull()?.value)
        assertEquals("نهه", ReturnText.makeReason("نهه", missing).getOrNull()?.value)
        listOf(null, "", "  ", "نه").forEach { assertEquals(Result.failure(missing), ReturnText.makeReason(it, missing), "$it") }
        assertEquals(Result.failure(TextTooLong(500)), ReturnText.makeReason("ا".repeat(501), ReturnText.Failure.CloseReasonRequired))
        assertEquals("CLOSE_REASON_REQUIRED", ReturnText.Failure.CloseReasonRequired.code)
    }

    @Test
    fun `a pickup is planned from an hour ago to 30 days on, by Tipax or Post only`() {
        val now = Instant.parse("2026-10-19T08:00:00Z")

        assertTrue(ReturnPolicy.pickupTimeFits(now.minus(Duration.ofHours(1)), now))
        assertTrue(ReturnPolicy.pickupTimeFits(now.plus(Duration.ofDays(30)), now))
        assertFalse(ReturnPolicy.pickupTimeFits(now.minus(Duration.ofMinutes(61)), now))
        assertFalse(ReturnPolicy.pickupTimeFits(now.plus(Duration.ofDays(30)).plusSeconds(1), now))
        assertThrows<IllegalArgumentException> { ReturnPickup.constructFromPersisted(ShippingMethodCode.COURIER, null, null, null) }
    }

    @Test
    fun `lines and receipts keep their numbers in range, and a receipt never restocks more than came`() {
        assertThrows<IllegalArgumentException> { ReturnItem.constructFromPersisted(0, 1) }
        assertThrows<IllegalArgumentException> { ReturnItem.constructFromPersisted(1, 21) }
        assertThrows<IllegalArgumentException> { ReceiptLine.constructFromPersisted(1, -1, 0) }
        assertThrows<IllegalArgumentException> { ReturnReceipt.constructFromPersisted(listOf(ReceiptLine.constructFromPersisted(1, 1, 2)), null) }
        assertThrows<IllegalArgumentException> {
            ReturnReceipt.constructFromPersisted(listOf(ReceiptLine.constructFromPersisted(1, 1, 1), ReceiptLine.constructFromPersisted(1, 1, 0)), null)
        }
    }
}
