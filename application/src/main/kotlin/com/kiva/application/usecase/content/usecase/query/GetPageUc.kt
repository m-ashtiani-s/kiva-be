package com.kiva.application.usecase.content.usecase.query

import com.kiva.application.dto.StaticPageDto
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.page.repository.StaticPageRepository

/**
 * A static page of the site (`GET /pages/{slug}`, public; also what `PUT /admin/pages/{slug}` answers with). A page
 * nobody wrote yet, or a slug that can't be one, is 404: no page is made up (roadmap phase 5, decision 8).
 */
class GetPageUc(
    private val transactionManager: TransactionManager,
    private val staticPageRepository: StaticPageRepository,
) : QueryUseCase<GetPageUc.Qry, StaticPageDto, GetPageUc.Failure> {

    private val logger = getLogger()

    /** @property slug the path's `{slug}` as it came. */
    data class Qry(val slug: String)

    override suspend fun execute(query: Qry): Result<StaticPageDto, Failure> {
        logger.info("GetPageUc.execute slug=${query.slug.take(MAX_LOGGED)}")
        val slug = Slug.makeNew(query.slug).getOrNull() ?: return Result.failure(Failure.NotFound)
        return transactionManager.inTransaction {
            val page = staticPageRepository.find(slug) ?: return@inTransaction Result.failure(Failure.NotFound)
            Result.success(StaticPageDto.from(page))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }

    private companion object {
        // A path is anyone's text: only its start goes into the log.
        const val MAX_LOGGED = 100
    }
}
