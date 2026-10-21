package com.kiva.infrastructure.persistence.exposed

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
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

/**
 * V30's backfill of the report's two columns from the orders' events, for the orders made before the projector kept
 * them: its two UPDATE statements, read from the migration itself, run again over rows emptied on purpose. Rolled back,
 * so nothing else sees it.
 */
class ReportColumnsBackfillTest {

    private val db = TestDatabase.database
    private val orders = StoredOrders()
    private val now = orders.now
    private val operator = UserId.constructFromPersisted(900)

    @AfterEach
    fun deleteWhatWasStored() = orders.deleteAll()

    private fun at(minutes: Long): Instant = now.plus(Duration.ofMinutes(minutes))
    private fun note(text: String) = StatusNote.constructFromPersisted(text)

    /** V30's statements that fill orderqm_tbl, without their comments. */
    private val backfill: List<String> =
        File("src/main/resources/db/migration/V30__admin_settings.sql").readText()
            .lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            .split(';').map { it.trim() }.filter { it.startsWith("UPDATE orderqm_tbl") }

    private fun paid(payment: String): Order {
        val order = orders.place(listOf(orders.line(1, orders.newBag())))
        return orders.change(order.id) { it.markPaid(PaymentPublicId.constructFromPersisted(payment), it.totals.payable, false, UserId.SYSTEM, at(2)).getOrNull()!! }
    }

    private fun photosSent(order: Order, minutes: Long) {
        orders.change(order.id) {
            it.attachPhotos(listOf(OrderPhoto(MediaId.constructFromPersisted("med_" + "1".padStart(22, '0')), 1, MediaView.FRONT, 1)), operator, at(minutes)).getOrNull()!!
        }
        orders.change(order.id) { it.sendPhotos(MessengerChannel.TELEGRAM, null, 24, operator, at(minutes)).getOrNull()!! }
    }

    @Test
    fun `the first batch's time and any change asked come back from the events - an order without either stays empty`() {
        assertEquals(2, backfill.size, "V30's two UPDATE statements of orderqm_tbl")
        val asked = paid("pay_0000000000C1")
        photosSent(asked, 10)
        orders.change(asked.id) { it.requestChange(ChangeRequest(ChangeType.OTHER, null, null, note("بند بلندتر")), null, asked.userId, at(20)).getOrNull()!! }
        orders.change(asked.id) { it.rejectChange(note("نداریم"), operator, at(30)).getOrNull()!! }
        photosSent(asked, 40)
        val quiet = paid("pay_0000000000C2")
        val ids = listOf(asked.id.value, quiet.id.value)

        val filled = transaction(db) {
            OrderQmTbl.update({ OrderQmTbl.orderId inList ids }) {
                it[firstPhotosSentOn] = null
                it[changeRequestedEver] = false
            }
            backfill.forEach { exec(it) }
            val rows = ids.map { id ->
                OrderQmTbl.select(OrderQmTbl.firstPhotosSentOn, OrderQmTbl.changeRequestedEver).where { OrderQmTbl.orderId eq id }.single()
                    .let { it[OrderQmTbl.firstPhotosSentOn]?.toInstant() to it[OrderQmTbl.changeRequestedEver] }
            }
            rollback()
            rows
        }

        assertEquals(listOf(at(10) to true, null to false), filled)
    }
}
