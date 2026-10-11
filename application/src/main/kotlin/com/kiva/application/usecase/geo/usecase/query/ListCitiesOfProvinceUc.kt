package com.kiva.application.usecase.geo.usecase.query

import com.kiva.application.dto.CityDto
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.geo.model.valueobject.ProvinceId
import com.kiva.domain.aggregate.geo.repository.GeoRepository

/** The cities of one province, in list order (`GET /geo/provinces/{provinceId}/cities`). */
class ListCitiesOfProvinceUc(
    private val transactionManager: TransactionManager,
    private val geoRepository: GeoRepository,
) : QueryUseCase<ListCitiesOfProvinceUc.Qry, List<CityDto>, ListCitiesOfProvinceUc.Failure> {

    private val logger = getLogger()

    /** The path segment as it arrived. */
    data class Qry(val provinceId: String)

    override suspend fun execute(query: Qry): Result<List<CityDto>, Failure> {
        logger.info("ListCitiesOfProvinceUc.execute provinceId=${query.provinceId}")

        // A segment that is not a positive number can name no province, so it is simply "not found" (the contract has
        // no 400 here). Checked before the transaction, so bad input never takes a database connection.
        val provinceId = query.provinceId.trim().normalizeDigits().toIntOrNull()?.let { ProvinceId.makeNew(it).getOrNull() }
            ?: return Result.failure(Failure.ProvinceNotFound)

        return transactionManager.inTransaction {
            geoRepository.findProvince(provinceId) ?: return@inTransaction Result.failure(Failure.ProvinceNotFound)
            Result.success(geoRepository.findCitiesOf(provinceId).map(CityDto::from))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        // The contract has no province-specific code; the general NOT_FOUND is what the client expects.
        data object ProvinceNotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
