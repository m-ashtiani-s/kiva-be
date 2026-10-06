package com.kiva.application.usecase.catalog.usecase.query

import com.kiva.application.dto.MaterialDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.material.repository.MaterialRepository

/**
 * Every material, for the material filter and spec tables (`GET /materials`), each with how many products in the shop
 * are made of it. Can't fail.
 */
class ListMaterialsUc(
    private val transactionManager: TransactionManager,
    private val materialRepository: MaterialRepository,
    private val productQmRepository: ProductQmRepository,
) : QueryUseCase<ListMaterialsUc.Qry, List<MaterialDto>, Nothing> {

    private val logger = getLogger()

    /** Nothing to choose: it is always every material. */
    data object Qry

    override suspend fun execute(query: Qry): Result<List<MaterialDto>, Nothing> {
        logger.info("ListMaterialsUc.execute")
        return transactionManager.inTransaction {
            val productCounts = productQmRepository.countActiveByMaterial()
            Result.success(materialRepository.findAll().map { MaterialDto.from(it, productCount = productCounts[it.slug] ?: 0) })
        }
    }
}
