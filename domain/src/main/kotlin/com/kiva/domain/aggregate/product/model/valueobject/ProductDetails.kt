package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.valueobject.SeoMeta
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import java.time.Instant

/**
 * Everything about a product that staff edit together in the product form: what it is, how it is described and
 * how it is sold. Price, status and colors are not here; each of them changes on its own.
 *
 * Every part is already a valid value, so there is nothing more to check here.
 *
 * @property categoryId / [materialId] only the id: a product refers to its category and material, it doesn't hold them.
 * @property newUntil the «جدید» badge shows until then (contract: instead of a hand-set isNew flag).
 * @property reservable it may go in an order held for the 4-day reservation; one item that isn't makes the whole cart
 *   unreservable (contract: `ITEM_NOT_RESERVABLE`).
 * @property sortPriority higher comes first where staff pin products in the list.
 * @property seo the page's own SEO title and description, and noIndex (the rest of SeoMeta is the server's).
 */
data class ProductDetails(
    val name: ProductName,
    val categoryId: CategoryId,
    val materialId: MaterialId,
    val materialDescription: MaterialDescription?,
    val bagType: BagType,
    val shortDescription: ShortDescription?,
    val description: ProductDescription?,
    val highlights: Highlights,
    val specs: ProductSpecs,
    val newUntil: Instant?,
    val maxPerOrder: MaxPerOrder,
    val returnable: Boolean,
    val reservable: Boolean,
    val sortPriority: Int,
    val tags: ProductTags = ProductTags.NONE,
    val seo: SeoMeta = SeoMeta.NONE,
)
