package com.kiva.infrastructure.persistence.exposed.projector

import com.kiva.common.valueobject.SeoMeta
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.product.entity.event.ProductArchived
import com.kiva.domain.aggregate.product.entity.event.ProductCreated
import com.kiva.domain.aggregate.product.entity.event.ProductDetailsChanged
import com.kiva.domain.aggregate.product.entity.event.ProductDomainEvent
import com.kiva.domain.aggregate.product.entity.event.ProductPriceChanged
import com.kiva.domain.aggregate.product.entity.event.ProductPublishScheduled
import com.kiva.domain.aggregate.product.entity.event.ProductPublishUnscheduled
import com.kiva.domain.aggregate.product.entity.event.ProductPublished
import com.kiva.domain.aggregate.product.entity.event.ProductUnpublished
import com.kiva.domain.aggregate.product.entity.event.VariantAdded
import com.kiva.domain.aggregate.product.entity.event.VariantChanged
import com.kiva.domain.aggregate.product.entity.event.VariantDeactivated
import com.kiva.domain.aggregate.product.entity.event.VariantMediaReplaced
import com.kiva.domain.aggregate.product.entity.event.VariantReactivated
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductDetails
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.persistence.event.serializer.payload.product.ProductSpecsPayload
import com.kiva.infrastructure.persistence.exposed.tbl.CategoryTbl
import com.kiva.infrastructure.persistence.exposed.tbl.GalleryItemPayload
import com.kiva.infrastructure.persistence.exposed.tbl.MaterialTbl
import com.kiva.infrastructure.persistence.exposed.tbl.MediaTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.persistence.exposed.toMediaAsset
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/**
 * Keeps the product read model (productqm_tbl, productvariantqm_tbl) in step with the product's events.
 * ProductRepositoryImpl calls [project] for each event right after storing it, in the same transaction, so an event
 * and its projection are kept or rolled back together.
 *
 * Each event changes only the rows it is about, always found by the product's id (and the color's): an UPDATE without
 * that would rewrite every product. Each also checks it changed exactly one row, so a missing row stops loudly instead
 * of being skipped. Then [ProductQmSummary.refresh] works out the derived columns again.
 *
 * Runs inside the caller's transaction; it never opens one itself.
 */
object ProductQmProjector {

    fun project(event: ProductDomainEvent) {
        val productId = event.productId.value
        // Lock order (ProductQmSummary): the product's row before any of its colors' rows. A new product has none yet.
        if (event !is ProductCreated) ProductQmSummary.lock(productId)

        when (event) {
            is ProductCreated -> insertProduct(event)
            is ProductDetailsChanged -> updateProduct(productId) { it.writeDetails(event.details) }
            is ProductPriceChanged -> changePrice(productId, event.priceTag)
            is ProductPublished -> {
                updateProduct(productId) {
                    it[status] = ProductStatus.ACTIVE
                    it[publishedOn] = event.occurredOn.utc()
                    // Published, by hand or by the job: no schedule any more (and the database allows none on ACTIVE).
                    it[publishAt] = null
                }
                // Only the first time: taken out of the shop and put back, a product is not new again.
                ProductQmTbl.update({ (ProductQmTbl.productId eq productId) and ProductQmTbl.firstPublishedOn.isNull() }) {
                    it[firstPublishedOn] = event.occurredOn.utc()
                }
            }
            is ProductUnpublished -> updateProduct(productId) {
                it[status] = ProductStatus.DRAFT
                it[publishAt] = null
            }
            is ProductArchived -> updateProduct(productId) {
                it[status] = ProductStatus.ARCHIVED
                it[publishAt] = null
            }
            is ProductPublishScheduled -> updateProduct(productId) { it[publishAt] = event.publishAt.utc() }
            is ProductPublishUnscheduled -> updateProduct(productId) { it[publishAt] = null }
            is VariantAdded -> addColor(event)
            is VariantChanged -> {
                // As in Product.apply: a new default takes it from the old one. First, or the unique index refuses.
                if (event.isDefault) takeDefaultAway(productId)
                updateColor(productId, event.variantId) {
                    it.writeColorPrice(event.ownPriceTag, ProductQmSummary.basePriceTag(productId))
                    it[sortOrder] = event.sortOrder
                    it[isDefault] = event.isDefault
                    it[barcode] = event.barcode?.value
                }
            }
            is VariantDeactivated -> {
                updateColor(productId, event.variantId) {
                    it[isActive] = false
                    it[isDefault] = false
                }
                event.newDefaultVariantId?.let { newDefault -> updateColor(productId, newDefault) { it[isDefault] = true } }
            }
            is VariantReactivated -> {
                if (event.isDefault) takeDefaultAway(productId)
                updateColor(productId, event.variantId) {
                    it[isActive] = true
                    it[isDefault] = event.isDefault
                }
            }
            is VariantMediaReplaced -> {
                val gallery = galleryOf(event.media)
                updateColor(productId, event.variantId) { it[media] = gallery }
            }
        }

        // The product's version is its last event's sequence (the panel's ETag); stock changes are other streams.
        if (event !is ProductCreated) updateProduct(productId) { it[version] = event.sequence.value }
        ProductQmSummary.refresh(productId, updatedOn = event.occurredOn)
    }

