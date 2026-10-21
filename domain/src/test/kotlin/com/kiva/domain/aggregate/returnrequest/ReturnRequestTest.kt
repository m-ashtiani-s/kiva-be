package com.kiva.domain.aggregate.returnrequest

import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import com.kiva.domain.aggregate.returnrequest.ReturnFixtures.inStatus
import com.kiva.domain.aggregate.returnrequest.ReturnFixtures.item
import com.kiva.domain.aggregate.returnrequest.ReturnFixtures.now
import com.kiva.domain.aggregate.returnrequest.ReturnFixtures.operator
import com.kiva.domain.aggregate.returnrequest.ReturnFixtures.pickup
import com.kiva.domain.aggregate.returnrequest.ReturnFixtures.received
import com.kiva.domain.aggregate.returnrequest.ReturnFixtures.refundId
import com.kiva.domain.aggregate.returnrequest.ReturnFixtures.requested
import com.kiva.domain.aggregate.returnrequest.ReturnFixtures.text
import com.kiva.domain.aggregate.returnrequest.entity.ReturnRequest
import com.kiva.domain.aggregate.returnrequest.model.policy.ReturnTransitionPolicy
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnPickup
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnReason
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnShippingPayer
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.APPROVED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.CLOSED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.PICKUP_SCHEDULED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.RECEIVED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.REFUNDED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.REJECTED
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus.REQUESTED
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A return's steps (roadmap section 12.3): Sara's caramel Aida, sent back because it isn't as pictured. */
class ReturnRequestTest {

    /** The step that takes a return to [to], tried on [ret]; REQUESTED has none (a return is only asked for once). */
    private fun step(ret: ReturnRequest, to: ReturnStatus): Result<ReturnRequest, ResultFailure>? = when (to) {
        REQUESTED -> null
        APPROVED -> ret.approve(null, null, operator, now)
        REJECTED -> ret.reject(text("عکس‌ها مشکلی نشون نمی‌دن"), operator, now)
        PICKUP_SCHEDULED -> ret.schedulePickup(pickup)
        RECEIVED -> ret.receive(listOf(received(1, 1, 1)), null, operator, now)
        REFUNDED -> ret.markRefunded(RefundId.constructFromPersisted(32), now)
        CLOSED -> ret.close(text("کالا نرسید"), hasOpenRefund = false, now)
    }

    @Nested
    inner class Transitions {
        @Test
        fun `every step from every status - 7 by 7 - goes exactly where the policy allows, and is 422 with what is allowed otherwise`() {
            ReturnStatus.entries.forEach { from ->
                ReturnStatus.entries.forEach { to ->
                    val ret = inStatus(from)
                    val allowed = ReturnTransitionPolicy.allowedFrom(from, hasOpenRefund = false)
                    val result = step(ret, to)
                    when {
                        result == null -> assertTrue(to !in allowed, "nothing goes back to REQUESTED, from $from")
                        to in allowed -> {
                            assertTrue(result.isSuccess, "$from to $to: ${result.failureOrNull()?.code}")
                            assertEquals(to, ret.status)
                        }
                        else -> {
                            val failure = result.failureOrNull()
                            assertEquals("INVALID_STATUS_TRANSITION", failure?.code, "$from to $to")
                            assertEquals(mapOf("from" to from.name, "to" to to.name, "allowed" to allowed.map { it.name }.sorted()), failure?.meta, "$from to $to")
                            assertEquals(from, ret.status, "$from to $to changed it")
                        }
                    }
                }
            }
        }

        @Test
        fun `allowedTransitions of each status, with and without a refund open`() {
            val expected = mapOf(
                REQUESTED to setOf(APPROVED, REJECTED),
                APPROVED to setOf(PICKUP_SCHEDULED, RECEIVED, CLOSED),
                PICKUP_SCHEDULED to setOf(PICKUP_SCHEDULED, RECEIVED, CLOSED),
                RECEIVED to setOf(REFUNDED, CLOSED),
                REJECTED to emptySet(),
                REFUNDED to emptySet(),
                CLOSED to emptySet(),
            )

            ReturnStatus.entries.forEach { status ->
                assertEquals(expected.getValue(status), inStatus(status).allowedTransitions(hasOpenRefund = false), "$status")
                val withRefund = if (status == RECEIVED) emptySet() else expected.getValue(status)
                assertEquals(withRefund, inStatus(status).allowedTransitions(hasOpenRefund = true), "$status with a refund open")
            }
        }
    }

