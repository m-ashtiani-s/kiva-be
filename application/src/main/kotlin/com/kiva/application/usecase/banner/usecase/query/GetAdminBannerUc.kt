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

/** One banner: the answer of making and replacing one. */
class GetAdminBannerUc(
    private val transactionManager: TransactionManager,
    private val bannerRepository: BannerRepository,
    private val banners: Banners,
) : QueryUseCase<GetAdminBannerUc.Qry, BannerDto, GetAdminBannerUc.Failure> {

    private val logger = getLogger()

    data class Qry(val bannerId: BannerId)

    override suspend fun execute(query: Qry): Result<BannerDto, Failure> {
        logger.info("GetAdminBannerUc.execute bannerId=${query.bannerId.value}")
        return transactionManager.inTransaction {
            val banner = bannerRepository.find(query.bannerId) ?: return@inTransaction Result.failure(Failure.NotFound)
            Result.success(banners.of(listOf(banner)).single())
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
