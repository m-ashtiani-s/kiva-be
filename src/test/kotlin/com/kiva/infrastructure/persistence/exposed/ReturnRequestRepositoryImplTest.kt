package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import com.kiva.domain.aggregate.refund.model.valueobject.RefundMethod
import com.kiva.domain.aggregate.returnrequest.entity.ReturnRequest
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReceiptLine
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnItem
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnPickup
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnReason
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnShippingPayer
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnText
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.tbl.ReturnRequestTbl
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** returnrequest_tbl on kiva_test (phase 7 step 7.2): a return written and read back whole, its version, its lists, its one-per-order wall. */
class ReturnRequestRepositoryImplTest {

    private val db = TestDatabase.database
    private val repository = ReturnRequestRepositoryImpl()
    private val ids = ReturnIdGeneratorImpl()
    private val now = Instant.parse("2026-10-19T08:00:00.123456Z")
    private val sara = UserId.constructFromPersisted(7)
    private val operator = UserId.constructFromPersisted(900)
    private val orderIds = mutableListOf<Long>()
    private var nextOrder = 987_654_400L

    @AfterEach
    fun deleteWhatWasStored() {
        transaction(db) { ReturnRequestTbl.deleteWhere { ReturnRequestTbl.orderId inList orderIds } }
    }

    private fun text(value: String) = ReturnText.constructFromPersisted(value)

    private fun newReturn(orderId: Long = nextOrder++, userId: UserId = sara, at: Instant = now): ReturnRequest = transaction(db) {
        orderIds += orderId
        ReturnRequest.request(
            ids.nextReturnId(), OrderId.constructFromPersisted(orderId), OrderCode.constructFromPersisted("KV-${orderId % 1_000_000}"), userId,
            ReturnReason.NOT_AS_PICTURED, text("رنگش با عکس فرق داشت"),
            listOf(ReturnItem.constructFromPersisted(1, 2), ReturnItem.constructFromPersisted(3, 1)),
            listOf(MediaId.constructFromPersisted("med_" + "1".padStart(22, '0'))), RefundMethod.BANK_TRANSFER, at,
        ).also(repository::add)
    }

    private fun read(id: ReturnId) = transaction(db) { repository.find(id) }

    private fun change(id: ReturnId, block: (ReturnRequest) -> Unit) = transaction(db) {
        val ret = repository.lock(id)!!
        block(ret)
        repository.save(ret)
    }

    @Test
    fun `a new return reads back as it was asked, its code from its id`() {
        val asked = newReturn()

        val back = read(asked.id)!!

        assertEquals("RT-${1000 + asked.id.value}", back.code.value)
        assertEquals(
            listOf(asked.orderId, asked.orderCode, sara, ReturnStatus.REQUESTED, ReturnReason.NOT_AS_PICTURED, "رنگش با عکس فرق داشت", asked.items, asked.mediaIds),
            listOf(back.orderId, back.orderCode, back.userId, back.status, back.reason, back.description?.value, back.items, back.mediaIds),
        )
        assertEquals(listOf(RefundMethod.BANK_TRANSFER, ReturnShippingPayer.KIVA, now, 0L), listOf(back.refundMethod, back.shippingPaidBy, back.createdOn, back.version))
    }

