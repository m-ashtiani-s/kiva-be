package com.kiva.application.usecase.catalog.usecase.query

import com.kiva.application.dto.MaterialDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.repository.MaterialRepository

/** One material as the panel lists it (every status counted): the body of `POST` and `PUT /admin/materials*`. */
class GetAdminMaterialUc(
    private val transactionManager: TransactionManager,
    private val materialRepository: MaterialRepository,
    private val productQmRepository: ProductQmRepository,
) : QueryUseCase<GetAdminMaterialUc.Qry, MaterialDto, GetAdminMaterialUc.Failure> {

    private val logger = getLogger()

    data class Qry(val materialId: MaterialId)

    override suspend fun execute(query: Qry): Result<MaterialDto, Failure> {
        logger.info("GetAdminMaterialUc.execute material=${query.materialId.value}")
        return transactionManager.inTransaction {
            val material = materialRepository.findById(query.materialId) ?: return@inTransaction Result.failure(Failure.NotFound)
            Result.success(MaterialDto.from(material, productQmRepository.countAllByMaterial()[material.slug] ?: 0))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
