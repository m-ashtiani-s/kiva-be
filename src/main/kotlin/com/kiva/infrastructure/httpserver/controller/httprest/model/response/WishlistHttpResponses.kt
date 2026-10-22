package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.usecase.wishlist.usecase.command.AddWishlistToCartUc
import com.kiva.application.dto.WishlistDto
import com.kiva.application.dto.WishlistItemDto
import com.kiva.application.dto.WishlistShareDto
import com.kiva.application.dto.WishlistToggleDto
import com.kiva.infrastructure.common.text.JalaliDates
import com.kiva.domain.aggregate.wishlist.model.policy.WishlistPolicy
import com.kiva.infrastructure.common.http.PageMetaHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable

/** The Persian words of the wishlist (roadmap section 14.5): the hearts' messages, the price drop, the shared list's owner. */
object WishlistTexts {
    const val ADDED = "به علاقه‌مندی‌ها اضافه شد"
    const val REMOVED = "از علاقه‌مندی‌ها حذف شد"
    const val MOVED = "به علاقه‌مندی‌ها منتقل شد"

    /** A shared list whose owner gave no first name. */
    const val SOMEONE = "یکی از دوستان کیوا"

    fun priceDrop(percent: Int): String = "از وقتی ذخیره کردی ${JalaliDates.persianDigits(percent)}٪ ارزون‌تر شده"

    /** «همه به سبد»: how many went in, or that none could. */
    fun addedToCart(count: Int): String = if (count == 0) "هیچ کیفی به سبد اضافه نشد" else "${JalaliDates.persianDigits(count)} کیف به سبد اضافه شد"
}

/** Contract schema `WishlistItem`. */
@Serializable
data class WishlistItemHttpResponse(
    val product: ProductSummaryHttpResponse,
    val addedAt: String,
    val preferredColorKey: String?,
    val priceAtAdd: Long,
    val priceDrop: PriceDropHttpResponse?,
) {
    companion object {
        fun from(item: WishlistItemDto, urls: MediaUrls) = WishlistItemHttpResponse(
            // Drawn in the color it was saved in, when it still comes in it.
            product = ProductSummaryHttpResponse.from(item.product, listOfNotNull(item.preferredColorKey), urls),
            addedAt = item.addedAt.toString(),
            preferredColorKey = item.preferredColorKey,
            priceAtAdd = item.priceAtAdd,
            priceDrop = item.priceDrop?.let(PriceDropHttpResponse::from),
        )
    }
}

/** «از وقتی ذخیره کردی ۱۵٪ ارزون‌تر شده». */
@Serializable
data class PriceDropHttpResponse(val amount: Long, val percent: Int, val label: String) {
    companion object {
        fun from(drop: WishlistPolicy.PriceDrop) = PriceDropHttpResponse(drop.amount.toman, drop.percent, WishlistTexts.priceDrop(drop.percent))
    }
}

/** Contract schema `WishlistShare`: [url] is the shop's page of the shared list. */
@Serializable
data class WishlistShareHttpResponse(val token: String, val url: String, val createdAt: String) {
    override fun toString(): String = "WishlistShareHttpResponse(token=${token.take(6)}…)"

    companion object {
        fun from(share: WishlistShareDto, siteUrl: String) =
            WishlistShareHttpResponse(share.token, "${siteUrl.trimEnd('/')}/wishlist/shared/${share.token}", share.createdAt.toString())
    }
}

/** Contract schema `Wishlist`. */
@Serializable
data class WishlistHttpResponse(
    val items: List<WishlistItemHttpResponse>,
    val meta: PageMetaHttpResponse,
    val count: Int,
    val inStockCount: Int,
    val share: WishlistShareHttpResponse?,
) {
    companion object {
        fun from(wishlist: WishlistDto, urls: MediaUrls, siteUrl: String) = WishlistHttpResponse(
            items = wishlist.page.items.map { WishlistItemHttpResponse.from(it, urls) },
            meta = wishlist.page.let { PageMetaHttpResponse(it.page, it.size, it.totalItems, it.totalPages, it.hasNext) },
            count = wishlist.count,
            inStockCount = wishlist.inStockCount,
            share = wishlist.share?.let { WishlistShareHttpResponse.from(it, siteUrl) },
        )
    }
}

/** Contract schema `WishlistToggleResponse`. */
@Serializable
data class WishlistToggleHttpResponse(val productId: Long, val wishlisted: Boolean, val count: Int, val message: String) {
    companion object {
        fun from(toggle: WishlistToggleDto) =
            WishlistToggleHttpResponse(toggle.productId, toggle.wishlisted, toggle.count, if (toggle.wishlisted) WishlistTexts.ADDED else WishlistTexts.REMOVED)
    }
}

/** The answer of `GET /me/wishlist/ids`. */
@Serializable
data class WishlistIdsHttpResponse(val productIds: List<Long>, val count: Int)

/** Contract schema `WishlistAddToCartResponse`: the cart after it, how many bags went in, and each product that couldn't. */
@Serializable
data class WishlistAddToCartHttpResponse(val cart: CartHttpResponse, val addedCount: Int, val skipped: List<ReorderSkippedHttpResponse>, val message: String) {
    companion object {
        fun from(cart: CartHttpResponse, added: AddWishlistToCartUc.ToCart) = WishlistAddToCartHttpResponse(
            cart = cart,
            addedCount = added.addedCount,
            skipped = added.skipped.map { ReorderSkippedHttpResponse(it.productId, it.name, it.reason.name) },
            message = WishlistTexts.addedToCart(added.addedCount),
        )
    }
}
