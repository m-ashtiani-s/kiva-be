package com.kiva.application.usecase.inventory.usecase.query

import com.kiva.application.dto.ActorNameDto
import com.kiva.application.dto.InventoryAdjustmentDto
import com.kiva.application.repository.InventoryAdjustmentQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.user.repository.UserRepository

/**
 * The stock's history (`GET /admin/inventory/adjustments`; ADMIN, CATALOG_MANAGER): hand changes only (deliveries,
 * counts, damage, returns, corrections, and a new color's starting stock), newest first, every color or one. Orders'
 * reservations, releases and shipments are not in it.
 *
 * Who made each change is read by name once per person on the page (a page of 20 has a handful of people).
 */
class ListInventoryAdjustmentsUc(
    private val transactionManager: TransactionManager,
    private val inventoryAdjustmentQmRepository: InventoryAdjustmentQmRepository,
    private val userRepository: UserRepository,
) : QueryUseCase<ListInventoryAdjustmentsUc.Qry, Page<InventoryAdjustmentDto>, ListInventoryAdjustmentsUc.Failure> {

    private val logger = getLogger()

    /** @property variantId the `variantId` parameter as it came, or null for every color. */
    data class Qry(val variantId: String?, val pageable: Pageable)

    override suspend fun execute(query: Qry): Result<Page<InventoryAdjustmentDto>, Failure> {
        logger.info("ListInventoryAdjustmentsUc.execute variantId=${query.variantId} page=${query.pageable.page}")
        val variantId = query.variantId?.let { raw ->
            (raw.toLongOrNull()?.let { VariantId.makeNew(it) } ?: VariantId.makeNew(0)).forField("variantId")
                .unwrapOr { return Result.failure(Failure.InvalidInput(listOf(it))) }
        }

        return transactionManager.inTransaction {
            val page = inventoryAdjustmentQmRepository.find(variantId, query.pageable)
            val people = page.items.map { it.occurredBy }.distinct().filter { it != UserId.SYSTEM }.associateWith(userRepository::findById)
            Result.success(
                page.map {
                    InventoryAdjustmentDto(
                        id = it.id,
                        variantId = it.variantId.value,
                        sku = it.sku.value,
                        delta = it.delta,
                        reason = it.reason,
                        note = it.note,
                        stockAfter = it.stockAfter,
                        createdAt = it.occurredOn,
                        createdBy = ActorNameDto.of(it.occurredBy, people[it.occurredBy]),
                    )
                },
            )
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}
