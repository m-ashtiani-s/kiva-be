package com.kiva.domain.aggregate.order.model.valueobject

/**
 * The number of an order, from a database sequence (`orderid_seq`), handed out before the order's first event. It
 * never leaves the server: the shopper sees the order's [OrderCode], made from it. Also the id of the order's event
 * stream (`ORDER/42`).
 */
@JvmInline
value class OrderId private constructor(val value: Long) {

    // Never typed by anyone: from the sequence or read back from storage, so a wrong one is a bug.
    init {
        require(value > 0) { "Invalid OrderId $value" }
    }

    companion object {
        /** For values from the sequence or read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Long): OrderId = OrderId(value)
    }
}
