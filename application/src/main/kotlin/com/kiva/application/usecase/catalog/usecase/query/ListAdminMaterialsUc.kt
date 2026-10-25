package com.kiva.application.usecase.catalog.usecase.query

import com.kiva.application.dto.MaterialDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.material.repository.MaterialRepository

/**
 * Every material for the panel (`GET /admin/materials`; ADMIN, CATALOG_MANAGER), each with how many products of
 * **any** status are made of it. The shop's GET /materials counts only the products in the shop. Can't fail.
 */
class ListAdminMaterialsUc(
    private val transactionManager: TransactionManager,
    private val materialRepository: MaterialRepository,
    private val productQmRepository: ProductQmRepository,
) : QueryUseCase<ListAdminMaterialsUc.Qry, List<MaterialDto>, Nothing> {

    private val logger = getLogger()

    data object Qry

    override suspend fun execute(query: Qry): Result<List<MaterialDto>, Nothing> {
        logger.info("ListAdminMaterialsUc.execute")
        return transactionManager.inTransaction {
            val productCounts = productQmRepository.countAllByMaterial()
            Result.success(materialRepository.findAll().map { MaterialDto.from(it, productCounts[it.slug] ?: 0) })
        }
    }
}
