package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.discount.entity.DiscountCode
import com.kiva.domain.aggregate.discount.entity.DiscountRedemption
import com.kiva.domain.aggregate.discount.model.DiscountUsage
import com.kiva.domain.aggregate.discount.model.DiscountUsageStats
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountRedemptionStatus
import com.kiva.domain.aggregate.discount.repository.DiscountCodeRepository
import com.kiva.domain.aggregate.discount.repository.DiscountRedemptionRepository
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.infrastructure.common.persistence.SortOrders
import com.kiva.infrastructure.common.persistence.paginate
import com.kiva.infrastructure.common.persistence.refusedConstraint
import com.kiva.infrastructure.persistence.exposed.tbl.DiscountCodeTbl
import com.kiva.infrastructure.persistence.exposed.tbl.DiscountRedemptionTbl
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class DiscountCodeRepositoryImpl : DiscountCodeRepository {

    override fun findByCode(code: DiscountCodeText): DiscountCode? =
        DiscountCodeTbl.selectAll().where { DiscountCodeTbl.code eq code.value }.singleOrNull()?.toCode()

    /** SELECT ... FOR UPDATE: a row lock until the commit or the rollback. */
    override fun lockByCode(code: DiscountCodeText): DiscountCode? =
        DiscountCodeTbl.selectAll().where { DiscountCodeTbl.code eq code.value }.forUpdate().singleOrNull()?.toCode()

    override fun findById(id: DiscountCodeId): DiscountCode? =
        DiscountCodeTbl.selectAll().where { DiscountCodeTbl.id eq id.value }.singleOrNull()?.toCode()

    override fun lockById(id: DiscountCodeId): DiscountCode? =
        DiscountCodeTbl.selectAll().where { DiscountCodeTbl.id eq id.value }.forUpdate().singleOrNull()?.toCode()

    override fun nextId(): DiscountCodeId =
        TransactionManager.current().exec("SELECT nextval(${DiscountCodeTbl.ID_SEQUENCE_OF})") { rows ->
            rows.next()
            DiscountCodeId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval of discountcode_tbl's id sequence returned nothing")

    override fun list(active: Boolean?, pageable: Pageable): Page<DiscountCode> =
        DiscountCodeTbl.selectAll()
            .where { active?.let { DiscountCodeTbl.isActive eq it } ?: Op.TRUE }
            .paginate(pageable, SORT_ORDERS, tiebreaker = DiscountCodeTbl.id, tiebreakerOrder = SortOrder.DESC) { it.toCode() }

    override fun add(code: DiscountCode) {
        writing(code) {
            DiscountCodeTbl.insert {
                it[id] = code.id.value
                it.fill(code)
                it[createdOn] = code.createdOn.utc()
                it[version] = 0
            }
        }
    }

    override fun save(code: DiscountCode) {
        val updated = writing(code) {
            DiscountCodeTbl.update({ (DiscountCodeTbl.id eq code.id.value) and (DiscountCodeTbl.version eq code.version) }) {
                it.fill(code)
                it[version] = code.version + 1
            }
        }
        if (updated != 1) throw ConcurrentChangeException(AGGREGATE_NAME, EventAggregateId(code.id.value.toString()))
    }

    /** Counted by the database: the code's confirmed uses and what they took off, for the whole page in one query. */
    override fun usageStatsOf(ids: Collection<DiscountCodeId>): Map<DiscountCodeId, DiscountUsageStats> {
        if (ids.isEmpty()) return emptyMap()
        val count = DiscountRedemptionTbl.id.count()
        val total = DiscountRedemptionTbl.amount.sum()
        return DiscountRedemptionTbl.select(DiscountRedemptionTbl.codeId, count, total)
            .where { (DiscountRedemptionTbl.codeId inList ids.map { it.value }) and (DiscountRedemptionTbl.status eq DiscountRedemptionStatus.CONFIRMED) }
            .groupBy(DiscountRedemptionTbl.codeId)
            .associate { row ->
                DiscountCodeId.constructFromPersisted(row[DiscountRedemptionTbl.codeId]) to
                    DiscountUsageStats(Math.toIntExact(row[count]), Money.constructFromPersisted(row[total] ?: 0))
            }
    }

    /** The unique key on the text is the last word on it; any other refusal is a bug and goes up as it is. */
    private fun <T> writing(code: DiscountCode, write: () -> T): T {
        try {
            return write()
        } catch (e: ExposedSQLException) {
            if (e.refusedConstraint() == DiscountCodeTbl.CODE_KEY) throw UniqueTakenException("discount code ${code.code.value}", e)
            throw e
        }
    }

    private fun UpdateBuilder<*>.fill(code: DiscountCode) {
        this[DiscountCodeTbl.code] = code.code.value
        this[DiscountCodeTbl.type] = code.type
        this[DiscountCodeTbl.value] = code.value
        this[DiscountCodeTbl.label] = code.label
        this[DiscountCodeTbl.minSubtotal] = code.minSubtotal?.toman
        this[DiscountCodeTbl.maxDiscount] = code.maxDiscount?.toman
        this[DiscountCodeTbl.startsAt] = code.startsAt?.utc()
        this[DiscountCodeTbl.endsAt] = code.endsAt?.utc()
        this[DiscountCodeTbl.usageLimit] = code.usageLimit
        this[DiscountCodeTbl.perUserLimit] = code.perUserLimit
        this[DiscountCodeTbl.firstOrderOnly] = code.firstOrderOnly
        this[DiscountCodeTbl.requiresLogin] = code.requiresLogin
        this[DiscountCodeTbl.excludeSaleItems] = code.excludeSaleItems
        this[DiscountCodeTbl.categoryIds] = code.categoryIds.map { it.value }.sorted()
        this[DiscountCodeTbl.productIds] = code.productIds.map { it.value }.sorted()
        this[DiscountCodeTbl.isActive] = code.isActive
        this[DiscountCodeTbl.updatedOn] = code.updatedOn.utc()
    }

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)

    private fun ResultRow.toCode(): DiscountCode = DiscountCode.constructFromPersisted(
        id = DiscountCodeId.constructFromPersisted(this[DiscountCodeTbl.id].value),
        code = DiscountCodeText.constructFromPersisted(this[DiscountCodeTbl.code]),
        type = this[DiscountCodeTbl.type],
        value = this[DiscountCodeTbl.value],
        label = this[DiscountCodeTbl.label],
        minSubtotal = this[DiscountCodeTbl.minSubtotal]?.let(Money::constructFromPersisted),
        maxDiscount = this[DiscountCodeTbl.maxDiscount]?.let(Money::constructFromPersisted),
        startsAt = this[DiscountCodeTbl.startsAt]?.toInstant(),
        endsAt = this[DiscountCodeTbl.endsAt]?.toInstant(),
        usageLimit = this[DiscountCodeTbl.usageLimit],
        perUserLimit = this[DiscountCodeTbl.perUserLimit],
        firstOrderOnly = this[DiscountCodeTbl.firstOrderOnly],
        requiresLogin = this[DiscountCodeTbl.requiresLogin],
        excludeSaleItems = this[DiscountCodeTbl.excludeSaleItems],
        categoryIds = this[DiscountCodeTbl.categoryIds].map(CategoryId::constructFromPersisted).toSet(),
        productIds = this[DiscountCodeTbl.productIds].map(ProductId::constructFromPersisted).toSet(),
        isActive = this[DiscountCodeTbl.isActive],
        createdOn = this[DiscountCodeTbl.createdOn].toInstant(),
        updatedOn = this[DiscountCodeTbl.updatedOn].toInstant(),
        version = this[DiscountCodeTbl.version],
    )

    private companion object {
        val AGGREGATE_NAME = EventAggregateName("DISCOUNT_CODE")

        /** Newest made first; the id breaks ties between codes made in the same instant. */
        val SORT_ORDERS: SortOrders = mapOf(DiscountCodeRepository.NEWEST to listOf(DiscountCodeTbl.createdOn to SortOrder.DESC))
    }
}