    private fun insertProduct(event: ProductCreated) {
        ProductQmTbl.insert {
            it[productId] = event.productId.value
            it[slug] = event.slug.value
            it[sku] = event.sku.value
            it.writeDetails(event.details)
            it.writeBasePrice(event.priceTag)
            // A product starts without colors; ProductQmSummary.refresh right after works these out properly.
            it[price] = event.priceTag.price.toman
            it[compareAtPrice] = event.priceTag.compareAtPrice?.toman
            it[discountPercent] = 0
            it[colorKeys] = emptyList()
            it[inStock] = false
            it[hasVideo] = false
            it[searchText] = ""
            it[status] = ProductStatus.DRAFT
            it[createdOn] = event.occurredOn.utc()
            it[updatedOn] = event.occurredOn.utc()
            it[publishedOn] = null
            it[firstPublishedOn] = null
            it[version] = event.sequence.value
            it[createdByUserId] = event.occurredByUserId.value
            it[publishAt] = null
            it[availableTotal] = 0
        }
    }

    private fun changePrice(productId: Long, priceTag: PriceTag) {
        updateProduct(productId) { it.writeBasePrice(priceTag) }
        // The colors that follow the product's price follow it here too; colors with their own keep theirs.
        ProductVariantQmTbl.update({ (ProductVariantQmTbl.productId eq productId) and (ProductVariantQmTbl.hasOwnPrice eq false) }) {
            it[price] = priceTag.price.toman
            it[compareAtPrice] = priceTag.compareAtPrice?.toman
        }
    }

    private fun addColor(event: VariantAdded) {
        val productId = event.productId.value
        val productPriceTag = ProductQmSummary.basePriceTag(productId)
        if (event.isDefault) takeDefaultAway(productId)

        ProductVariantQmTbl.insert {
            it[variantId] = event.variantId.value
            it[ProductVariantQmTbl.productId] = productId
            it[colorKey] = event.colorKey.value
            it[sku] = event.sku.value
            it.writeColorPrice(event.ownPriceTag, productPriceTag)
            it[isDefault] = event.isDefault
            it[isActive] = true
            it[sortOrder] = event.sortOrder
            it[media] = galleryOf(event.media)
            it[barcode] = event.barcode?.value
            // Stock comes from its own events (InventoryQmProjector); a new color has none yet.
            it[onHand] = 0
            it[reserved] = 0
        }
    }

    /**
     * The gallery with each file's sizes copied from its media row. The use case checked every file exists before the
     * event was raised (MediaAttachments), and a used file is never removed, so a missing one is a bug.
     */
    private fun galleryOf(media: List<ProductMedia>): List<GalleryItemPayload> {
        if (media.isEmpty()) return emptyList()
        val assets = MediaTbl.selectAll().where { MediaTbl.mediaId inList media.map { it.mediaId.value } }
            .map { it.toMediaAsset() }
            .associateBy { it.id }
        return media.map { item ->
            GalleryItemPayload(item, assets[item.mediaId] ?: error("Media ${item.mediaId.value} of a product event is not in media_tbl"))
        }
    }