    @Nested
    inner class Request {
        @Test
        fun `a new return is REQUESTED, RT- and 1000 plus its id, its way back paid as its reason suggests`() {
            val ret = requested()

            assertEquals(REQUESTED, ret.status)
            assertEquals("RT-1012", ret.code.value)
            assertEquals(ReturnShippingPayer.KIVA, ret.shippingPaidBy)
            assertEquals(now, ret.createdOn)
            assertEquals(0, ret.version)
            assertNull(ret.decidedOn)
        }

        @Test
        fun `the shop's four faults are the shop's to pay - changed mind and other the customer's`() {
            val payers = ReturnReason.entries.associateWith { requested(reason = it).shippingPaidBy }

            assertEquals(
                mapOf(
                    ReturnReason.NOT_AS_PICTURED to ReturnShippingPayer.KIVA, ReturnReason.MANUFACTURING_DEFECT to ReturnShippingPayer.KIVA,
                    ReturnReason.WRONG_ITEM to ReturnShippingPayer.KIVA, ReturnReason.DAMAGED_IN_TRANSIT to ReturnShippingPayer.KIVA,
                    ReturnReason.CHANGED_MIND to ReturnShippingPayer.CUSTOMER, ReturnReason.OTHER to ReturnShippingPayer.CUSTOMER,
                ),
                payers,
            )
        }

        @Test
        fun `a return of no line, or of a line twice, is a bug and stops loudly`() {
            assertThrows<IllegalArgumentException> { requested(items = emptyList()) }
            assertThrows<IllegalArgumentException> { requested(items = listOf(item(1), item(1, 2))) }
        }
    }

    @Nested
    inner class Decision {
        @Test
        fun `approved without a payer keeps the suggestion and has no instructions of its own - decided now by the operator`() {
            val ret = requested()

            ret.approve(null, null, operator, now).getOrNull()!!

            assertEquals(APPROVED, ret.status)
            assertEquals(ReturnShippingPayer.KIVA, ret.shippingPaidBy)
            assertNull(ret.instructions)
            assertEquals(listOf(now, operator), listOf(ret.decidedOn, ret.decidedBy))
        }

        @Test
        fun `approved with the customer paying and instructions of the operator's own`() {
            val ret = requested()

            ret.approve(ReturnShippingPayer.CUSTOMER, text("با پست بفرست"), operator, now).getOrNull()!!

            assertEquals(ReturnShippingPayer.CUSTOMER, ret.shippingPaidBy)
            assertEquals("با پست بفرست", ret.instructions?.value)
        }

        @Test
        fun `turned down - REJECTED with its reason, decided now`() {
            val ret = requested()

            ret.reject(text("عکس‌ها مشکلی نشون نمی‌دن"), operator, now).getOrNull()!!

            assertEquals(REJECTED, ret.status)
            assertEquals("عکس‌ها مشکلی نشون نمی‌دن", ret.rejectionReason?.value)
            assertEquals(now, ret.decidedOn)
        }
    }

    @Nested
    inner class Pickup {
        @Test
        fun `a second pickup replaces the first as a whole`() {
            val ret = inStatus(PICKUP_SCHEDULED)
            val another = ReturnPickup.constructFromPersisted(ShippingMethodCode.POST, scheduledFor = null, trackingCode = null, note = text("زنگ بزنید"))

            ret.schedulePickup(another).getOrNull()!!

            assertEquals(another, ret.pickup)
        }
    }

