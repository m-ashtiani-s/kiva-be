package com.kiva.application.usecase.product

import com.kiva.application.dto.GalleryItemDto
import com.kiva.application.dto.PriceDto
import com.kiva.application.dto.ProductCardDto
import com.kiva.application.dto.ProductDetailDto
import com.kiva.application.repository.ProductCardQm
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.product.model.policy.BadgePolicy
import com.kiva.domain.aggregate.product.model.policy.PricePolicy
import com.kiva.domain.aggregate.product.model.policy.StockPolicy
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * A product card made from the read model with the shop's rules, the same way for the list and the product page, so
 * the two never show one bag differently.
 *
 * The card shows the default color's price. Its stock is the whole product's, through StockPolicy: sold out only when
 * no color is left, the number only when few are left in all. Its badges come from BadgePolicy, «جدید» judged at [now].
 */
object ProductCards {

    /** @param palette the colors of the shop by key; every color of [qm] is in it (the read model's foreign key). */
    fun cardOf(qm: ProductCardQm, category: Category, palette: Map<ColorKey, Color>, now: Instant, freeShippingThreshold: Money): ProductCardDto {
        val stock = StockPolicy.stockOf(qm.colors.sumOf { it.available }, qm.maxPerOrder)
        val isNew = BadgePolicy.isNew(qm.newUntil, now)

        return ProductCardDto(
            id = qm.productId.value,
            slug = qm.slug.value,
            sku = qm.sku.value,
            name = qm.name.value,
            category = ProductCardDto.CategoryRef(category.id.value, category.slug.value, category.name.value),
            bagType = qm.bagType,
            price = PriceDto.from(qm.priceTag),
            colors = qm.colors.map { color ->
                val swatch = palette.getValue(color.colorKey)
                ProductCardDto.ColorOption(
                    variantId = color.variantId.value,
                    key = swatch.key.value,
                    name = swatch.name.value,
                    hex = swatch.hex.value,
                    isLight = swatch.isLight,
                    sortOrder = swatch.sortOrder,
                    stockStatus = StockPolicy.stockOf(color.available, qm.maxPerOrder).status,
                    image = color.photos.getOrNull(0),
                    hoverImage = color.photos.getOrNull(1),
                )
            },
            // A product in the shop always has an active default color (Product keeps that); only a draft the panel
            // reads may have none.
            defaultColorKey = qm.colors.firstOrNull { it.isDefault }?.colorKey?.value,
            stock = stock,
            badges = BadgePolicy.badgesOf(isNew, qm.priceTag, qm.hasVideo, stock.status, freeShippingThreshold),
            isNew = isNew,
            hasVideo = qm.hasVideo,
            soldCount = qm.soldCount,
            freeShippingEligible = PricePolicy.qualifiesForFreeShipping(qm.priceTag, freeShippingThreshold),
        )
    }

    /**
     * One color of a product's page: its price tag, and its stock through StockPolicy, so only a few left shows a
     * number. The shop's page and the panel's (for every color, active or not) make it the same way.
     */
    fun variantOf(
        variantId: VariantId,
        sku: Sku,
        colorKey: ColorKey,
        priceTag: PriceTag,
        isDefault: Boolean,
        available: Int,
        media: List<GalleryItemDto>,
        palette: Map<ColorKey, Color>,
        maxPerOrder: MaxPerOrder,
    ): ProductDetailDto.Variant {
        val swatch = palette.getValue(colorKey)
        return ProductDetailDto.Variant(
            id = variantId.value,
            sku = sku.value,
            color = ProductDetailDto.Swatch(swatch.key.value, swatch.name.value, swatch.hex.value, swatch.isLight, swatch.sortOrder),
            isDefault = isDefault,
            price = PriceDto.from(priceTag),
            stock = StockPolicy.stockOf(available, maxPerOrder),
            media = media,
            hasVideo = media.any { it.type == MediaType.VIDEO },
        )
    }
}
