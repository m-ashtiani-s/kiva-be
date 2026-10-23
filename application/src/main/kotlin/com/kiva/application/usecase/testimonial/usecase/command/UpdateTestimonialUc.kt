package com.kiva.application.usecase.testimonial.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.application.usecase.testimonial.TestimonialFields
import com.kiva.application.usecase.testimonial.TestimonialReferences
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.text.normalizeDigits
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.testimonial.model.valueobject.TestimonialId
import com.kiva.domain.aggregate.testimonial.repository.TestimonialRepository
import java.time.Clock

/**
 * The panel replaces a testimonial (`PUT /admin/testimonials/{testimonialId}`; ADMIN, CONTENT_EDITOR): the whole form.
 * The form first (400); then the testimonial (404 NOT_FOUND), its product and colour (400), its screenshot (400). A save
 * that changes nothing writes nothing; otherwise one TESTIMONIAL_UPDATED row with what changed (the message only as
 * «changed», the name not at all).
 */
class UpdateTestimonialUc(
    private val transactionManager: TransactionManager,
    private val testimonialRepository: TestimonialRepository,
    private val references: TestimonialReferences,
    private val mediaAttachments: MediaAttachments,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<UpdateTestimonialUc.Cmd, TestimonialId, UpdateTestimonialUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val testimonialId: String, val fields: TestimonialFields, val actor: PanelActor) {
        override fun toString(): String = "Cmd(testimonialId=$testimonialId, actor=${actor.userId.value})"
    }

    override suspend fun execute(command: Cmd): Result<TestimonialId, Failure> {
        logger.info("UpdateTestimonialUc.execute testimonialId=${command.testimonialId} by=${command.actor.userId.value}")
        val now = clock.instant()
        val draft = command.fields.check(now).unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }
        val id = command.testimonialId.trim().normalizeDigits().toLongOrNull()?.let { TestimonialId.makeNew(it).getOrNull() }
            ?: return Result.failure(Failure.NotFound)

        return transactionManager.inTransaction {
            val before = testimonialRepository.find(id) ?: return@inTransaction Result.failure(Failure.NotFound)
            val missing = references.problemsOf(draft)
            if (missing.isNotEmpty()) return@inTransaction Result.failure(Failure.InvalidInput(missing))
            val screenshot = listOfNotNull(draft.screenshotMediaId?.let { MediaAttachments.Wanted(it, "screenshotMediaId") })
            mediaAttachments.check(screenshot, MediaPurpose.TESTIMONIAL, setOf(MediaType.IMAGE))
                .unwrapOr { return@inTransaction Result.failure(Failure.InvalidInput(it.causes)) }
            val after = before.replacedBy(draft, now) ?: return@inTransaction Result.success(id)
            testimonialRepository.save(after)
            mediaAttachments.attach(listOfNotNull(after.screenshotMediaId), now)
            val changes = AuditDiff.build {
                hidden("customerName", before.customerName, after.customerName)
                field("city", before.city, after.city)
                field("source", before.source, after.source)
                longText("message", before.message, after.message)
                field("messageTime", before.messageTime, after.messageTime)
                field("receivedAt", before.receivedAt, after.receivedAt)
                field("rating", before.rating.value, after.rating.value)
                field("productId", before.productId.value, after.productId.value)
                field("colorKey", before.colorKey?.value, after.colorKey?.value)
                field("screenshotMediaId", before.screenshotMediaId?.value, after.screenshotMediaId?.value)
                field("isPublished", before.isPublished, after.isPublished)
                field("sortOrder", before.sortOrder, after.sortOrder)
            }
            auditTrail.record(AuditEntry(now, command.actor, AuditAction.TESTIMONIAL_UPDATED, AuditEntityType.TESTIMONIAL, id.value.toString(), "#${id.value}", changes))
            Result.success(id)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
