package com.kiva.domain.aggregate.reservation

import com.kiva.common.logging.getLogger
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import com.kiva.domain.aggregate.reservation.entity.ReservationGroup
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.reservation.repository.ReservationGroupIdGenerator
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import java.time.Instant

/**
 * «رزرو ۴ روزه» (roadmap section 13): which orders a customer's held reservation takes, what paying an order does to
 * it, and letting it go when its time is over. It never saves; the use case holds the customer's reservation lock
 * (section 11, lock 4) and saves the group and the orders in its transaction.
 */
class ReservationDomainService(private val reservationGroupIdGenerator: ReservationGroupIdGenerator) {

    private val logger = getLogger()

    /**
     * The group an order to [address] placed at [now] joins, shipping free with it: the customer's [holding] group, while
     * it holds and the address is its own, unedited. Null otherwise, and for an address not in the book.
     */
    fun groupTaking(holding: ReservationGroup?, address: AddressSnapshot?, now: Instant): ReservationGroup? =
        holding?.takeIf { address != null && it.takes(address, now) }

    /**
     * What [order] being paid at [now] does, with what is true now (and kept in OrderPaid as decided):
     *
     * | the order | now | becomes |
     * |---|---|---|
     * | joined a group when placed | the group still holds | RESERVED in it |
     * | joined a group when placed | its time is over | PROCESSING, shipping with it all the same |
     * | asked to reserve | the customer holds no group | RESERVED in a new group, its time starting now |
     * | asked to reserve | a group started meanwhile, at its address | RESERVED in that group |
     * | asked to reserve | a group started meanwhile, elsewhere | PROCESSING: it ships sooner, nobody loses |
     * | neither | | PROCESSING |
     *
     * [holding] is the customer's group still marked HOLDING, read under their reservation lock.
     */
    fun onPaid(order: Order, holding: ReservationGroup?, settings: ShopSettings, now: Instant): OnPaid {
        order.reservationGroupId?.let { joined ->
            val held = holding != null && holding.id == joined && holding.isHolding(now)
            if (!held) logger.info("onPaid: order ${order.code.value} joined a reservation whose time is over; it ships with it all the same")
            return OnPaid(held, joined, started = null)
        }
        if (!order.reserveRequested) return OnPaid(held = false, groupId = null, started = null)
        if (holding == null) {
            val group = ReservationGroup.start(
                reservationGroupIdGenerator.nextReservationGroupId(), order.userId, order.id, order.address, order.shippingMethod,
                settings.reservationHoldDays, now,
            )
            return OnPaid(held = true, groupId = group.id, started = group)
        }
        if (holding.takes(order.address, now)) return OnPaid(held = true, groupId = holding.id, started = null)
        // A group started between this order's placing and its payment (or one ending right now): it ships on its own.
        logger.info("onPaid: order ${order.code.value} asked to reserve, but group ${holding.code.value} holds another address or is ending; it is prepared now")
        return OnPaid(held = false, groupId = null, started = null)
    }

    /**
     * The group's time is over: its held orders go to be prepared, and it lets go. Not before its time; running again
     * changes nothing. True when it let go now.
     */
    fun release(group: ReservationGroup, heldOrders: List<Order>, now: Instant): Boolean {
        logger.info("release group=${group.code.value} orders=${heldOrders.map { it.code.value }}")
        require(heldOrders.all { it.reservationGroupId == group.id }) { "Orders of another group in ${group.code.value}" }
        if (now.isBefore(group.expiresOn)) return false
        heldOrders.forEach { it.releaseFromReservation(UserId.SYSTEM, now) }
        return group.release(now)
    }

    /**
     * @property held the order is RESERVED; otherwise PROCESSING.
     * @property groupId the group it is in, held or not.
     * @property started a new group the order starts, to be saved.
     */
    data class OnPaid(val held: Boolean, val groupId: ReservationGroupId?, val started: ReservationGroup?)
}
