package com.kiva.infrastructure.persistence.exposed

import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.ProductSpecs
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.event.serializer.payload.product.ProductSpecsPayload
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * V5__product_read_model.sql: the database itself refuses read model rows that break the rules the projectors copy,
 * so a projector bug stops at the first wrong write. Every write here is rolled back.
 */
class ProductReadModelMigrationTest {

    private val db = TestDatabase.database
    private val at = OffsetDateTime.parse("2026-10-06T08:00:00Z")

    // Far above what the sequences hand out in a test run, so these never meet a real product.
    private val testProduct = 900_001L

    /** The write must fail, and the database names the rule that refused it. */
    private fun assertRefusedBy(constraint: String, write: JdbcTransaction.() -> Unit) = transaction(db) {
        insertProduct()
        val error = assertFailsWith<ExposedSQLException> { write() }
        assertEquals(constraint, (error.cause as PSQLException).serverErrorMessage?.constraint)
        rollback()
    }

    private fun insertProduct(id: Long = testProduct, categorySlug: String = "shoulder") {
        ProductQmTbl.insert {
            it[productId] = id
            it[slug] = "bag-$id"
            it[sku] = "KV-$id"
            it[name] = "کیف"
            it[searchText] = "کیف"
            it[ProductQmTbl.categorySlug] = categorySlug
            it[materialSlug] = "satin"
            it[bagType] = BagType.HOBO
            it[basePrice] = 1_000_000
            it[baseCompareAtPrice] = null
            it[price] = 1_000_000
            it[compareAtPrice] = null
            it[discountPercent] = 0
            it[colorKeys] = emptyList()
            it[inStock] = false
            it[hasVideo] = false
            it[sortPriority] = 0
            it[occasions] = emptyList()
            it[maxPerOrder] = 5
            it[returnable] = true
            it[reservable] = true
            it[highlights] = emptyList()
            it[specs] = ProductSpecsPayload(ProductSpecs.NONE)
            it[status] = ProductStatus.DRAFT
            it[createdOn] = at
            it[updatedOn] = at
        }
    }

    private fun insertColor(
        variantId: Long,
        colorKey: String = "lilac",
        isDefault: Boolean = false,
        isActive: Boolean = true,
        onHand: Int = 0,
        reserved: Int = 0,
    ) {
        ProductVariantQmTbl.insert {
            it[ProductVariantQmTbl.variantId] = variantId
            it[productId] = testProduct
            it[ProductVariantQmTbl.colorKey] = colorKey
            it[sku] = "KV-$testProduct-${colorKey.uppercase()}-$variantId"
            it[price] = 1_000_000
            it[compareAtPrice] = null
            it[hasOwnPrice] = false
            it[ProductVariantQmTbl.isDefault] = isDefault
            it[ProductVariantQmTbl.isActive] = isActive
            it[sortOrder] = 0
            it[media] = emptyList()
            it[ProductVariantQmTbl.onHand] = onHand
            it[ProductVariantQmTbl.reserved] = reserved
        }
    }

    @Test
    fun `a product has at most one default color`() = assertRefusedBy("productvariantqm_tbl_one_default_idx") {
        insertColor(900_001, "lilac", isDefault = true)
        insertColor(900_002, "black", isDefault = true)
    }

    @Test
    fun `a color out of the shop can't be the default`() = assertRefusedBy("productvariantqm_tbl_default_check") {
        insertColor(900_001, isDefault = true, isActive = false)
    }

    @Test
    fun `a color appears once per product`() = assertRefusedBy("productvariantqm_tbl_product_color_key") {
        insertColor(900_001, "lilac")
        insertColor(900_002, "lilac")
    }

    @Test
    fun `more reserved than on hand is refused, like in the Inventory aggregate`() =
        assertRefusedBy("productvariantqm_tbl_available_check") {
            insertColor(900_001, onHand = 2, reserved = 3)
        }

    @Test
    fun `a color of no product is refused`() = assertRefusedBy("productvariantqm_tbl_productId_fkey") {
        ProductVariantQmTbl.insert {
            it[variantId] = 900_001
            it[productId] = 900_999
            it[colorKey] = "lilac"
            it[sku] = "KV-900999-LILAC"
            it[price] = 1
            it[hasOwnPrice] = false
            it[isDefault] = false
            it[isActive] = true
            it[sortOrder] = 0
            it[media] = emptyList()
        }
    }

    @Test
    fun `a category the catalog doesn't have is refused`() = assertRefusedBy("productqm_tbl_categorySlug_fkey") {
        insertProduct(id = 900_002, categorySlug = "nope")
    }
}
