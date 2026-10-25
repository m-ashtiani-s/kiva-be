package com.kiva.application.usecase.media.usecase.query

import com.kiva.application.usecase.media.usecase.command.DeleteUnattachedMediaUc
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.repository.MediaRepository
import java.time.Clock

/**
 * The clean-up job's candidates (`delete-unattached-media`, roadmap section 5.2): files attached to nothing and
 * uploaded more than 48 hours ago, oldest first. Only candidates: DeleteUnattachedMediaUc removes each one only if it is
 * still attached to nothing.
 */
class FindUnattachedMediaUc(
    private val transactionManager: TransactionManager,
    private val mediaRepository: MediaRepository,
    private val clock: Clock,
) : QueryUseCase<FindUnattachedMediaUc.Qry, List<MediaId>, Nothing> {

    private val logger = getLogger()

    data class Qry(val limit: Int = BATCH)

    override suspend fun execute(query: Qry): Result<List<MediaId>, Nothing> {
        logger.info("FindUnattachedMediaUc.execute limit=${query.limit}")
        val before = clock.instant().minus(DeleteUnattachedMediaUc.KEPT_UNATTACHED_FOR)
        return transactionManager.inTransaction { Result.success(mediaRepository.findUnattachedBefore(before, query.limit)) }
    }

    companion object {
        /** At most this many a run. */
        const val BATCH = 100
    }
}
