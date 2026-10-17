package com.kiva.application.usecase.media.usecase.command

import com.kiva.application.port.MediaStorage
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.repository.MediaRepository
import java.time.Clock
import java.time.Duration

/**
 * Removes one upload nothing ever used (the job `delete-unattached-media`, roadmap section 5.2): its row in a
 * transaction, then its folder once that committed. The row goes only while it is still attached to nothing and older
 * than [KEPT_UNATTACHED_FOR], decided by the database in the delete itself, so a file attached since it was found stays.
 * An attached file is never removed, even after it was taken off its product: orders' snapshots point at it.
 *
 * A folder whose removal fails stays on the disk without its row; nothing points at it, and nothing breaks.
 */
class DeleteUnattachedMediaUc(
    private val transactionManager: TransactionManager,
    private val mediaRepository: MediaRepository,
    private val mediaStorage: MediaStorage,
    private val clock: Clock,
) : CommandUseCase<DeleteUnattachedMediaUc.Cmd, Unit, Nothing> {

    private val logger = getLogger()

    data class Cmd(val mediaId: MediaId)

    override suspend fun execute(command: Cmd): Result<Unit, Nothing> {
        logger.info("DeleteUnattachedMediaUc.execute media=${command.mediaId.value}")
        val before = clock.instant().minus(KEPT_UNATTACHED_FOR)
        val removed = transactionManager.inTransaction {
            val asset = mediaRepository.find(command.mediaId)
            val gone = asset != null && mediaRepository.deleteIfUnattached(asset.id, before)
            Result.success(if (gone) asset else null)
        }.getOrNull()
        if (removed != null) {
            mediaStorage.delete(removed.storageDir)
            logger.info("DeleteUnattachedMediaUc: ${removed.id.value} removed, attached to nothing since ${removed.uploadedOn}")
        }
        return Result.success(Unit)
    }

    companion object {
        /** How long an upload may wait to be used (contract: 48 hours). */
        val KEPT_UNATTACHED_FOR: Duration = Duration.ofHours(48)
    }
}
