package com.kiva.domain.aggregate.reservation.entity

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.reservation.model.valueobject.AddressFingerprint
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupCode
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupStatus
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Duration
import java.time.Instant

/**
 * «رزرو ۴ روزه» (roadmap section 13): a paid order held for [expiresOn], so the customer's next orders to the same
 * address join it and ship together, with one shipping cost. Kept as a row; a customer has at most one HOLDING (a
 * partial unique index), and every change takes the customer's lock (section 11, lock 4).
 *
 * Its time starts when its first order is **paid** ([startedOn]), and never moves: the orders that join don't extend
 * it. An unpaid order can't hold a group open, since nothing of it would be kept if it expired.
 *
 * @property addressId the saved address of its first order; null when that order went to an address not kept in the
 *   book, which nothing can join.
 * @property addressFingerprint what that address said then: editing it afterwards means a new order isn't joined.
 * @property shippingMethod its first order's: every order of the group ships with it.
 * @property version the row's, for the repository's check that nobody saved it in the meantime.
 */
class ReservationGroup private constructor(
    val id: ReservationGroupId,
    val code: ReservationGroupCode,
    val userId: UserId,
    val rootOrderId: OrderId,
    val addressId: AddressId?,
    val addressFingerprint: AddressFingerprint,
    val shippingMethod: ShippingMethodCode,
    val startedOn: Instant,
    val expiresOn: Instant,
    status: ReservationGroupStatus,
    releasedOn: Instant?,
    val version: Long,
) {
    var status: ReservationGroupStatus = status
        private set
    var releasedOn: Instant? = releasedOn
        private set

    init {
        require(expiresOn.isAfter(startedOn)) { "Group ${code.value} ends before it starts" }
        require((status == ReservationGroupStatus.RELEASED) == (releasedOn != null)) { "Group ${code.value} is $status with releasedOn=$releasedOn" }
    }

    /** Still holding its orders at [now]: not let go of, and its time not over. */
    fun isHolding(now: Instant): Boolean = status == ReservationGroupStatus.HOLDING && now.isBefore(expiresOn)

    /** An order to [address] placed at [now] joins this group: it is holding, and the address is its own, unedited. */
    fun takes(address: AddressSnapshot, now: Instant): Boolean =
        isHolding(now) && addressId != null && address.addressId == addressId && AddressFingerprint.of(address) == addressFingerprint

    /** Its time is over: it lets go of its orders. Letting go twice changes nothing; false then. */
    fun release(now: Instant): Boolean {
        if (status == ReservationGroupStatus.RELEASED) return false
        status = ReservationGroupStatus.RELEASED
        releasedOn = now
        return true
    }

    override fun equals(other: Any?): Boolean = other is ReservationGroup && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ReservationGroup(code=${code.value}, userId=${userId.value}, status=$status)"

    companion object {
        /** A new group, started by its first order, paid [now], for [holdDays]. */
        fun start(
            id: ReservationGroupId,
            userId: UserId,
            rootOrderId: OrderId,
            address: AddressSnapshot,
            shippingMethod: ShippingMethodCode,
            holdDays: Int,
            now: Instant,
        ): ReservationGroup {
            require(holdDays > 0) { "A reservation holds at least a day: $holdDays" }
            return ReservationGroup(
                id, ReservationGroupCode.of(id), userId, rootOrderId, address.addressId, AddressFingerprint.of(address), shippingMethod, now,
                now.plus(Duration.ofDays(holdDays.toLong())), ReservationGroupStatus.HOLDING, null, version = 0,
            )
        }

        fun constructFromPersisted(
            id: ReservationGroupId,
            code: ReservationGroupCode,
            userId: UserId,
            rootOrderId: OrderId,
            addressId: AddressId?,
            addressFingerprint: AddressFingerprint,
            shippingMethod: ShippingMethodCode,
            startedOn: Instant,
            expiresOn: Instant,
            status: ReservationGroupStatus,
            releasedOn: Instant?,
            version: Long,
        ): ReservationGroup =
            ReservationGroup(id, code, userId, rootOrderId, addressId, addressFingerprint, shippingMethod, startedOn, expiresOn, status, releasedOn, version)
    }
}
