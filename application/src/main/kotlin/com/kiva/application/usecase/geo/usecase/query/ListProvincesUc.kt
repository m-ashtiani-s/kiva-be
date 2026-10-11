package com.kiva.application.usecase.geo.usecase.query

import com.kiva.application.dto.CityDto
import com.kiva.application.dto.ProvinceDto
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.geo.repository.GeoRepository

/**
 * Iran's provinces for the address form (`GET /geo/provinces`), in list order, each with its cities unless the client
 * asks for the provinces alone (`includeCities=false`, then `cities` is null). All cities come in one query, so the
 * whole list costs two.
 */
class ListProvincesUc(
    private val transactionManager: TransactionManager,
    private val geoRepository: GeoRepository,
) : QueryUseCase<ListProvincesUc.Qry, List<ProvinceDto>, ListProvincesUc.Failure> {

    private val logger = getLogger()

    /** `?includeCities=` as it arrived. */
    data class Qry(val includeCities: String? = null)

    override suspend fun execute(query: Qry): Result<List<ProvinceDto>, Failure> {
        logger.info("ListProvincesUc.execute includeCities=${query.includeCities}")

        // Checked before the transaction, so a wrong flag never takes a database connection.
        val includeCities = flagOf(query.includeCities)
            ?: return Result.failure(Failure.InvalidInput(listOf(FieldFailure("includeCities", Failure.BooleanInvalid))))

        return transactionManager.inTransaction {
            val provinces = geoRepository.findAllProvinces()
            val citiesByProvince = if (includeCities) geoRepository.findAllCities().groupBy { it.provinceId } else null
            Result.success(
                provinces.map { province ->
                    ProvinceDto.from(province, citiesByProvince?.let { cities -> cities[province.id].orEmpty().map(CityDto::from) })
                },
            )
        }
    }

    /** Missing or empty is the contract's default (true); otherwise only `true` or `false`, in any case. */
    private fun flagOf(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        null, "" -> true
        "true" -> true
        "false" -> false
        else -> null
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) :
        ResultFailure(code, kind, causes = causes) {
        /** `includeCities` is neither true nor false (400 VALIDATION_ERROR). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object BooleanInvalid : Failure("BOOLEAN_INVALID", FailureKind.INVALID)
    }
}
