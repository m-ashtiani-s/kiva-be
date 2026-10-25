package com.kiva.application.usecase.inventory.usecase.command

import com.kiva.application.dto.ActorNameDto
import com.kiva.application.dto.InventoryAdjustmentDto
import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.repository.InventoryAdjustmentQmRepository
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.bind
import com.kiva.common.railway.forField
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.railway.mapOnSuccess
import com.kiva.common.railway.required
import com.kiva.common.railway.unwrapOr
import com.kiva.common.railway.zip
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentNote
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock

/**
 * A change staff make by hand to the stock of one color: a delivery, a count, damage, a return or a correction
 * (`POST /admin/inventory/adjustments`; ADMIN, CATALOG_MANAGER).
 *
 * Two adjustments of one color at the same moment queue: the color's stock is held (InventoryRepository.holdForChange)
 * before it is read. When someone else still changes it in between (an order, which doesn't hold it first), the save is
 * refused with [ConcurrentChangeException] and the whole transaction runs again from a fresh read, up to
 * [MAX_ATTEMPTS] times in all. The rules are checked
 * again against that fresh stock, so a retry never takes away bags someone else has just reserved.
 *
 * The color must be one some product has: the read model tells that cheaply, where the stock's own stream can't (a
 * color nothing was recorded for has no stream either). A color taken out of the shop still counts: staff still
 * count and move its bags.
 *
 * The answer is the change as the history lists it: the row the projector wrote for this very event (its `id` and the
 * stock on hand after it), and who made it by name. One INVENTORY_ADJUSTED row goes in the change log, in the same
 * transaction, with the stock on hand before and after and the reason.
 */
class AdjustInventoryUc(
    private val transactionManager: TransactionManager,
    private val inventoryDomainService: InventoryDomainService,
    private val inventoryRepository: InventoryRepository,
    private val productQmRepository: ProductQmRepository,
    private val inventoryAdjustmentQmRepository: InventoryAdjustmentQmRepository,
    private val userRepository: UserRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<AdjustInventoryUc.Cmd, InventoryAdjustmentDto, AdjustInventoryUc.Failure> {

    private val logger = getLogger()

    /** The contract's InventoryAdjustmentRequest as it arrived (every field nullable, as a body has it), plus who makes the change. */
    data class Cmd(
        val variantId: Long?,
        val delta: Int?,
        val reason: String?,
        val note: String?,
        val actor: PanelActor,
    )

    private data class Input(
        val variantId: VariantId,
        val delta: StockDelta,
        val reason: AdjustmentReason,
        val note: AdjustmentNote?,
    )

    override suspend fun execute(command: Cmd): Result<InventoryAdjustmentDto, Failure> {
        logger.info("AdjustInventoryUc.execute variantId=${command.variantId} delta=${command.delta} reason=${command.reason}")

        // Every input at once, so the form shows all mistakes together; before the transaction, so bad input never
        // takes a database connection.
        val input = Result.zip(
            command.variantId.required().bind { VariantId.makeNew(it) }.forField("variantId"),
            command.delta.required().forField("delta").bind { StockDelta.makeNew(it) },
            command.reason.required().forField("reason").bind { AdjustmentReason.makeNew(it) },
            AdjustmentNote.makeNew(command.note),
            Failure::InvalidInput,
        ) { variantId, delta, reason, note -> Input(variantId, delta, reason, note) }
            .unwrapOr { return Result.failure(it) }

        repeat(MAX_ATTEMPTS - 1) { attempt ->
            try {
                return adjustOnce(input, command.actor)
            } catch (e: ConcurrentChangeException) {
                logger.info("AdjustInventoryUc: variant ${command.variantId} changed meanwhile, attempt ${attempt + 1} of $MAX_ATTEMPTS refused; trying again")
            }
        }
        // The last attempt: if even this one is refused, the exception goes up to the caller.
        return adjustOnce(input, command.actor)
    }

    /** One whole attempt in one transaction: find the color, read its stock, let the domain decide, save. */
    private suspend fun adjustOnce(input: Input, actor: PanelActor): Result<InventoryAdjustmentDto, Failure> =
        transactionManager.inTransaction {
            val variant = productQmRepository.findVariant(input.variantId)
                ?: return@inTransaction Result.failure(Failure.VariantNotFound(input.variantId))
            // Staff may adjust one color many times at once: they queue here rather than collide at the save.
            inventoryRepository.holdForChange(input.variantId)
            val now = clock.instant()
            inventoryDomainService.adjustStock(input.variantId, input.delta, input.reason, input.note, actor.userId, now)
                .mapOnFailure {
                    when (it) {
                        is InventoryDomainService.AdjustStockFailure.WrongDirection -> Failure.WrongDirection(it.reason)
                        is InventoryDomainService.AdjustStockFailure.MoreThanAvailable -> Failure.MoreThanAvailable(it.availableQuantity)
                    }
                }
                .mapOnSuccess { inventory ->
                    inventoryRepository.save(inventory)
                    val row = inventoryAdjustmentQmRepository.findByEventId(inventory.getRecordedEvents().last().id)
                        ?: error("The projector writes the history row of an adjustment in its own transaction")
                    auditTrail.record(
                        AuditEntry(
                            now, actor, AuditAction.INVENTORY_ADJUSTED, AuditEntityType.INVENTORY, input.variantId.value.toString(), variant.sku.value,
                            AuditDiff.build {
                                field("stockOnHand", inventory.onHand - input.delta.value, inventory.onHand)
                                field("reason", null, input.reason)
                            },
                        ),
                    )
                    InventoryAdjustmentDto(
                        id = row.id,
                        variantId = row.variantId.value,
                        sku = row.sku.value,
                        delta = row.delta,
                        reason = row.reason,
                        note = row.note,
                        stockAfter = row.stockAfter,
                        createdAt = row.occurredOn,
                        createdBy = ActorNameDto.of(actor.userId, userRepository.findById(actor.userId)),
                    )
                }
        }

    companion object {
        const val MAX_ATTEMPTS = 3
    }

    sealed class Failure(
        code: String,
        kind: FailureKind,
        field: String? = null,
        causes: List<ResultFailure> = emptyList(),
        meta: Map<String, Any> = emptyMap(),
    ) : ResultFailure(code, kind, field, causes, meta) {
        /** One or more fields are wrong; each cause names its field (400 VALIDATION_ERROR). */
        data class InvalidInput(val problems: List<ResultFailure>) :
            Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        /** No product has this color: a wrong reference in the request, so a problem of its field (400). */
        data class VariantNotFound(val variantId: VariantId) :
            Failure("VARIANT_NOT_FOUND", FailureKind.INVALID, "variantId", meta = mapOf("variantId" to variantId.value))

        data class WrongDirection(val reason: AdjustmentReason) :
            Failure("ADJUSTMENT_DIRECTION_INVALID", FailureKind.INVALID, "delta", meta = mapOf("reason" to reason.name))

        data class MoreThanAvailable(val availableQuantity: Int) :
            Failure("QUANTITY_EXCEEDS_STOCK", FailureKind.BUSINESS_RULE, "delta", meta = mapOf("availableQuantity" to availableQuantity))
    }
}
