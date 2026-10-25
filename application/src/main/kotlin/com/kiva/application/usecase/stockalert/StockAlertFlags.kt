package com.kiva.application.usecase.stockalert

import com.kiva.application.dto.ProductDetailDto
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.stockalert.entity.StockAlert
import com.kiva.domain.aggregate.stockalert.repository.StockAlertRepository

/**
 * The product page's «موجود شد خبرم کن» flags (roadmap section 15.2, decision 15): the customer's ACTIVE subscription to
 * «any color» (`stockAlert`) and, on each color, whether they wait for it (`stockAlertSubscribed`); one read for the
 * page. A guest: not subscribed anywhere. Inside the caller's transaction.
 */
fun interface StockAlertFlags {

    /** [userId]'s ACTIVE subscriptions of [productId]. */
    fun activeOf(userId: UserId, productId: ProductId): List<StockAlert>

    /** [page] with the flags of [viewer] (none: a guest, never subscribed). */
    fun mark(page: ProductDetailDto, viewer: UserId?): ProductDetailDto {
        val active = viewer?.let { activeOf(it, ProductId.constructFromPersisted(page.card.id)) }.orEmpty()
        val any = active.firstOrNull { it.variantId == null }
        val colors = active.mapNotNull { it.variantId?.value }.toSet()
        return page.copy(
            stockAlert = ProductDetailDto.StockAlertFlag(subscribed = any != null, alertId = any?.id?.value),
            variants = page.variants.map { it.copy(stockAlertSubscribed = it.id in colors) },
        )
    }

    companion object {
        /** For what reads no subscription: nobody waits for anything. */
        val NONE = StockAlertFlags { _, _ -> emptyList() }

        fun of(stockAlertRepository: StockAlertRepository) = StockAlertFlags(stockAlertRepository::activeOf)
    }
}
