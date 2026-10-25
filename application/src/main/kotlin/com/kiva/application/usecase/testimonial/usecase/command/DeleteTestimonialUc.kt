package com.kiva.application.usecase.testimonial.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.testimonial.model.valueobject.TestimonialId
import com.kiva.domain.aggregate.testimonial.repository.TestimonialRepository
import java.time.Clock

/**
 * The panel deletes a testimonial for good (`DELETE /admin/testimonials/{testimonialId}`; ADMIN, CONTENT_EDITOR;
 * decision 18). Its screenshot stays (roadmap section 5.2). One TESTIMONIAL_DELETED row; one nobody has is 404.
 */
class DeleteTestimonialUc(
    private val transactionManager: TransactionManager,
    private val testimonialRepository: TestimonialRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<DeleteTestimonialUc.Cmd, Unit, DeleteTestimonialUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val testimonialId: String, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("DeleteTestimonialUc.execute testimonialId=${command.testimonialId} by=${command.actor.userId.value}")
        val id = command.testimonialId.trim().normalizeDigits().toLongOrNull()?.let { TestimonialId.makeNew(it).getOrNull() }
            ?: return Result.failure(Failure.NotFound)
        return transactionManager.inTransaction {
            if (!testimonialRepository.delete(id)) return@inTransaction Result.failure(Failure.NotFound)
            auditTrail.record(AuditEntry(clock.instant(), command.actor, AuditAction.TESTIMONIAL_DELETED, AuditEntityType.TESTIMONIAL, id.value.toString(), "#${id.value}", null))
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
