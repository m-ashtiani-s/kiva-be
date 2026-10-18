package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.discount.entity.DiscountCode
import com.kiva.domain.aggregate.discount.entity.DiscountRedemption
import com.kiva.domain.aggregate.discount.model.DiscountUsage
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountRedemptionStatus
import com.kiva.domain.aggregate.discount.repository.DiscountCodeRepository
import com.kiva.domain.aggregate.discount.repository.DiscountRedemptionRepository
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.infrastructure.persistence.exposed.tbl.DiscountCodeTbl
import com.kiva.infrastructure.persistence.exposed.tbl.DiscountRedemptionTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
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
    )
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
