package com.kiva.application.usecase.inventory.usecase.command

import com.kiva.application.dto.InventoryAdjustmentDto
import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.railway.mapOnSuccess
import com.kiva.common.railway.unwrapOr
import com.kiva.common.railway.zip
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentNote
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Clock

/**
 * A change staff make by hand to the stock of one color: a delivery, a count, damage, a return or a correction
 * (`POST /admin/inventory/adjustments`, and the dev seeder until the admin panel exists).
 *
 * When someone else changes the same stock at the same moment, the save is refused with [ConcurrentChangeException].
 * The whole transaction then runs again from a fresh read, up to [MAX_ATTEMPTS] times in all. The rules are checked
 * again against that fresh stock, so a retry never takes away bags someone else has just reserved.
 *
 * Not checked yet: that the color exists. Only the read model can tell that cheaply, so the check comes with it
 * in step 5. Until then nothing but tests calls this.
 */
class AdjustInventoryUc(
    private val transactionManager: TransactionManager,
    private val inventoryDomainService: InventoryDomainService,
    private val inventoryRepository: InventoryRepository,
    private val clock: Clock,
) : CommandUseCase<AdjustInventoryUc.Cmd, InventoryAdjustmentDto, AdjustInventoryUc.Failure> {

    private val logger = getLogger()

    /**
     * The contract's InventoryAdjustmentRequest as it arrived, plus who makes the change.
     *
     * @property by from the access token, or [UserId.SYSTEM] for the seeder: never typed by anyone, so not raw input.
     */
    data class Cmd(
        val variantId: Long,
        val delta: Int,
        val reason: String,
        val note: String?,
        val by: UserId,
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
            VariantId.makeNew(command.variantId).forField("variantId"),
            StockDelta.makeNew(command.delta),
            AdjustmentReason.makeNew(command.reason),
            AdjustmentNote.makeNew(command.note),
            Failure::InvalidInput,
        ) { variantId, delta, reason, note -> Input(variantId, delta, reason, note) }
            .unwrapOr { return Result.failure(it) }

        repeat(MAX_ATTEMPTS - 1) { attempt ->
            try {
                return adjustOnce(input, command.by)
            } catch (e: ConcurrentChangeException) {
                logger.info("AdjustInventoryUc: variant ${command.variantId} changed meanwhile, attempt ${attempt + 1} of $MAX_ATTEMPTS refused; trying again")
            }
        }
        // The last attempt: if even this one is refused, the exception goes up to the caller.
        return adjustOnce(input, command.by)
    }

    /** One whole attempt in one transaction: read the stock, let the domain decide, save. */
    private suspend fun adjustOnce(input: Input, by: UserId): Result<InventoryAdjustmentDto, Failure> =
        transactionManager.inTransaction {
            val now = clock.instant()
            inventoryDomainService.adjustStock(input.variantId, input.delta, input.reason, input.note, by, now)
                .mapOnFailure {
                    when (it) {
                        is InventoryDomainService.AdjustStockFailure.WrongDirection -> Failure.WrongDirection(it.reason)
                        is InventoryDomainService.AdjustStockFailure.MoreThanAvailable -> Failure.MoreThanAvailable(it.availableQuantity)
                    }
                }
                .mapOnSuccess { inventory ->
                    inventoryRepository.save(inventory)
                    InventoryAdjustmentDto(
                        variantId = input.variantId.value,
                        delta = input.delta.value,
                        reason = input.reason,
                        note = input.note?.value,
                        onHandAfter = inventory.onHand,
                        availableAfter = inventory.available,
                        adjustedAt = now,
                        adjustedBy = by.value,
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

        data class WrongDirection(val reason: AdjustmentReason) :
            Failure("ADJUSTMENT_DIRECTION_INVALID", FailureKind.INVALID, "delta", meta = mapOf("reason" to reason.name))

        data class MoreThanAvailable(val availableQuantity: Int) :
            Failure("QUANTITY_EXCEEDS_STOCK", FailureKind.BUSINESS_RULE, "delta", meta = mapOf("availableQuantity" to availableQuantity))
    }
}