    @Test
    fun `every step is written and read back - the decision, the pickup, the receipt, the refund - and the version goes up each time`() {
        val id = newReturn().id
        val pickup = ReturnPickup.constructFromPersisted(ShippingMethodCode.TIPAX, now.plusSeconds(86_400), TrackingCode.constructFromPersisted("TPX4821760"), text("زنگ بزنید"))

        change(id) { it.approve(ReturnShippingPayer.CUSTOMER, text("با تیپاکس بفرست"), operator, now.plusSeconds(60)).getOrNull()!! }
        change(id) { it.schedulePickup(pickup).getOrNull()!! }
        change(id) {
            it.receive(listOf(ReceiptLine.constructFromPersisted(1, 2, 1), ReceiptLine.constructFromPersisted(3, 0, 0)), text("یکی خراب بود"), operator, now.plusSeconds(120))
                .getOrNull()!!
        }
        change(id) { it.markRefunded(RefundId.constructFromPersisted(31), now.plusSeconds(180)).getOrNull()!! }
        val back = read(id)!!

        assertEquals(ReturnStatus.REFUNDED, back.status)
        assertEquals(listOf(ReturnShippingPayer.CUSTOMER, "با تیپاکس بفرست", now.plusSeconds(60), operator), listOf(back.shippingPaidBy, back.instructions?.value, back.decidedOn, back.decidedBy))
        assertEquals(pickup, back.pickup)
        assertEquals(listOf(ReceiptLine.constructFromPersisted(1, 2, 1), ReceiptLine.constructFromPersisted(3, 0, 0)), back.receipt?.lines)
        assertEquals("یکی خراب بود", back.receipt?.note?.value)
        assertEquals(listOf(now.plusSeconds(120), operator), listOf(back.receivedOn, back.receivedBy))
        assertEquals(listOf(RefundId.constructFromPersisted(31), now.plusSeconds(180)), listOf(back.refundId, back.refundedOn))
        assertEquals(4L, back.version)
    }

    @Test
    fun `turned down and closed read back with their reasons`() {
        val rejected = newReturn().id
        val closed = newReturn().id

        change(rejected) { it.reject(text("عکس‌ها مشکلی نشون نمی‌دن"), operator, now).getOrNull()!! }
        change(closed) { it.approve(null, null, operator, now).getOrNull()!! }
        change(closed) { it.close(text("کالا نرسید"), hasOpenRefund = false, now.plusSeconds(60)).getOrNull()!! }

        assertEquals("عکس‌ها مشکلی نشون نمی‌دن", read(rejected)?.rejectionReason?.value)
        assertEquals(listOf(ReturnStatus.CLOSED, "کالا نرسید", now.plusSeconds(60)), read(closed)?.let { listOf(it.status, it.closeReason?.value, it.closedOn) })
    }

    @Test
    fun `saving over a version someone else saved is ConcurrentChangeException - the row keeps their change`() {
        val id = newReturn().id
        val stale = read(id)!!
        change(id) { it.approve(null, null, operator, now).getOrNull()!! }

        stale.reject(text("دیر رسید"), operator, now)
        assertFailsWith<ConcurrentChangeException> { transaction(db) { repository.save(stale) } }

        assertEquals(ReturnStatus.APPROVED, read(id)?.status)
    }

    @Test
    fun `an order's returns newest first, a customer's newest first and at most as many as asked - none of another's`() {
        val order = nextOrder++
        val first = newReturn(order)
        change(first.id) { it.reject(text("عکس‌ها مشکلی نشون نمی‌دن"), operator, now).getOrNull()!! }
        val second = newReturn(order, at = now.plusSeconds(60))
        val third = newReturn(at = now.plusSeconds(120))
        newReturn(userId = UserId.constructFromPersisted(8))

        assertEquals(listOf(second.id, first.id), transaction(db) { repository.findOfOrder(OrderId.constructFromPersisted(order)) }.map { it.id })
        assertEquals(listOf(third.id, second.id), transaction(db) { repository.findOfUser(sara, 2) }.map { it.id })
        assertEquals(3, transaction(db) { repository.findOfUser(sara, 50) }.size)
        assertNull(read(ReturnId.constructFromPersisted(999_999_999)))
    }

    @Test
    fun `a second return of an order that isn't REJECTED is refused by returnrequest_one_per_order_idx - after a rejected one it is fine`() {
        val order = nextOrder++
        val first = newReturn(order)

        val refused = assertFailsWith<ExposedSQLException> { newReturn(order) }
        assertEquals("returnrequest_one_per_order_idx", (refused.cause as? PSQLException)?.serverErrorMessage?.constraint)

        change(first.id) { it.reject(text("عکس‌ها مشکلی نشون نمی‌دن"), operator, now).getOrNull()!! }
        assertTrue(runCatching { newReturn(order) }.isSuccess)
    }

    @Test
    fun `ids come from the sequence, each once`() {
        val a = transaction(db) { ids.nextReturnId() }
        val b = transaction(db) { ids.nextReturnId() }

        assertEquals(a.value + 1, b.value)
    }
}
