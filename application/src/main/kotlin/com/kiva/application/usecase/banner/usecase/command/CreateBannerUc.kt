package com.kiva.application.usecase.banner.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.banner.BannerFields
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.banner.entity.Banner
import com.kiva.domain.aggregate.banner.model.valueobject.BannerId
import com.kiva.domain.aggregate.banner.repository.BannerRepository
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import java.time.Clock

/**
 * The panel makes a banner (`POST /admin/banners`; ADMIN, CONTENT_EDITOR; roadmap section 16.6). The form first, every
 * problem at once (400); then its picture: there, a BANNER upload, an image (400 MEDIA_* on `imageMediaId`). The picture
 * is marked used in the same transaction; one BANNER_CREATED row.
 */
class CreateBannerUc(
    private val transactionManager: TransactionManager,
    private val bannerRepository: BannerRepository,
    private val mediaAttachments: MediaAttachments,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<CreateBannerUc.Cmd, BannerId, CreateBannerUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val fields: BannerFields, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<BannerId, Failure> {
        logger.info("CreateBannerUc.execute placement=${command.fields.placement} by=${command.actor.userId.value}")
        val draft = command.fields.check().unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }

        return transactionManager.inTransaction {
            mediaAttachments.check(listOf(MediaAttachments.Wanted(draft.imageMediaId, "imageMediaId")), MediaPurpose.BANNER, setOf(MediaType.IMAGE))
                .unwrapOr { return@inTransaction Result.failure(Failure.InvalidInput(it.causes)) }
            val now = clock.instant()
            val banner = Banner.create(bannerRepository.nextId(), draft, now)
            bannerRepository.add(banner)
            mediaAttachments.attach(listOf(banner.imageMediaId), now)
            auditTrail.record(AuditEntry(now, command.actor, AuditAction.BANNER_CREATED, AuditEntityType.BANNER, banner.id.value.toString(), banner.title, null))
            Result.success(banner.id)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}
