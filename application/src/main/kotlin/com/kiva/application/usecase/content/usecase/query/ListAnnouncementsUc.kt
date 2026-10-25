package com.kiva.application.usecase.content.usecase.query

import com.kiva.application.dto.AnnouncementDto
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.announcement.repository.AnnouncementBarRepository

/**
 * Every announcement of the bar for the panel's form (`GET /admin/announcements`; ADMIN, CONTENT_EDITOR), scheduled and
 * ended ones too, in order: the list `PUT` replaces. Also what the `PUT` answers with.
 */
class ListAnnouncementsUc(
    private val transactionManager: TransactionManager,
    private val announcementBarRepository: AnnouncementBarRepository,
) : QueryUseCase<ListAnnouncementsUc.Qry, List<AnnouncementDto>, Nothing> {

    private val logger = getLogger()

    data object Qry

    override suspend fun execute(query: Qry): Result<List<AnnouncementDto>, Nothing> {
        logger.info("ListAnnouncementsUc.execute")
        return transactionManager.inTransaction { Result.success(announcementBarRepository.load().items.map(AnnouncementDto::from)) }
    }
}
