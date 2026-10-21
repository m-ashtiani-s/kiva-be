package com.kiva.infrastructure.persistence.exposed.projector

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.valueobject.ChangeRequest
import com.kiva.domain.aggregate.order.model.valueobject.ChangeType
import com.kiva.domain.aggregate.order.model.valueobject.OrderPhoto
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.StoredOrders
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

/**
 * The two columns of orderqm_tbl the panel's report counts (V30, roadmap phase 4 step 4.4): when the first photos were
 * sent, and whether a change was ever asked for. Each change in its own transaction, as in the app.
 */
class OrderQmReportColumnsProjectorTest {

    private val db = TestDatabase.database
    private val orders = StoredOrders()
    private val now = orders.now
    private val operator = UserId.constructFromPersisted(900)

    @AfterEach
    fun deleteWhatWasStored() = orders.deleteAll()

    private fun at(minutes: Long): Instant = now.plus(Duration.ofMinutes(minutes))
    private fun note(text: String) = StatusNote.constructFromPersisted(text)

    /** firstPhotosSentOn and changeRequestedEver as the read model has them. */
    private fun columnsOf(order: Order): Pair<Instant?, Boolean> = transaction(db) {
        OrderQmTbl.select(OrderQmTbl.firstPhotosSentOn, OrderQmTbl.changeRequestedEver).where { OrderQmTbl.orderId eq order.id.value }.single()
            .let { it[OrderQmTbl.firstPhotosSentOn]?.toInstant() to it[OrderQmTbl.changeRequestedEver] }
    }

    private fun paid(): Order {
        val order = orders.place(listOf(orders.line(1, orders.newBag())))
        return orders.change(order.id) { it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000B1"), it.totals.payable, false, UserId.SYSTEM, at(2)).getOrNull()!! }
    }

    private fun photosSent(order: Order, minutes: Long) {
        orders.change(order.id) {
            it.attachPhotos(listOf(OrderPhoto(MediaId.constructFromPersisted("med_" + "1".padStart(22, '0')), 1, MediaView.FRONT, 1)), operator, at(minutes)).getOrNull()!!
        }
        orders.change(order.id) { it.sendPhotos(MessengerChannel.TELEGRAM, null, 24, operator, at(minutes)).getOrNull()!! }
    }

    @Test
    fun `placed and paid - no photos sent, no change asked`() {
        val order = paid()

        assertEquals(null to false, columnsOf(order))
    }

    @Test
    fun `the first photos sent set when - a change asked marks the order - a refusal and a second batch leave both`() {
        val order = paid()
        photosSent(order, 10)
        assertEquals(at(10) to false, columnsOf(order))

        orders.change(order.id) { it.requestChange(ChangeRequest(ChangeType.OTHER, null, null, note("بند بلندتر")), null, order.userId, at(20)).getOrNull()!! }
        assertEquals(at(10) to true, columnsOf(order))

        orders.change(order.id) { it.rejectChange(note("نداریم"), operator, at(30)).getOrNull()!! }
        photosSent(order, 40)

        assertEquals(at(10) to true, columnsOf(order))
    }
}
