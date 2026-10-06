package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.repository.VariantQm
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/** Reads productqm_tbl and productvariantqm_tbl. Runs inside the use case's transaction; it never opens one itself. */
class ProductQmRepositoryImpl : ProductQmRepository {

    override fun findVariant(variantId: VariantId): VariantQm? =
        ProductVariantQmTbl.selectAll()
            .where { ProductVariantQmTbl.variantId eq variantId.value }
            .singleOrNull()
            ?.let {
                VariantQm(
                    variantId = VariantId.constructFromPersisted(it[ProductVariantQmTbl.variantId]),
                    productId = ProductId.constructFromPersisted(it[ProductVariantQmTbl.productId]),
                    colorKey = ColorKey.constructFromPersisted(it[ProductVariantQmTbl.colorKey]),
                    sku = Sku.constructFromPersisted(it[ProductVariantQmTbl.sku]),
                    isActive = it[ProductVariantQmTbl.isActive],
                )
            }

    override fun countActiveByCategory(): Map<Slug, Int> =
        countActiveBy(ProductQmTbl.categorySlug).mapKeys { Slug.constructFromPersisted(it.key) }

    override fun countActiveByMaterial(): Map<Slug, Int> =
        countActiveBy(ProductQmTbl.materialSlug).mapKeys { Slug.constructFromPersisted(it.key) }

    // A product counts once for each of its active colors: unnest turns the colorKeys array into one row per color.
    override fun countActiveByColor(): Map<ColorKey, Int> =
        TransactionManager.current().exec(
            """SELECT unnest("colorKeys"), count(*) FROM productqm_tbl WHERE status = '${ProductStatus.ACTIVE.name}' GROUP BY 1""",
        ) { rows ->
            buildMap { while (rows.next()) put(ColorKey.constructFromPersisted(rows.getString(1)), rows.getInt(2)) }
        }.orEmpty()

    private fun countActiveBy(column: Column<String>): Map<String, Int> {
        val products = ProductQmTbl.id.count()
        return ProductQmTbl.select(column, products)
            .where { ProductQmTbl.status eq ProductStatus.ACTIVE }
            .groupBy(column)
            .associate { it[column] to it[products].toInt() }
    }
}
