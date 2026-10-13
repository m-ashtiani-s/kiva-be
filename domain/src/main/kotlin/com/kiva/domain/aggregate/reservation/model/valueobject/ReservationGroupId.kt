package com.kiva.domain.aggregate.reservation.model.valueobject

/** The id of a reservation group (from reservationgroupid_seq). Its public face is [ReservationGroupCode]. */
@JvmInline
value class ReservationGroupId private constructor(val value: Long) {

    init {
        require(value > 0) { "Invalid ReservationGroupId $value" }
    }

    companion object {
        /** For values from the sequence or read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Long): ReservationGroupId = ReservationGroupId(value)
    }
}

/**
 * A reservation group's code (`SG-3021`): the panel's `shipmentGroupCode`, the orders that ship together. Made from the
 * group's id, so it is unique without a check.
 */
@JvmInline
value class ReservationGroupCode private constructor(val value: String) {

    init {
        require(FORMAT.matches(value)) { "Invalid ReservationGroupCode '$value'" }
    }

    companion object {
        private val FORMAT = Regex("SG-[1-9][0-9]{0,17}")

        fun of(id: ReservationGroupId): ReservationGroupCode = ReservationGroupCode("SG-${id.value}")

        fun constructFromPersisted(value: String): ReservationGroupCode = ReservationGroupCode(value)
    }
}

/** Where a reservation group is: still holding its orders, or let go of them to be prepared. */
enum class ReservationGroupStatus {
    HOLDING,
    RELEASED,
}
