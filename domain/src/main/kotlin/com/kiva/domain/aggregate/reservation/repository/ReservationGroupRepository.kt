package com.kiva.domain.aggregate.reservation.repository

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.reservation.entity.ReservationGroup
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import java.time.Instant

/**
 * Reservation groups, kept as rows. A change takes the customer's lock first ([lockFor], section 11 lock 4), so a payment
 * that starts or joins a group and the job that lets one go never cross; [save] checks the row's version too.
 */
interface ReservationGroupRepository {

    /** Locks the reservations of [userId] until the transaction ends (a transaction-level advisory lock). */
    fun lockFor(userId: UserId)

    /** The customer's group still marked HOLDING (at most one), whether or not its time is over yet. */
    fun findHolding(userId: UserId): ReservationGroup?

    fun find(id: ReservationGroupId): ReservationGroup?

    fun findAll(ids: Collection<ReservationGroupId>): Map<ReservationGroupId, ReservationGroup>

    fun add(group: ReservationGroup)

    /** Writes the group's change, only if its row still has the version it was read with. */
    fun save(group: ReservationGroup)

    /** The release job's candidates: groups still HOLDING whose time is over at [now], the oldest first. */
    fun findDue(now: Instant, limit: Int): List<ReservationGroupId>
}

/** Hands out the ids of new reservation groups, from a database sequence. */
interface ReservationGroupIdGenerator {
    fun nextReservationGroupId(): ReservationGroupId
}
