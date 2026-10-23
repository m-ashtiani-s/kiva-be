package com.kiva.application.usecase.banner.usecase.query

import com.kiva.application.dto.BannerDto
import com.kiva.application.usecase.banner.Banners
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.banner.model.BannerDraft
import com.kiva.domain.aggregate.banner.model.valueobject.BannerId
import com.kiva.domain.aggregate.banner.model.valueobject.BannerPlacement
import com.kiva.domain.aggregate.banner.repository.BannerRepository
import java.time.Clock

/**
 * The banners of one placement the shop shows now (`GET /banners?placement=`, public; roadmap section 16.2, decisions 7
 * and 10): in their window, by `sortOrder` then id, at most 10. A placement left out or unknown is 400 on `placement`.
 */
class ListBannersUc(
    private val transactionManager: TransactionManager,
    private val bannerRepository: BannerRepository,
    private val banners: Banners,
    private val clock: Clock,
) : QueryUseCase<ListBannersUc.Qry, List<BannerDto>, ListBannersUc.Failure> {

    private val logger = getLogger()

    data class Qry(val placement: String?)

    override suspend fun execute(query: Qry): Result<List<BannerDto>, Failure> {
        logger.info("ListBannersUc.execute placement=${query.placement}")
        val text = query.placement?.trim()?.ifEmpty { null }
            ?: return Result.failure(Failure.InvalidInput(listOf(FieldFailure("placement", FieldRequired))))
        val placement = BannerPlacement.entries.firstOrNull { it.name == text }
            ?: return Result.failure(Failure.InvalidInput(listOf(FieldFailure("placement", BannerDraft.Problem.PlacementInvalid))))
        val now = clock.instant()
        return transactionManager.inTransaction { Result.success(banners.of(bannerRepository.findVisible(placement, now, MAX_SHOWN))) }
    }

    companion object {
        const val MAX_SHOWN = 10
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}
