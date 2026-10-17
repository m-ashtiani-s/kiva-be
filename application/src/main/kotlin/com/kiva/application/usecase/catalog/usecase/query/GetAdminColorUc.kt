package com.kiva.application.usecase.catalog.usecase.query

import com.kiva.application.dto.ColorDto
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository

/**
 * One palette color: the body of `POST` and `PUT /admin/colors*`. Without `productCount`, which the contract sends only
 * in GET /colors and the facets.
 */
class GetAdminColorUc(
    private val transactionManager: TransactionManager,
    private val colorRepository: ColorRepository,
) : QueryUseCase<GetAdminColorUc.Qry, ColorDto, GetAdminColorUc.Failure> {

    private val logger = getLogger()

    data class Qry(val key: ColorKey)

    override suspend fun execute(query: Qry): Result<ColorDto, Failure> {
        logger.info("GetAdminColorUc.execute color=${query.key.value}")
        return transactionManager.inTransaction {
            val color = colorRepository.findByKey(query.key) ?: return@inTransaction Result.failure(Failure.NotFound)
            Result.success(ColorDto.from(color, productCount = null))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
