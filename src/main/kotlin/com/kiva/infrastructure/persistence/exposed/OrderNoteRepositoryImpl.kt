package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.ordernote.entity.OrderNote
import com.kiva.domain.aggregate.ordernote.entity.OrderNoteText
import com.kiva.domain.aggregate.ordernote.repository.OrderNoteRepository
import com.kiva.infrastructure.persistence.exposed.tbl.OrderNoteTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.ZoneOffset

/** ordernote_tbl. Runs inside the use case's transaction and never opens one itself. */
class OrderNoteRepositoryImpl : OrderNoteRepository {

    override fun add(note: OrderNote): OrderNote {
        val id = OrderNoteTbl.insertAndGetId {
            it[orderId] = note.orderId.value
            it[text] = note.text.value
            it[authorId] = note.authorId.value
            it[createdOn] = note.createdOn.atOffset(ZoneOffset.UTC)
        }
        return OrderNote.constructFromPersisted(id.value, note.orderId, note.text, note.authorId, note.createdOn)
    }

    override fun findAllOf(orderId: OrderId): List<OrderNote> =
        OrderNoteTbl.selectAll()
            .where { OrderNoteTbl.orderId eq orderId.value }
            .orderBy(OrderNoteTbl.createdOn to SortOrder.ASC, OrderNoteTbl.id to SortOrder.ASC)
            .map { it.toNote() }

    private fun ResultRow.toNote(): OrderNote = OrderNote.constructFromPersisted(
        id = this[OrderNoteTbl.id].value,
        orderId = OrderId.constructFromPersisted(this[OrderNoteTbl.orderId]),
        text = OrderNoteText.constructFromPersisted(this[OrderNoteTbl.text]),
        authorId = UserId.constructFromPersisted(this[OrderNoteTbl.authorId]),
        createdOn = this[OrderNoteTbl.createdOn].toInstant(),
    )
}
