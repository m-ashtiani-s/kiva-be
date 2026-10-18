package com.kiva.domain.aggregate.refund

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.refund.entity.Refund
import com.kiva.domain.aggregate.refund.model.valueobject.BankReference
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import com.kiva.domain.aggregate.refund.model.valueobject.RefundMethod
import com.kiva.domain.aggregate.refund.model.valueobject.RefundNote
import com.kiva.domain.aggregate.refund.model.valueobject.RefundReason
import com.kiva.domain.aggregate.refund.model.valueobject.RefundSource
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus.COMPLETED
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus.FAILED
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus.PENDING
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus.PROCESSING
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefundTest {

    private val now = Instant.parse("2026-10-18T08:00:00Z")
    private val admin = UserId.constructFromPersisted(1)
    private val reference = BankReference.constructFromPersisted("140507181234567")
    private val note = RefundNote.constructFromPersisted("شماره شبا تأیید نشد؛ دوباره باهات تماس می‌گیریم")

    private fun pending() = Refund.start(
        RefundId.constructFromPersisted(31), OrderId.constructFromPersisted(5), Money.constructFromPersisted(500_000), RefundMethod.ORIGINAL_PAYMENT,
        RefundSource.MANUAL, RefundReason.constructFromPersisted("کیف آسیب دیده بود"), admin, now,
    )

    private fun processing() = pending().also { it.markProcessing(null, now.plusSeconds(60)).getOrNull()!! }

    @Test
    fun `a new refund is PENDING, expected within 72 hours, with nothing completed`() {
        val refund = pending()

        assertEquals(PENDING, refund.status)
        assertEquals(now.plus(Duration.ofHours(72)), refund.expectedBy)
        assertNull(refund.referenceId)
        assertNull(refund.completedOn)
        assertEquals(admin, refund.createdBy)
    }

    @Nested
    inner class AllowedMoves {
        @Test
        fun `PENDING to PROCESSING, with its note - asking again changes nothing`() {
            val refund = pending()

            refund.markProcessing(note, now.plusSeconds(60)).getOrNull()!!
            refund.markProcessing(null, now.plusSeconds(120)).getOrNull()!!

            assertEquals(PROCESSING, refund.status)
            assertEquals(now.plusSeconds(60), refund.processingOn)
            assertEquals(note, refund.statusNote)
        }

        @Test
        fun `PENDING and PROCESSING to COMPLETED, with the bank's reference`() {
            listOf(pending(), processing()).forEach { refund ->
                refund.complete(reference, null, now.plusSeconds(600)).getOrNull()!!

                assertEquals(COMPLETED, refund.status)
                assertEquals(reference, refund.referenceId)
                assertEquals(now.plusSeconds(600), refund.completedOn)
            }
        }

        @Test
        fun `PENDING and PROCESSING to FAILED, with the note the customer sees`() {
            listOf(pending(), processing()).forEach { refund ->
                refund.fail(note, now.plusSeconds(600)).getOrNull()!!

                assertEquals(FAILED, refund.status)
                assertEquals(note, refund.statusNote)
                assertEquals(now.plusSeconds(600), refund.failedOn)
            }
        }
    }

    @Nested
    inner class RefusedMoves {
        private fun completed() = pending().also { it.complete(reference, null, now.plusSeconds(600)).getOrNull()!! }

        private fun failed() = pending().also { it.fail(null, now.plusSeconds(600)).getOrNull()!! }

        @Test
        fun `COMPLETED and FAILED are final - every move from them is INVALID_STATUS_TRANSITION`() {
            listOf(completed(), failed()).forEach { refund ->
                val from = refund.status
                assertEquals(Refund.InvalidTransition(from, PROCESSING), refund.markProcessing(null, now.plusSeconds(700)).failureOrNull())
                assertEquals(Refund.InvalidTransition(from, FAILED), refund.fail(null, now.plusSeconds(700)).failureOrNull())
                val again = refund.complete(reference, null, now.plusSeconds(700)).failureOrNull()
                assertIs<Refund.CompleteFailure.NotOpen>(again)
                assertEquals("INVALID_STATUS_TRANSITION", again.code)
                assertEquals(mapOf("from" to from.name, "to" to "COMPLETED"), again.meta)
            }
        }

        @Test
        fun `PROCESSING doesn't go back - a FAILED one doesn't go to PROCESSING either`() {
            assertEquals(Refund.InvalidTransition(FAILED, PROCESSING), failed().markProcessing(null, now).failureOrNull())
        }

        @Test
        fun `COMPLETED without the bank's reference is REFERENCE_ID_REQUIRED, and nothing changes`() {
            val refund = processing()

            val refused = refund.complete(null, note, now.plusSeconds(600)).failureOrNull()

            assertEquals(Refund.CompleteFailure.ReferenceRequired, refused)
            assertEquals("referenceId", refused?.field)
            assertEquals(PROCESSING, refund.status)
        }
    }

    @Test
    fun `a stored refund that breaks its rules stops loudly`() {
        assertThrows<IllegalArgumentException> {
            Refund.constructFromPersisted(
                RefundId.constructFromPersisted(31), OrderId.constructFromPersisted(5), Money.constructFromPersisted(1), RefundMethod.BANK_TRANSFER, COMPLETED,
                RefundSource.CANCELLATION, null, null, now, referenceId = null, statusNote = null, createdOn = now, createdBy = admin, processingOn = null,
                completedOn = now, failedOn = null, version = 1,
            )
        }
    }

    @Nested
    inner class Values {
        @Test
        fun `the method is ORIGINAL_PAYMENT or BANK_TRANSFER - there is no store credit`() {
            assertEquals(RefundMethod.BANK_TRANSFER, RefundMethod.makeNew("BANK_TRANSFER").getOrNull())
            assertEquals("REFUND_METHOD_INVALID", RefundMethod.makeNew("STORE_CREDIT").failureOrNull()?.code)
        }

        @Test
        fun `a target status is PROCESSING, COMPLETED or FAILED - PENDING is where a refund starts, never asked for`() {
            assertEquals(listOf(PROCESSING, COMPLETED, FAILED), listOf("PROCESSING", "COMPLETED", "FAILED").map { RefundStatus.makeNewTarget(it).getOrNull() })
            assertEquals("REFUND_STATUS_INVALID", RefundStatus.makeNewTarget("PENDING").failureOrNull()?.code)
        }

        @Test
        fun `the reason is required and at most 300`() {
            assertEquals("کیف آسیب دیده بود", RefundReason.makeNew("  کیف آسیب دیده بود ").getOrNull()?.value)
            assertEquals("REFUND_REASON_REQUIRED", RefundReason.makeNew("  ").failureOrNull()?.code)
            assertEquals("REFUND_REASON_TOO_LONG", RefundReason.makeNew("ا".repeat(301)).failureOrNull()?.code)
            assertTrue(RefundReason.makeNew("ا".repeat(300)).isSuccess)
        }

        @Test
        fun `the bank's reference is up to 64 letters, digits and dashes - Persian digits taken as Latin - blank is none`() {
            assertEquals("140507181234567", BankReference.makeNew("۱۴۰۵۰۷۱۸۱۲۳۴۵۶۷").getOrNull()?.value)
            assertNull(BankReference.makeNew(" ").getOrNull())
            assertEquals("REFERENCE_ID_INVALID", BankReference.makeNew("14050718 1234").failureOrNull()?.code)
            assertEquals("REFERENCE_ID_INVALID", BankReference.makeNew("1".repeat(65)).failureOrNull()?.code)
            assertEquals("BankReference(***)", BankReference.constructFromPersisted("123").toString())
        }

        @Test
        fun `the note is optional and at most 300`() {
            assertNull(RefundNote.makeNew(null).getOrNull())
            assertEquals("REFUND_NOTE_TOO_LONG", RefundNote.makeNew("ا".repeat(301)).failureOrNull()?.code)
        }

        @Test
        fun `a refund id from the path is a positive number - anything else is no refund`() {
            assertEquals(31L, RefundId.makeNew("31").getOrNull()?.value)
            listOf("0", "-4", "abc", null).forEach { assertEquals("REFUND_NOT_FOUND", RefundId.makeNew(it).failureOrNull()?.code, "$it") }
        }
    }
}
