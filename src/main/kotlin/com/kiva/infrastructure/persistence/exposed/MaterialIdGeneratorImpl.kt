package com.kiva.infrastructure.persistence.exposed

import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.repository.MaterialIdGenerator
import com.kiva.infrastructure.persistence.exposed.tbl.MaterialTbl
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/** Takes the next number of material_tbl's identity. Runs inside the use case's transaction. */
class MaterialIdGeneratorImpl : MaterialIdGenerator {
    override fun nextMaterialId(): MaterialId =
        TransactionManager.current().exec("SELECT nextval(${MaterialTbl.ID_SEQUENCE_OF})") { rows ->
            rows.next()
            MaterialId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval of material_tbl's id sequence returned nothing")
}
