package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.reservation.entity.ReservationGroup
import com.kiva.domain.aggregate.reservation.model.valueobject.AddressFingerprint
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupCode
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupStatus
import com.kiva.domain.aggregate.reservation.repository.ReservationGroupIdGenerator
import com.kiva.domain.aggregate.reservation.repository.ReservationGroupRepository
import com.kiva.infrastructure.persistence.exposed.tbl.ReservationGroupTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class ReservationGroupRepositoryImpl : ReservationGroupRepository {

    /** A transaction-level advisory lock, released by the commit or the rollback; it works before any group exists. */
    override fun lockFor(userId: UserId) {
        TransactionManager.current().exec("SELECT pg_advisory_xact_lock(hashtextextended('reservation:${userId.value}', 0))") { }
    }

    override fun findHolding(userId: UserId): ReservationGroup? =
        ReservationGroupTbl.selectAll()
            .where { (ReservationGroupTbl.userId eq userId.value) and (ReservationGroupTbl.status eq ReservationGroupStatus.HOLDING) }
            .singleOrNull()?.toGroup()

    override fun find(id: ReservationGroupId): ReservationGroup? =
        ReservationGroupTbl.selectAll().where { ReservationGroupTbl.groupId eq id.value }.singleOrNull()?.toGroup()

    override fun findAll(ids: Collection<ReservationGroupId>): Map<ReservationGroupId, ReservationGroup> {
        if (ids.isEmpty()) return emptyMap()
        return ReservationGroupTbl.selectAll().where { ReservationGroupTbl.groupId inList ids.map { it.value }.distinct() }
            .map { it.toGroup() }
            .associateBy { it.id }
    }

    override fun add(group: ReservationGroup) {
        ReservationGroupTbl.insert {
            it[groupId] = group.id.value
            it[code] = group.code.value
            it[userId] = group.userId.value
            it[rootOrderId] = group.rootOrderId.value
            it[addressId] = group.addressId?.value
            it[addressFingerprint] = group.addressFingerprint.toByteArray()
            it[shippingMethod] = group.shippingMethod
            it[startedOn] = group.startedOn.utc()
            it[expiresOn] = group.expiresOn.utc()
            it[status] = group.status
            it[releasedOn] = group.releasedOn?.utc()
            it[version] = group.version
        }
    }

    /** Only if the row still has the version [group] was read with; only the status and when it was let go change. */
    override fun save(group: ReservationGroup) {
        val updated = ReservationGroupTbl.update({ (ReservationGroupTbl.groupId eq group.id.value) and (ReservationGroupTbl.version eq group.version) }) {
            it[status] = group.status
            it[releasedOn] = group.releasedOn?.utc()
            it[version] = group.version + 1
        }
        if (updated != 1) throw ConcurrentChangeException(AGGREGATE_NAME, EventAggregateId(group.id.value.toString()))
    }

    override fun findDue(now: Instant, limit: Int): List<ReservationGroupId> =
        ReservationGroupTbl.select(ReservationGroupTbl.groupId)
            .where { (ReservationGroupTbl.status eq ReservationGroupStatus.HOLDING) and (ReservationGroupTbl.expiresOn lessEq now.utc()) }
            .orderBy(ReservationGroupTbl.expiresOn)
            .limit(limit)
            .map { ReservationGroupId.constructFromPersisted(it[ReservationGroupTbl.groupId]) }

    private fun ResultRow.toGroup(): ReservationGroup = ReservationGroup.constructFromPersisted(
        id = ReservationGroupId.constructFromPersisted(this[ReservationGroupTbl.groupId]),
        code = ReservationGroupCode.constructFromPersisted(this[ReservationGroupTbl.code]),
        userId = UserId.constructFromPersisted(this[ReservationGroupTbl.userId]),
        rootOrderId = OrderId.constructFromPersisted(this[ReservationGroupTbl.rootOrderId]),
        addressId = this[ReservationGroupTbl.addressId]?.let { AddressId.constructFromPersisted(it) },
        addressFingerprint = AddressFingerprint.constructFromPersisted(this[ReservationGroupTbl.addressFingerprint]),
        shippingMethod = this[ReservationGroupTbl.shippingMethod],
        startedOn = this[ReservationGroupTbl.startedOn].toInstant(),
        expiresOn = this[ReservationGroupTbl.expiresOn].toInstant(),
        status = this[ReservationGroupTbl.status],
        releasedOn = this[ReservationGroupTbl.releasedOn]?.toInstant(),
        version = this[ReservationGroupTbl.version],
    )

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)

    private companion object {
        val AGGREGATE_NAME = EventAggregateName("RESERVATION_GROUP")
    }
}

/** Takes the next number from the sequence of V19__reservation_groups.sql. Runs inside the use case's transaction. */
class ReservationGroupIdGeneratorImpl : ReservationGroupIdGenerator {
    override fun nextReservationGroupId(): ReservationGroupId =
        TransactionManager.current().exec("SELECT nextval('reservationgroupid_seq')") { rows ->
            rows.next()
            ReservationGroupId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval('reservationgroupid_seq') returned nothing")
}
