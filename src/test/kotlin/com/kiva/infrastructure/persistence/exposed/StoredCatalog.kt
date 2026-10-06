package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.railway.Result
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.inventory.entity.Inventory
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductDetails
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import kotlin.test.fail

/**
 * Products and stock saved on kiva_test through the real repositories, so the read model is written exactly as in the
 * app; each call is its own transaction, like one request. It remembers what it saved: [deleteAll] removes all of it,
 * events and read model rows, and belongs in an @AfterEach.
 */
class StoredCatalog {

    private val db = TestDatabase.database
    private val products = ProductRepositoryImpl()
    private val inventories = InventoryRepositoryImpl()
    private val ids = ProductIdGeneratorImpl()

    private val productIds = mutableListOf<Long>()
    private val variantIds = mutableListOf<Long>()

    val staff = UserId.constructFromPersisted(12)
    val now: Instant = Instant.parse("2026-10-06T08:00:00Z")

    /** One color of a new product. Without [makeDefault], only the first color becomes the default. */
    data class NewColor(
        val key: String,
        val ownPriceTag: PriceTag? = null,
        val makeDefault: Boolean = false,
        val media: List<ProductMedia> = emptyList(),
    )

    /**
     * A new draft product with these colors, positioned in the given order, saved [at] that moment. The slug is made
     * from the id unless given, so tests never share one.
     */
    fun newProduct(
        colors: List<NewColor>,
        priceTag: PriceTag = priceTag(1_750_000),
        details: ProductDetails = ProductEventSamples.fullDetails,
        slug: String? = null,
        at: Instant = now,
    ): Product = transaction(db) {
        val id = ids.nextProductId()
        productIds += id.value
        val product = Product.create(id, Slug.constructFromPersisted(slug ?: "bag-${id.value}"), null, details, priceTag, staff, at)
            .orFail()
        colors.forEachIndexed { index, color ->
            val variantId = ids.nextVariantId().also { variantIds += it.value }
            product.addVariant(variantId, ColorKey.constructFromPersisted(color.key), null, color.ownPriceTag, color.makeDefault, index, color.media, staff, at)
                .orFail()
        }
        products.save(product)
        product
    }

    /** A color of a new draft product and nothing else, for tests about stock alone. */
    fun newColor(): VariantId = newProduct(listOf(NewColor("lilac"))).variants.single().id

    fun read(id: ProductId): Product = transaction(db) { products.get(id) ?: fail("Product ${id.value} not stored") }

    /** Reads the product, changes it and saves the change: one request of staff. */
    fun change(id: ProductId, change: (Product) -> Result<Product, *>): Product = transaction(db) {
        val product = products.get(id) ?: fail("Product ${id.value} not stored")
        change(product).orFail()
        products.save(product)
        product
    }

    /** Reads a color's stock (empty when new), changes it and saves the change: one request. */
    fun stock(variantId: VariantId, change: (Inventory) -> Result<Inventory, *>): Inventory = transaction(db) {
        val inventory = inventories.get(variantId) ?: Inventory.empty(variantId)
        change(inventory).orFail()
        inventories.save(inventory)
        inventory
    }

    fun deleteAll() {
        transaction(db) {
            // By id alone, so a stream a test wrote by hand with one of these numbers goes too.
            EventStoreTbl.deleteWhere { aggregateId inList (productIds + variantIds).map { it.toString() } }
            ProductVariantQmTbl.deleteWhere { productId inList productIds }
            ProductQmTbl.deleteWhere { productId inList productIds }
        }
        productIds.clear()
        variantIds.clear()
    }

    companion object {
        fun priceTag(price: Long, compareAt: Long? = null): PriceTag =
            PriceTag.constructFromPersisted(Money.constructFromPersisted(price), compareAt?.let { Money.constructFromPersisted(it) })

        fun <T> Result<T, *>.orFail(): T = getOrNull() ?: fail("refused: $this")
    }
}
