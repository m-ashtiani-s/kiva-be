package com.kiva.application.usecase.banner.usecase.command

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
import com.kiva.domain.aggregate.banner.model.valueobject.BannerId
import com.kiva.domain.aggregate.banner.repository.BannerRepository
import java.time.Clock

/**
 * The panel deletes a banner for good (`DELETE /admin/banners/{bannerId}`; ADMIN, CONTENT_EDITOR; decision 18). Its
 * picture stays (roadmap section 5.2). One BANNER_DELETED row; one nobody has is 404 NOT_FOUND.
 */
class DeleteBannerUc(
    private val transactionManager: TransactionManager,
    private val bannerRepository: BannerRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<DeleteBannerUc.Cmd, Unit, DeleteBannerUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val bannerId: String, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("DeleteBannerUc.execute bannerId=${command.bannerId} by=${command.actor.userId.value}")
        val id = command.bannerId.trim().normalizeDigits().toLongOrNull()?.let { BannerId.makeNew(it).getOrNull() }
            ?: return Result.failure(Failure.NotFound)
        return transactionManager.inTransaction {
            val banner = bannerRepository.find(id) ?: return@inTransaction Result.failure(Failure.NotFound)
            bannerRepository.delete(id)
            auditTrail.record(AuditEntry(clock.instant(), command.actor, AuditAction.BANNER_DELETED, AuditEntityType.BANNER, id.value.toString(), banner.title, null))
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
