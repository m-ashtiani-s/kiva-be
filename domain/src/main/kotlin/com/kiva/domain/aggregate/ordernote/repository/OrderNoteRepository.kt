package com.kiva.domain.aggregate.ordernote.repository

import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.ordernote.entity.OrderNote

/** The panel's notes on orders, kept as rows that are only ever added. */
interface OrderNoteRepository {

    /** Stores a new note and gives it back with its id. */
    fun add(note: OrderNote): OrderNote

    /** Every note of [orderId], oldest first. */
    fun findAllOf(orderId: OrderId): List<OrderNote>
}