    @Nested
    inner class Receipt {
        private fun approvedOfTwo(): ReturnRequest = requested(items = listOf(item(1, 2), item(3))).also { it.approve(null, null, operator, now).getOrNull()!! }

        @Test
        fun `received with each line once - RECEIVED with its receipt, by the operator, now`() {
            val ret = approvedOfTwo()

            ret.receive(listOf(received(3, 1, 0), received(1, 2, 1)), text("یکیش دسته‌ش پاره بود"), operator, now).getOrNull()!!

            assertEquals(RECEIVED, ret.status)
            assertEquals(listOf(received(3, 1, 0), received(1, 2, 1)), ret.receipt?.lines)
            assertEquals(listOf(now, operator), listOf(ret.receivedOn, ret.receivedBy))
        }

        @Test
        fun `an empty parcel - everything 0 - is received`() {
            val ret = approvedOfTwo()

            assertTrue(ret.receive(listOf(received(1, 0, 0), received(3, 0, 0)), null, operator, now).isSuccess)
        }

        @Test
        fun `a line left out, one more, or another line is RECEIPT_ITEMS_MISMATCH with the lines expected - nothing changes`() {
            listOf(
                listOf(received(1, 1, 1)),
                listOf(received(1, 1, 1), received(3, 1, 1), received(2, 1, 1)),
                listOf(received(1, 1, 1), received(2, 1, 1)),
            ).forEach { lines ->
                val ret = approvedOfTwo()

                val failure = ret.receive(lines, null, operator, now).failureOrNull()

                assertIs<ReturnRequest.ReceiveFailure.ItemsMismatch>(failure)
                assertEquals(listOf("items", "RECEIPT_ITEMS_MISMATCH"), listOf(failure.field, failure.code))
                assertEquals(mapOf("expectedItemIds" to listOf(1, 3)), failure.meta)
                assertEquals(APPROVED, ret.status)
            }
        }

        @Test
        fun `more back than went is RECEIVED_QUANTITY_EXCEEDS, more on the shelf than came RESTOCK_EXCEEDS_RECEIVED - each on its field, together`() {
            val ret = approvedOfTwo()

            val failure = ret.receive(listOf(received(1, 3, 0), received(3, 1, 2)), null, operator, now).failureOrNull()

            assertIs<ReturnRequest.ReceiveFailure.ItemsInvalid>(failure)
            assertEquals(
                listOf("items.0.receivedQuantity RECEIVED_QUANTITY_EXCEEDS {maxQuantity=2}", "items.1.restockQuantity RESTOCK_EXCEEDS_RECEIVED {}"),
                failure.causes.map { "${(it as FieldFailure).name} ${it.code} ${it.meta}" },
            )
            assertEquals(APPROVED, ret.status)
        }
    }

    @Nested
    inner class Close {
        @Test
        fun `closed from RECEIVED - CLOSED with its reason, what went on the shelf stays in the receipt`() {
            val ret = inStatus(RECEIVED)

            ret.close(text("بازبینی نشون داد مشکل از استفاده بوده"), hasOpenRefund = false, now).getOrNull()!!

            assertEquals(CLOSED, ret.status)
            assertEquals(now, ret.closedOn)
            assertEquals(listOf(received(1, 1, 1)), ret.receipt?.lines)
        }

        @Test
        fun `a refund of it open is RETURN_REFUND_OPEN - nothing changes`() {
            val ret = inStatus(RECEIVED)

            assertEquals(Result.failure(ReturnRequest.CloseFailure.RefundOpen), ret.close(text("کالا نرسید"), hasOpenRefund = true, now))
            assertEquals(RECEIVED, ret.status)
        }
    }

    @Nested
    inner class Refunded {
        @Test
        fun `its refund completed - REFUNDED with it, and the same refund again changes nothing`() {
            val ret = inStatus(RECEIVED)

            ret.markRefunded(refundId, now).getOrNull()!!
            val again = ret.markRefunded(refundId, now.plusSeconds(60))

            assertEquals(REFUNDED, ret.status)
            assertEquals(listOf(refundId, now), listOf(ret.refundId, ret.refundedOn))
            assertTrue(again.isSuccess)
            assertEquals(now, ret.refundedOn)
        }
    }

    @Test
    fun `a stored return that breaks a rule is a bug and stops loudly - REJECTED without its reason`() {
        val ret = inStatus(REJECTED)

        assertThrows<IllegalArgumentException> {
            ReturnRequest.constructFromPersisted(
                ret.id, ret.orderId, ret.orderCode, ret.userId, REJECTED, ret.reason, ret.description, ret.items, ret.mediaIds, ret.refundMethod,
                ret.shippingPaidBy, ret.instructions, rejectionReason = null, ret.pickup, ret.receipt, ret.closeReason, ret.refundId, ret.createdOn,
                ret.decidedOn, ret.decidedBy, ret.receivedOn, ret.receivedBy, ret.closedOn, ret.refundedOn, ret.version,
            )
        }
    }

    @Test
    fun `printing a return shows neither its texts nor its pickup's note`() {
        val ret = inStatus(REJECTED)

        val printed = "$ret ${ret.rejectionReason} ${ret.description}"

        listOf("عکس", "رنگش").forEach { assertTrue(it !in printed, "$it in $printed") }
    }
}
