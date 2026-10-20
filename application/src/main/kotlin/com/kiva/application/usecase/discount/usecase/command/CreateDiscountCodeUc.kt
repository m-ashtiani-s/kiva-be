package com.kiva.application.usecase.discount.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.discount.DiscountCodeFields
import com.kiva.application.usecase.discount.DiscountCodeReferences
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.discount.entity.DiscountCode
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.discount.repository.DiscountCodeRepository
import java.time.Clock

/**
 * The panel makes a discount code (`POST /admin/discount-codes`; ADMIN). The form first, every problem at once (400);
 * then, in the transaction, the categories and products it names (400 on their index), and its text nobody else's
 * (409 DUPLICATE_CODE, also when another request takes it a moment before the insert). One DISCOUNT_CODE_CREATED row.
 * The shop can use it at once (`POST /cart/discount-code`).
 */
class CreateDiscountCodeUc(
    private val transactionManager: TransactionManager,
    private val discountCodeRepository: DiscountCodeRepository,
    private val references: DiscountCodeReferences,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<CreateDiscountCodeUc.Cmd, DiscountCodeId, CreateDiscountCodeUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val fields: DiscountCodeFields, val actor: PanelActor) {
        override fun toString(): String = "Cmd(code=${fields.code}, actor=${actor.userId.value})"
    }

    override suspend fun execute(command: Cmd): Result<DiscountCodeId, Failure> {
        logger.info("CreateDiscountCodeUc.execute code=${command.fields.code} by=${command.actor.userId.value}")
        val terms = command.fields.check().unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }

        return transactionManager.inTransaction {
            val missing = references.problemsOf(terms)
            if (missing.isNotEmpty()) return@inTransaction Result.failure(Failure.InvalidInput(missing))
            if (discountCodeRepository.findByCode(terms.code) != null) return@inTransaction Result.failure(Failure.DuplicateCode(terms.code))

            val now = clock.instant()
            val code = DiscountCode.create(discountCodeRepository.nextId(), terms, now)
            try {
                discountCodeRepository.add(code)
            } catch (e: UniqueTakenException) {
                logger.info("CreateDiscountCodeUc: ${e.message} between the check and the insert")
                return@inTransaction Result.failure(Failure.DuplicateCode(terms.code))
            }
            auditTrail.record(
                AuditEntry(now, command.actor, AuditAction.DISCOUNT_CODE_CREATED, AuditEntityType.DISCOUNT_CODE, code.id.value.toString(), code.code.value, null),
            )
            logger.info("CreateDiscountCodeUc: code ${code.id.value} made")
            Result.success(code.id)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, causes, meta) {

        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        data class DuplicateCode(val text: DiscountCodeText) : Failure("DUPLICATE_CODE", FailureKind.CONFLICT, "code", meta = mapOf("code" to text.value))
    }
}
