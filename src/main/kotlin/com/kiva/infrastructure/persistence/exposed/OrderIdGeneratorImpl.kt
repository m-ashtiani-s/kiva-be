package com.kiva.infrastructure.persistence.exposed

import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.repository.OrderIdGenerator
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/** Takes the next number from the sequence of V15__orders.sql. Runs inside the use case's transaction. */
class OrderIdGeneratorImpl : OrderIdGenerator {
    override fun nextOrderId(): OrderId =
        TransactionManager.current().exec("SELECT nextval('$ORDER_ID_SEQUENCE')") { rows ->
            rows.next()
            OrderId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval('$ORDER_ID_SEQUENCE') returned nothing")

    private companion object {
        const val ORDER_ID_SEQUENCE = "orderid_seq"
    }
}