    /** No color of the product is the default any more, until the caller makes one the default. */
    private fun takeDefaultAway(productId: Long) {
        ProductVariantQmTbl.update({ (ProductVariantQmTbl.productId eq productId) and (ProductVariantQmTbl.isDefault eq true) }) {
            it[isDefault] = false
        }
    }

    private fun updateProduct(productId: Long, change: ProductQmTbl.(UpdateStatement) -> Unit) {
        val updated = ProductQmTbl.update({ ProductQmTbl.productId eq productId }, body = change)
        check(updated == 1) { "Product $productId: updated $updated rows of the read model instead of 1" }
    }

    private fun updateColor(productId: Long, variantId: VariantId, change: ProductVariantQmTbl.(UpdateStatement) -> Unit) {
        val updated = ProductVariantQmTbl.update(
            { (ProductVariantQmTbl.productId eq productId) and (ProductVariantQmTbl.variantId eq variantId.value) },
            body = change,
        )
        check(updated == 1) { "Variant ${variantId.value} of product $productId: updated $updated rows of the read model instead of 1" }
    }

    /** The columns of the product form. Category and material are kept by slug, the way the filters ask for them. */
    private fun UpdateBuilder<*>.writeDetails(details: ProductDetails) {
        this[ProductQmTbl.name] = details.name.value
        this[ProductQmTbl.categorySlug] = categorySlugOf(details.categoryId)
        this[ProductQmTbl.materialSlug] = materialSlugOf(details.materialId)
        this[ProductQmTbl.bagType] = details.bagType
        this[ProductQmTbl.newUntil] = details.newUntil?.utc()
        this[ProductQmTbl.sortPriority] = details.sortPriority
        this[ProductQmTbl.occasions] = details.specs.occasions.sorted().map { it.name }
        this[ProductQmTbl.maxPerOrder] = details.maxPerOrder.value
        this[ProductQmTbl.returnable] = details.returnable
        this[ProductQmTbl.reservable] = details.reservable
        this[ProductQmTbl.shortDescription] = details.shortDescription?.value
        this[ProductQmTbl.description] = details.description?.value
        this[ProductQmTbl.highlights] = details.highlights.items
        this[ProductQmTbl.materialDescription] = details.materialDescription?.value
        this[ProductQmTbl.specs] = ProductSpecsPayload(details.specs)
        this[ProductQmTbl.tags] = details.tags.items
        this[ProductQmTbl.seo] = seoOf(details.seo)
    }

    /** The page's own SEO fields, as V24's `seo` JSONB keeps them: only those set (none is `{}`). */
    private fun seoOf(seo: SeoMeta): JsonObject = buildJsonObject {
        seo.title?.let { put("title", it) }
        seo.description?.let { put("description", it) }
        if (seo.noIndex) put("noIndex", true)
    }

    private fun UpdateBuilder<*>.writeBasePrice(priceTag: PriceTag) {
        this[ProductQmTbl.basePrice] = priceTag.price.toman
        this[ProductQmTbl.baseCompareAtPrice] = priceTag.compareAtPrice?.toman
    }

    /** A color's own price tag, or a copy of the product's when it has none. */
    private fun UpdateBuilder<*>.writeColorPrice(ownPriceTag: PriceTag?, productPriceTag: PriceTag) {
        val priceTag = ownPriceTag ?: productPriceTag
        this[ProductVariantQmTbl.price] = priceTag.price.toman
        this[ProductVariantQmTbl.compareAtPrice] = priceTag.compareAtPrice?.toman
        this[ProductVariantQmTbl.hasOwnPrice] = ownPriceTag != null
    }

    // ProductDomainService checked both exist before the event was raised, so a missing one is a bug.
    private fun categorySlugOf(id: CategoryId): String =
        CategoryTbl.select(CategoryTbl.slug).where { CategoryTbl.id eq id.value }.singleOrNull()?.get(CategoryTbl.slug)
            ?: error("Category ${id.value} of a product event is not in category_tbl")

    private fun materialSlugOf(id: MaterialId): String =
        MaterialTbl.select(MaterialTbl.slug).where { MaterialTbl.id eq id.value }.singleOrNull()?.get(MaterialTbl.slug)
            ?: error("Material ${id.value} of a product event is not in material_tbl")

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)
}
