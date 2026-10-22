package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.stockalert.entity.StockAlert
import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertId
import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertStatus
import com.kiva.domain.aggregate.stockalert.repository.StockAlertIdGenerator
import com.kiva.domain.aggregate.stockalert.repository.StockAlertRepository
import com.kiva.infrastructure.common.persistence.refusedConstraint
import com.kiva.infrastructure.persistence.exposed.tbl.StockAlertTbl
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class StockAlertRepositoryImpl : StockAlertRepository {

    override fun add(alert: StockAlert) {
        try {
            StockAlertTbl.insert {
                it[alertId] = alert.id.value
                it[userId] = alert.userId.value
                it[productId] = alert.productId.value
                it[variantId] = alert.variantId?.value
                it[status] = alert.status
                it[createdOn] = alert.createdOn.utc()
            }
        } catch (e: ExposedSQLException) {
            if (e.refusedConstraint() == StockAlertTbl.ONE_ACTIVE_KEY) {
                throw UniqueTakenException("an active stock alert of product ${alert.productId.value} by user ${alert.userId.value}", e)
            }
            throw e
        }
    }

    override fun find(id: StockAlertId): StockAlert? = StockAlertTbl.selectAll().where { StockAlertTbl.alertId eq id.value }.singleOrNull()?.toAlert()

    override fun lock(id: StockAlertId): StockAlert? =
        StockAlertTbl.selectAll().where { StockAlertTbl.alertId eq id.value }.forUpdate().singleOrNull()?.toAlert()

    override fun activeExists(userId: UserId, productId: ProductId, variantId: VariantId?): Boolean =
        StockAlertTbl.select(StockAlertTbl.alertId).where {
            (StockAlertTbl.userId eq userId.value) and (StockAlertTbl.productId eq productId.value) and (StockAlertTbl.status eq StockAlertStatus.ACTIVE) and
                (if (variantId == null) StockAlertTbl.variantId.isNull() else StockAlertTbl.variantId eq variantId.value)
        }.limit(1).any()

    override fun countActive(userId: UserId): Int =
        StockAlertTbl.selectAll().where { (StockAlertTbl.userId eq userId.value) and (StockAlertTbl.status eq StockAlertStatus.ACTIVE) }.count().toInt()

    override fun createdSince(userId: UserId, since: Instant): List<Instant> =
        StockAlertTbl.select(StockAlertTbl.createdOn).where { (StockAlertTbl.userId eq userId.value) and (StockAlertTbl.createdOn greater since.utc()) }
            .map { it[StockAlertTbl.createdOn].toInstant() }

    override fun listMine(userId: UserId, notifiedSince: Instant, limit: Int): List<StockAlert> =
        StockAlertTbl.selectAll().where {
            (StockAlertTbl.userId eq userId.value) and (
                (StockAlertTbl.status eq StockAlertStatus.ACTIVE) or
                    ((StockAlertTbl.status eq StockAlertStatus.NOTIFIED) and (StockAlertTbl.notifiedOn greater notifiedSince.utc()))
                )
        }.orderBy(StockAlertTbl.createdOn to SortOrder.DESC, StockAlertTbl.alertId to SortOrder.DESC).limit(limit).map { it.toAlert() }

    override fun activeOf(userId: UserId, productId: ProductId): List<StockAlert> =
        StockAlertTbl.selectAll().where {
            (StockAlertTbl.userId eq userId.value) and (StockAlertTbl.productId eq productId.value) and (StockAlertTbl.status eq StockAlertStatus.ACTIVE)
        }.map { it.toAlert() }

    /** One statement on the read model (roadmap section 15.3): the oldest first, so the first to wait is the first told. */
    override fun findDue(limit: Int): List<StockAlertId> {
        val ids = mutableListOf<StockAlertId>()
        TransactionManager.current().exec(
            """
            SELECT a."alertId"
            FROM stockalert_tbl a
            JOIN productqm_tbl p ON p."productId" = a."productId" AND p.status = 'ACTIVE'
            WHERE a.status = 'ACTIVE'
              AND EXISTS (
                  SELECT 1 FROM productvariantqm_tbl v
                  WHERE v."productId" = a."productId"
                    AND v."isActive"
                    AND v."onHand" - v.reserved > 0
                    AND (a."variantId" IS NULL OR v."variantId" = a."variantId"))
            ORDER BY a."createdOn", a."alertId"
            LIMIT ?
            """.trimIndent(),
            listOf(LongColumnType() to limit.toLong()),
        ) { rs -> while (rs.next()) ids += StockAlertId.constructFromPersisted(rs.getLong(1)) }
        return ids
    }

    override fun subscribersOf(variantIds: Collection<VariantId>): Map<VariantId, Int> {
        if (variantIds.isEmpty()) return emptyMap()
        val count = StockAlertTbl.alertId.count()
        return StockAlertTbl.select(StockAlertTbl.variantId, count)
            .where { (StockAlertTbl.variantId inList variantIds.map { it.value }) and (StockAlertTbl.status eq StockAlertStatus.ACTIVE) }
            .groupBy(StockAlertTbl.variantId)
            .associate { VariantId.constructFromPersisted(it[StockAlertTbl.variantId]!!) to it[count].toInt() }
    }

    override fun deleteEndedBefore(before: Instant): Int = StockAlertTbl.deleteWhere {
        val at = before.utc()
        ((StockAlertTbl.status eq StockAlertStatus.NOTIFIED) and (StockAlertTbl.notifiedOn less at)) or
            ((StockAlertTbl.status eq StockAlertStatus.CANCELLED) and (StockAlertTbl.cancelledOn less at))
    }

    override fun save(alert: StockAlert) {
        StockAlertTbl.update({ StockAlertTbl.alertId eq alert.id.value }) {
            it[status] = alert.status
            it[notifiedOn] = alert.notifiedOn?.utc()
            it[notifiedVariantId] = alert.notifiedVariantId?.value
            it[cancelledOn] = alert.cancelledOn?.utc()
        }
    }

    private fun ResultRow.toAlert(): StockAlert = StockAlert.constructFromPersisted(
        id = StockAlertId.constructFromPersisted(this[StockAlertTbl.alertId]),
        userId = UserId.constructFromPersisted(this[StockAlertTbl.userId]),
        productId = ProductId.constructFromPersisted(this[StockAlertTbl.productId]),
        variantId = this[StockAlertTbl.variantId]?.let { VariantId.constructFromPersisted(it) },
        status = this[StockAlertTbl.status],
        createdOn = this[StockAlertTbl.createdOn].toInstant(),
        notifiedOn = this[StockAlertTbl.notifiedOn]?.toInstant(),
        notifiedVariantId = this[StockAlertTbl.notifiedVariantId]?.let { VariantId.constructFromPersisted(it) },
        cancelledOn = this[StockAlertTbl.cancelledOn]?.toInstant(),
    )

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)
}

/** The ids of new subscriptions, from `stockalertid_seq`. */
class StockAlertIdGeneratorImpl : StockAlertIdGenerator {
    override fun nextStockAlertId(): StockAlertId =
        TransactionManager.current().exec("SELECT nextval('${StockAlertTbl.ID_SEQUENCE}')") { rows ->
            rows.next()
            StockAlertId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval('${StockAlertTbl.ID_SEQUENCE}') returned nothing")
}