/** Runs inside the use case's transaction; it never opens one itself. */
class DiscountRedemptionRepositoryImpl : DiscountRedemptionRepository {

    /** Counted by the database: the code's held and confirmed rows, then those of [userId]. */
    override fun usageOf(codeId: DiscountCodeId, userId: UserId?): DiscountUsage {
        val counted = (DiscountRedemptionTbl.codeId eq codeId.value) and (DiscountRedemptionTbl.status inList COUNTED)
        val ofCode = DiscountRedemptionTbl.selectAll().where { counted }.count()
        val ofCustomer = userId?.let { id -> DiscountRedemptionTbl.selectAll().where { counted and (DiscountRedemptionTbl.userId eq id.value) }.count() } ?: 0
        return DiscountUsage(ofCode = Math.toIntExact(ofCode), ofCustomer = Math.toIntExact(ofCustomer))
    }

    override fun findOfOrder(orderId: OrderId): DiscountRedemption? =
        DiscountRedemptionTbl.selectAll().where { DiscountRedemptionTbl.orderId eq orderId.value }.singleOrNull()?.toRedemption()

    override fun lockOfOrder(orderId: OrderId): DiscountRedemption? =
        DiscountRedemptionTbl.selectAll().where { DiscountRedemptionTbl.orderId eq orderId.value }.forUpdate().singleOrNull()?.toRedemption()

    override fun add(redemption: DiscountRedemption) {
        DiscountRedemptionTbl.insert {
            it[codeId] = redemption.codeId.value
            it[userId] = redemption.userId.value
            it[orderId] = redemption.orderId.value
            it[amount] = redemption.amount.toman
            it[status] = redemption.status
            it[heldOn] = redemption.heldOn.utc()
            it[settledOn] = redemption.settledOn?.utc()
        }
    }

    /**
     * Only a row still in the status it was read in changes (DiscountRedemption.storedStatus): confirming or releasing it
     * twice, or a payment and a cancellation at once, never write twice.
     */
    override fun save(redemption: DiscountRedemption) {
        val updated = DiscountRedemptionTbl.update({
            (DiscountRedemptionTbl.orderId eq redemption.orderId.value) and (DiscountRedemptionTbl.status eq redemption.storedStatus)
        }) {
            it[status] = redemption.status
            it[settledOn] = redemption.settledOn?.utc()
        }
        if (updated != 1) throw ConcurrentChangeException(AGGREGATE_NAME, EventAggregateId(redemption.orderId.value.toString()))
    }

    private fun ResultRow.toRedemption(): DiscountRedemption = DiscountRedemption.constructFromPersisted(
        codeId = DiscountCodeId.constructFromPersisted(this[DiscountRedemptionTbl.codeId]),
        userId = UserId.constructFromPersisted(this[DiscountRedemptionTbl.userId]),
        orderId = OrderId.constructFromPersisted(this[DiscountRedemptionTbl.orderId]),
        amount = Money.constructFromPersisted(this[DiscountRedemptionTbl.amount]),
        status = this[DiscountRedemptionTbl.status],
        heldOn = this[DiscountRedemptionTbl.heldOn].toInstant(),
        settledOn = this[DiscountRedemptionTbl.settledOn]?.toInstant(),
    )

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)

    private companion object {
        val COUNTED = listOf(DiscountRedemptionStatus.HELD, DiscountRedemptionStatus.CONFIRMED)
        val AGGREGATE_NAME = EventAggregateName("DISCOUNT_REDEMPTION")
    }
}
