package com.kiva.infrastructure.persistence.exposed

import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.product.repository.ProductIdGenerator
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Takes the next number from the sequences of V4__product_id_sequences.sql. Runs inside the use case's transaction;
 * it never opens one itself.
 */
class ProductIdGeneratorImpl : ProductIdGenerator {

    override fun nextProductId(): ProductId = ProductId.constructFromPersisted(next(PRODUCT_ID_SEQUENCE))

    override fun nextVariantId(): VariantId = VariantId.constructFromPersisted(next(VARIANT_ID_SEQUENCE))

    private fun next(sequence: String): Long =
        TransactionManager.current().exec("SELECT nextval('$sequence')") { rows ->
            rows.next()
            rows.getLong(1)
        } ?: error("nextval('$sequence') returned nothing")

    private companion object {
        const val PRODUCT_ID_SEQUENCE = "productid_seq"
        const val VARIANT_ID_SEQUENCE = "variantid_seq"
    }
}
