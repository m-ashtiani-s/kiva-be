package com.kiva.application.usecase.banner.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.banner.BannerFields
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.text.normalizeDigits
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.banner.model.valueobject.BannerId
import com.kiva.domain.aggregate.banner.repository.BannerRepository
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import java.time.Clock

/**
 * The panel replaces a banner (`PUT /admin/banners/{bannerId}`; ADMIN, CONTENT_EDITOR): the whole form, a field left out
 * its default. The form first (400); then the banner (404 NOT_FOUND, also for an id that can't be one) and its picture
 * (400 MEDIA_*). A save that changes nothing writes nothing; otherwise one BANNER_UPDATED row with what changed.
 */
class UpdateBannerUc(
    private val transactionManager: TransactionManager,
    private val bannerRepository: BannerRepository,
    private val mediaAttachments: MediaAttachments,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<UpdateBannerUc.Cmd, BannerId, UpdateBannerUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val bannerId: String, val fields: BannerFields, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<BannerId, Failure> {
        logger.info("UpdateBannerUc.execute bannerId=${command.bannerId} by=${command.actor.userId.value}")
        val draft = command.fields.check().unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }
        val id = command.bannerId.trim().normalizeDigits().toLongOrNull()?.let { BannerId.makeNew(it).getOrNull() }
            ?: return Result.failure(Failure.NotFound)

        return transactionManager.inTransaction {
            val before = bannerRepository.find(id) ?: return@inTransaction Result.failure(Failure.NotFound)
            mediaAttachments.check(listOf(MediaAttachments.Wanted(draft.imageMediaId, "imageMediaId")), MediaPurpose.BANNER, setOf(MediaType.IMAGE))
                .unwrapOr { return@inTransaction Result.failure(Failure.InvalidInput(it.causes)) }
            val now = clock.instant()
            val after = before.replacedBy(draft, now) ?: return@inTransaction Result.success(id)
            bannerRepository.save(after)
            mediaAttachments.attach(listOf(after.imageMediaId), now)
            val changes = AuditDiff.build {
                field("placement", before.placement, after.placement)
                field("theme", before.theme, after.theme)
                field("tagLabel", before.tag?.label, after.tag?.label)
                field("tagIcon", before.tag?.icon?.value, after.tag?.icon?.value)
                field("title", before.title, after.title)
                field("text", before.text, after.text)
                field("ctaLabel", before.cta?.label, after.cta?.label)
                field("ctaUrl", before.cta?.url?.value, after.cta?.url?.value)
                field("imageMediaId", before.imageMediaId.value, after.imageMediaId.value)
                field("overlayHighlight", before.overlay?.highlight, after.overlay?.highlight)
                field("overlayTitle", before.overlay?.title, after.overlay?.title)
                field("overlaySubtitle", before.overlay?.subtitle, after.overlay?.subtitle)
                field("overlayChannel", before.overlay?.channel, after.overlay?.channel)
                field("sortOrder", before.sortOrder, after.sortOrder)
                field("startsAt", before.window.startsAt, after.window.startsAt)
                field("endsAt", before.window.endsAt, after.window.endsAt)
            }
            auditTrail.record(AuditEntry(now, command.actor, AuditAction.BANNER_UPDATED, AuditEntityType.BANNER, id.value.toString(), after.title, changes))
            Result.success(id)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
