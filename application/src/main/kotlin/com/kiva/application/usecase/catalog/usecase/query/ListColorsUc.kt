package com.kiva.application.usecase.catalog.usecase.query

import com.kiva.application.dto.ColorDto
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.color.repository.ColorRepository

/** The palette for swatches and the color filter (`GET /colors`), in palette order. Can't fail. */
class ListColorsUc(
    private val transactionManager: TransactionManager,
    private val colorRepository: ColorRepository,
) : QueryUseCase<ListColorsUc.Qry, List<ColorDto>, Nothing> {

    private val logger = getLogger()

    /** Nothing to choose: it is always the whole palette. */
    data object Qry

    override suspend fun execute(query: Qry): Result<List<ColorDto>, Nothing> {
        logger.info("ListColorsUc.execute")
        return transactionManager.inTransaction {
            // No products are stored yet; the real count comes from the product read model once it exists.
            Result.success(colorRepository.findAll().map { ColorDto.from(it, productCount = 0) })
        }
    }
}
