package com.kiva.application.usecase.testimonial.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.application.usecase.testimonial.TestimonialFields
import com.kiva.application.usecase.testimonial.TestimonialReferences
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.testimonial.entity.Testimonial
import com.kiva.domain.aggregate.testimonial.model.valueobject.TestimonialId
import com.kiva.domain.aggregate.testimonial.repository.TestimonialRepository
import java.time.Clock

/**
 * The panel types a testimonial (`POST /admin/testimonials`; ADMIN, CONTENT_EDITOR; roadmap section 16.6). The form
 * first, every problem at once (400); then its product and colour (400) and its screenshot (TESTIMONIAL, an image; 400
 * MEDIA_*), marked used. One TESTIMONIAL_CREATED row, which keeps neither the message nor the name.
 */
class CreateTestimonialUc(
    private val transactionManager: TransactionManager,
    private val testimonialRepository: TestimonialRepository,
    private val references: TestimonialReferences,
    private val mediaAttachments: MediaAttachments,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<CreateTestimonialUc.Cmd, TestimonialId, CreateTestimonialUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val fields: TestimonialFields, val actor: PanelActor) {
        override fun toString(): String = "Cmd(productId=${fields.productId}, actor=${actor.userId.value})"
    }

    override suspend fun execute(command: Cmd): Result<TestimonialId, Failure> {
        logger.info("CreateTestimonialUc.execute productId=${command.fields.productId} by=${command.actor.userId.value}")
        val now = clock.instant()
        val draft = command.fields.check(now).unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }

        return transactionManager.inTransaction {
            val missing = references.problemsOf(draft)
            if (missing.isNotEmpty()) return@inTransaction Result.failure(Failure.InvalidInput(missing))
            val screenshot = listOfNotNull(draft.screenshotMediaId?.let { MediaAttachments.Wanted(it, "screenshotMediaId") })
            mediaAttachments.check(screenshot, MediaPurpose.TESTIMONIAL, setOf(MediaType.IMAGE))
                .unwrapOr { return@inTransaction Result.failure(Failure.InvalidInput(it.causes)) }
            val testimonial = Testimonial.create(testimonialRepository.nextId(), draft, now)
            testimonialRepository.add(testimonial)
            mediaAttachments.attach(listOfNotNull(testimonial.screenshotMediaId), now)
            auditTrail.record(
                AuditEntry(now, command.actor, AuditAction.TESTIMONIAL_CREATED, AuditEntityType.TESTIMONIAL, testimonial.id.value.toString(), "#${testimonial.id.value}", null),
            )
            Result.success(testimonial.id)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}
