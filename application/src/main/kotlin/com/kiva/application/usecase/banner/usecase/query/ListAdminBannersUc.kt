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

/** Every banner, scheduled and ended ones too, for the panel (`GET /admin/banners`; ADMIN, CONTENT_EDITOR). */
class ListAdminBannersUc(
    private val transactionManager: TransactionManager,
    private val bannerRepository: BannerRepository,
    private val banners: Banners,
) : QueryUseCase<ListAdminBannersUc.Qry, List<BannerDto>, Nothing> {

    private val logger = getLogger()

    data object Qry

    override suspend fun execute(query: Qry): Result<List<BannerDto>, Nothing> {
        logger.info("ListAdminBannersUc.execute")
        return transactionManager.inTransaction { Result.success(banners.of(bannerRepository.findAll())) }
    }
}
