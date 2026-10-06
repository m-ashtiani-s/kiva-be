package com.kiva.domain.aggregate.product

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.Highlights
import com.kiva.domain.aggregate.product.model.valueobject.MaterialDescription
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.MediaType
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductDetails
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.ProductSpecs
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/** Ready-made values for product tests: product 1 of the prototype, «کیف دوشی ماهک». */
object ProductTestData {
    val now: Instant = Instant.parse("2026-10-06T08:00:00Z")
    val later: Instant = Instant.parse("2026-10-07T09:30:00Z")
    val admin = UserId.constructFromPersisted(1)

    fun toman(amount: Long): Money = Money.makeNew(toman = amount).getOrNull()!!

    fun priceTag(price: Long, compareAt: Long? = null): PriceTag = PriceTag.constructFromPersisted(toman(price), compareAt?.let(::toman))

    fun color(key: String): ColorKey = ColorKey.constructFromPersisted(key)

    fun variantId(id: Long): VariantId = VariantId.constructFromPersisted(id)

    fun details(name: String = "کیف دوشی ماهک", maxPerOrder: Int = 5): ProductDetails = ProductDetails(
        name = ProductName.constructFromPersisted(name),
        categoryId = CategoryId.constructFromPersisted(1),
        materialId = MaterialId.constructFromPersisted(4),
        materialDescription = MaterialDescription.constructFromPersisted("چرم مصنوعی مات درجه یک"),
        bagType = BagType.HOBO,
        shortDescription = null,
        description = null,
        highlights = Highlights.constructFromPersisted(listOf("یراق‌آلات فلزی ضدزنگ با روکش طلایی مات")),
        specs = ProductSpecs.NONE,
        newUntil = Instant.parse("2026-10-20T00:00:00Z"),
        maxPerOrder = MaxPerOrder.constructFromPersisted(maxPerOrder),
        returnable = true,
        sortPriority = 0,
    )

    /** A new draft product without colors. */
    fun newProduct(id: Long = 1, sku: String? = null, priceTag: PriceTag = priceTag(1_890_000)): Product =
        Product.create(
            id = ProductId.constructFromPersisted(id),
            slug = Slug.constructFromPersisted("mahak-shoulder-bag"),
            sku = sku?.let(Sku::constructFromPersisted),
            details = details(),
            priceTag = priceTag,
            by = admin,
            now = now,
        ).getOrNull()!!

    /** Adds a color with everything left to the product (own SKU, own price, not asked to be default). */
    fun Product.withColor(
        id: Long,
        key: String,
        makeDefault: Boolean = false,
        sku: String? = null,
        ownPriceTag: PriceTag? = null,
        media: List<ProductMedia> = emptyList(),
    ): Product = addVariant(
        variantId = variantId(id),
        colorKey = color(key),
        sku = sku?.let(Sku::constructFromPersisted),
        ownPriceTag = ownPriceTag,
        makeDefault = makeDefault,
        sortOrder = variants.size,
        media = media,
        by = admin,
        now = now,
    ).getOrNull()!!

    fun photo(name: String): ProductMedia =
        ProductMedia.constructFromPersisted(MediaType.IMAGE, "https://cdn.kiva.ir/p/1/$name.webp", null, null)

    /** Every field of a product, its colors' too (a Variant's equals only compares ids): for replay tests. */
    fun Product.snapshot(): List<Any?> = listOf(
        id, slug, sku, details, priceTag, status,
        variants.map { listOf(it.id, it.colorKey, it.sku, it.ownPriceTag, it.isDefault, it.isActive, it.sortOrder, it.media) },
    )
}
