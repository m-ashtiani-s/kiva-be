package com.kiva.application.usecase.catalog.usecase.query

import com.kiva.application.dto.MaterialDto
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.material.repository.MaterialRepository

/** Every material, for the material filter and spec tables (`GET /materials`). Can't fail. */
class ListMaterialsUc(
    private val transactionManager: TransactionManager,
    private val materialRepository: MaterialRepository,
) : QueryUseCase<ListMaterialsUc.Qry, List<MaterialDto>, Nothing> {

    private val logger = getLogger()

    /** Nothing to choose: it is always every material. */
    data object Qry

    override suspend fun execute(query: Qry): Result<List<MaterialDto>, Nothing> {
        logger.info("ListMaterialsUc.execute")
        return transactionManager.inTransaction {
            // No products are stored yet; the real count comes from the product read model once it exists.
            Result.success(materialRepository.findAll().map { MaterialDto.from(it, productCount = 0) })
        }
    }
}
