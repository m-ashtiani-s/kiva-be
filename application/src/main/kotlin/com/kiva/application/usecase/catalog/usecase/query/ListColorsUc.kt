package com.kiva.application.usecase.catalog.usecase.query

import com.kiva.application.dto.ColorDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.color.repository.ColorRepository

/**
 * The palette for swatches and the color filter (`GET /colors`), in palette order, each with how many products in the
 * shop offer it. Can't fail.
 */
class ListColorsUc(
    private val transactionManager: TransactionManager,
    private val colorRepository: ColorRepository,
    private val productQmRepository: ProductQmRepository,
) : QueryUseCase<ListColorsUc.Qry, List<ColorDto>, Nothing> {

    private val logger = getLogger()

    /** Nothing to choose: it is always the whole palette. */
    data object Qry

    override suspend fun execute(query: Qry): Result<List<ColorDto>, Nothing> {
        logger.info("ListColorsUc.execute")
        return transactionManager.inTransaction {
            val productCounts = productQmRepository.countActiveByColor()
            Result.success(colorRepository.findAll().map { ColorDto.from(it, productCount = productCounts[it.key] ?: 0) })
        }
    }
}
