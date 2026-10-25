package com.kiva.application.usecase.discount.usecase.command

import com.kiva.application.port.AuditChange
import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeId
import com.kiva.domain.aggregate.discount.repository.DiscountCodeRepository
import java.time.Clock

/**
 * The panel switches a discount code off (`DELETE /admin/discount-codes/{discountId}`; ADMIN): never removed, as orders
 * point at it (decision 12). Under the code's row lock; one already off is 204 and changes nothing (no log row). A
 * malformed id is the same 404 as one nobody has. A cart holding it loses it at its next sync (DISCOUNT_REMOVED).
 */
class DeactivateDiscountCodeUc(
    private val transactionManager: TransactionManager,
    private val discountCodeRepository: DiscountCodeRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<DeactivateDiscountCodeUc.Cmd, Unit, DeactivateDiscountCodeUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val discountId: String, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("DeactivateDiscountCodeUc.execute discountId=${command.discountId} by=${command.actor.userId.value}")
        val id = command.discountId.toLongOrNull()?.let { DiscountCodeId.makeNew(it).getOrNull() } ?: return Result.failure(Failure.NotFound)

        return transactionManager.inTransaction {
            val code = discountCodeRepository.lockById(id) ?: return@inTransaction Result.failure(Failure.NotFound)
            val now = clock.instant()
            val off = code.deactivate(now) ?: return@inTransaction Result.success(Unit)
            discountCodeRepository.save(off)
            auditTrail.record(
                AuditEntry(
                    now, command.actor, AuditAction.DISCOUNT_CODE_DEACTIVATED, AuditEntityType.DISCOUNT_CODE, id.value.toString(), code.code.value,
                    listOf(AuditChange("isActive", "true", "false")),
                ),
            )
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
