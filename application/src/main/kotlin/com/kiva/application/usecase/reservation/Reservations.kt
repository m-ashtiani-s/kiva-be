package com.kiva.application.usecase.reservation

import com.kiva.application.dto.ReservationInfoDto
import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.domain.aggregate.address.repository.AddressBookRepository
import com.kiva.domain.aggregate.cart.model.ReservationStanding
import com.kiva.domain.aggregate.geo.repository.GeoRepository
import com.kiva.domain.aggregate.order.model.policy.OrderCodePolicy
import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.ShippingConsolidation
import com.kiva.domain.aggregate.reservation.ReservationDomainService
import com.kiva.domain.aggregate.reservation.entity.ReservationGroup
import com.kiva.domain.aggregate.reservation.repository.ReservationGroupRepository
import java.time.Instant

/**
 * What a customer's «رزرو ۴ روزه» means where the shop shows it (roadmap section 13): on the cart and checkout pages (the
 * switch, and whether an order joins the held reservation), on order cards and on the reservation's own page. One place
 * (Rule 15), so the cart, checkout and placing an order never disagree on whether an order joins. Reads only, inside
 * the caller's transaction.
 */
class Reservations(
    private val reservationGroupRepository: ReservationGroupRepository,
    private val addressBookRepository: AddressBookRepository,
    private val geoRepository: GeoRepository,
    private val orderQmRepository: OrderQmRepository,
    private val reservationDomainService: ReservationDomainService,
) {

    /**
     * The customer's held reservation, and the one an order to [addressId] joins (their default address when null, as
     * the cart has no address of its own). An address that isn't theirs joins nothing.
     */
    fun standingOf(userId: UserId, addressId: AddressId?, now: Instant): Standing {
        val holding = reservationGroupRepository.findHolding(userId)?.takeIf { it.isHolding(now) } ?: return Standing.NONE
        return Standing(holding, reservationDomainService.groupTaking(holding, snapshotOf(userId, addressId), now))
    }

    /** The customer's group still marked HOLDING, whatever its time: what placing an order decides with. */
    fun holdingOf(userId: UserId): ReservationGroup? = reservationGroupRepository.findHolding(userId)

    /** The reservation info of each RESERVED order among [orders] (an order card's `reservation`), by order. */
    fun infosOf(orders: List<OrderQm>, now: Instant): Map<OrderId, ReservationInfoDto> {
        val held = orders.filter { it.status == OrderStatus.RESERVED && it.reservationGroupId != null }
        if (held.isEmpty()) return emptyMap()
        val groups = reservationGroupRepository.findAll(held.mapNotNull { it.reservationGroupId })
        val codes = orderQmRepository.paidCodesIn(groups.keys)
        return held.associate { order ->
            val group = groups[order.reservationGroupId] ?: error("Order ${order.code.value} is held in a group that isn't there")
            order.orderId to infoOf(group, codes[group.id].orEmpty().map { it.value }, now)
        }
    }

    /** The customer's reservation page (`GET /me/reservation`); null when nothing holds now. */
    fun activeInfoOf(userId: UserId, now: Instant): ReservationInfoDto? {
        val group = reservationGroupRepository.findHolding(userId)?.takeIf { it.isHolding(now) } ?: return null
        return infoOf(group, orderQmRepository.paidCodesIn(listOf(group.id))[group.id].orEmpty().map { it.value }, now)
    }

    private fun infoOf(group: ReservationGroup, orderCodes: List<String>, now: Instant) = ReservationInfoDto(
        active = group.isHolding(now),
        orderCode = OrderCodePolicy.codeOf(group.rootOrderId).value,
        startedAt = group.startedOn,
        expiresAt = group.expiresOn,
        remainingSeconds = maxOf(0, group.expiresOn.epochSecond - now.epochSecond),
        groupOrderCodes = orderCodes,
    )

    /** The saved address as an order would copy it; null when it isn't the customer's, or they have none. */
    private fun snapshotOf(userId: UserId, addressId: AddressId?): AddressSnapshot? {
        val book = addressBookRepository.find(userId)
        val address = (if (addressId == null) book.addresses.firstOrNull { it.isDefault } else book.find(addressId)) ?: return null
        // The address table's foreign key to the city list keeps both there.
        val province = geoRepository.findProvince(address.provinceId) ?: error("Address ${address.id.value} names a province that isn't there")
        val city = geoRepository.findCity(address.cityId) ?: error("Address ${address.id.value} names a city that isn't there")
        return AddressSnapshot.of(address, province.name, city.name)
    }

    /**
     * @property holding the customer's reservation, while it holds.
     * @property joins the one an order joins: [holding], when the order goes to its address.
     */
    data class Standing(val holding: ReservationGroup?, val joins: ReservationGroup?) {
        /** What the cart's commands go by. */
        fun forCart(): ReservationStanding = ReservationStanding(holding != null, joins?.let { ShippingConsolidation(it.shippingMethod) })

        companion object {
            val NONE = Standing(holding = null, joins = null)
        }
    }
}
