package com.kiva.application.usecase.content.usecase.query

import com.kiva.application.dto.StaticPageDto
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.page.repository.StaticPageRepository

/** The panel's list of static pages (`GET /admin/pages`; ADMIN, CONTENT_EDITOR), by slug. The route leaves the content out. */
class ListPagesUc(
    private val transactionManager: TransactionManager,
    private val staticPageRepository: StaticPageRepository,
) : QueryUseCase<ListPagesUc.Qry, List<StaticPageDto>, Nothing> {

    private val logger = getLogger()

    data object Qry

    override suspend fun execute(query: Qry): Result<List<StaticPageDto>, Nothing> {
        logger.info("ListPagesUc.execute")
        return transactionManager.inTransaction { Result.success(staticPageRepository.list().map(StaticPageDto::from)) }
    }
}
