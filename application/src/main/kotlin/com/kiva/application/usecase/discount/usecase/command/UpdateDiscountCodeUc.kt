package com.kiva.application.usecase.discount.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.discount.DiscountCodeAudit
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
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.discount.repository.DiscountCodeRepository
import java.time.Clock

/**
 * The panel edits a discount code (`PUT /admin/discount-codes/{discountId}`; ADMIN): the whole form replaces the old
 * terms (roadmap phase 4, section 9.6). The form first (400); a malformed id is the same 404 as one nobody has; then
 * under the code's row lock: the categories and products it names (400), its text nobody else's (409 DUPLICATE_CODE).
 * One of the older 2-letter texts is kept only by the code that has it. A code already used may change (decision 11).
 * Two saves at the same moment: the second is 409 CHANGED_BY_SOMEONE_ELSE (its version).
 *
 * The same terms again log nothing; a change logs one DISCOUNT_CODE_UPDATED row with every term that changed.
 */
class UpdateDiscountCodeUc(
    private val transactionManager: TransactionManager,
    private val discountCodeRepository: DiscountCodeRepository,
    private val references: DiscountCodeReferences,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<UpdateDiscountCodeUc.Cmd, DiscountCodeId, UpdateDiscountCodeUc.Failure> {

    private val logger = getLogger()

    /** @property discountId the path's `{discountId}` as it came. */
    data class Cmd(val discountId: String, val fields: DiscountCodeFields, val actor: PanelActor) {
        override fun toString(): String = "Cmd(discountId=$discountId, code=${fields.code}, actor=${actor.userId.value})"
    }

    override suspend fun execute(command: Cmd): Result<DiscountCodeId, Failure> {
        logger.info("UpdateDiscountCodeUc.execute discountId=${command.discountId} code=${command.fields.code} by=${command.actor.userId.value}")
        val terms = command.fields.check(legacyAllowed = true).unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }
        val id = command.discountId.toLongOrNull()?.let { DiscountCodeId.makeNew(it).getOrNull() } ?: return Result.failure(Failure.NotFound)

        return transactionManager.inTransaction {
            val before = discountCodeRepository.lockById(id) ?: return@inTransaction Result.failure(Failure.NotFound)
            if (terms.code.isLegacy && terms.code != before.code) return@inTransaction Result.failure(Failure.InvalidInput(listOf(Failure.CodeTextInvalid)))
            val missing = references.problemsOf(terms)
            if (missing.isNotEmpty()) return@inTransaction Result.failure(Failure.InvalidInput(missing))
            val holder = discountCodeRepository.findByCode(terms.code)
            if (holder != null && holder.id != id) return@inTransaction Result.failure(Failure.DuplicateCode(terms.code))

            val now = clock.instant()
            val after = before.change(terms, now) ?: return@inTransaction Result.success(id)
            try {
                discountCodeRepository.save(after)
            } catch (e: UniqueTakenException) {
                logger.info("UpdateDiscountCodeUc: ${e.message} between the check and the save")
                return@inTransaction Result.failure(Failure.DuplicateCode(terms.code))
            }
            val changes = DiscountCodeAudit.changes(before.terms, terms)
            auditTrail.record(AuditEntry(now, command.actor, AuditAction.DISCOUNT_CODE_UPDATED, AuditEntityType.DISCOUNT_CODE, id.value.toString(), after.code.value, changes))
            Result.success(id)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, causes, meta) {

        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)

        data class DuplicateCode(val text: DiscountCodeText) : Failure("DUPLICATE_CODE", FailureKind.CONFLICT, "code", meta = mapOf("code" to text.value))

        /** One of the older 2-letter texts that isn't this code's own: the panel makes no new ones. */
        data object CodeTextInvalid : Failure("DISCOUNT_CODE_TEXT_INVALID", FailureKind.INVALID, "code")
    }
}
