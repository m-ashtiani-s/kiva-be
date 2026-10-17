package com.kiva.infrastructure.persistence.exposed

import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.repository.CategoryIdGenerator
import com.kiva.infrastructure.persistence.exposed.tbl.CategoryTbl
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/** Takes the next number of category_tbl's identity. Runs inside the use case's transaction. */
class CategoryIdGeneratorImpl : CategoryIdGenerator {
    override fun nextCategoryId(): CategoryId =
        TransactionManager.current().exec("SELECT nextval(${CategoryTbl.ID_SEQUENCE_OF})") { rows ->
            rows.next()
            CategoryId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval of category_tbl's id sequence returned nothing")
}
