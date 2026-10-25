package com.kiva.domain.aggregate.discount

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Quantity
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.model.PurchaseOffer
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.discount.entity.DiscountCode
import com.kiva.domain.aggregate.discount.model.CodeDiscount
import com.kiva.domain.aggregate.discount.model.DiscountLine
import com.kiva.domain.aggregate.discount.model.DiscountShopper
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountType
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * The dev seeder's three codes (roadmap section 14) and lines to try them on:
 * - KIVA10: 10% of the whole cart;
 * - WELCOME: 150,000 toman, first order only, with a login, once per customer;
 * - PAEEZ15: 15% from a subtotal of 2,000,000.
 */
object DiscountFixtures {

    val now: Instant = Instant.parse("2026-10-12T08:00:00Z")

    val customer = DiscountShopper.Customer(UserId.constructFromPersisted(7), hasPaidOrder = false)

    fun toman(amount: Long): Money = Money.constructFromPersisted(amount)

    fun text(value: String): DiscountCodeText = DiscountCodeText.constructFromPersisted(value)

    fun code(
        code: String = "KIVA10",
        type: DiscountType = DiscountType.PERCENT,
        value: Int = 10,
        label: String = "۱۰٪ تخفیف روی کل سبد",
        minSubtotal: Long? = null,
        maxDiscount: Long? = null,
        startsAt: Instant? = null,
        endsAt: Instant? = null,
        usageLimit: Int? = null,
        perUserLimit: Int? = null,
        firstOrderOnly: Boolean = false,
        requiresLogin: Boolean = false,
        excludeSaleItems: Boolean = false,
        categoryIds: Set<Long> = emptySet(),
        productIds: Set<Long> = emptySet(),
        isActive: Boolean = true,
        id: Long = 1,
    ): DiscountCode = DiscountCode.constructFromPersisted(
        id = DiscountCodeId.constructFromPersisted(id),
        code = text(code),
        type = type,
        value = value,
        label = label,
        minSubtotal = minSubtotal?.let(::toman),
        maxDiscount = maxDiscount?.let(::toman),
        startsAt = startsAt,
        endsAt = endsAt,
        usageLimit = usageLimit,
        perUserLimit = perUserLimit,
        firstOrderOnly = firstOrderOnly,
        requiresLogin = requiresLogin,
        excludeSaleItems = excludeSaleItems,
        categoryIds = categoryIds.map(CategoryId::constructFromPersisted).toSet(),
        productIds = productIds.map(ProductId::constructFromPersisted).toSet(),
        isActive = isActive,
        createdOn = Instant.parse("2026-10-01T00:00:00Z"),
        updatedOn = Instant.parse("2026-10-01T00:00:00Z"),
        version = 0,
    )

    val kiva10: DiscountCode = code()

    val welcome: DiscountCode = code(
        "WELCOME", DiscountType.FIXED, 150_000, "۱۵۰ هزار تومان تخفیف اولین خرید", firstOrderOnly = true, requiresLogin = true, perUserLimit = 1, id = 2,
    )

    val paeez15: DiscountCode = code("PAEEZ15", value = 15, label = "۱۵٪ تخفیف پاییزه (خرید بالای ۲ میلیون)", minSubtotal = 2_000_000, id = 3)

    /** What a code takes off in OrderPricing's tests: KIVA10's, for [amount]. */
    fun codeDiscount(amount: Long): CodeDiscount = CodeDiscount(kiva10, toman(amount))

    fun line(
        price: Long,
        compareAt: Long? = null,
        quantity: Int = 1,
        productId: Long = 3,
        categoryId: Long = 1,
        available: Int = 10,
        inShop: Boolean = true,
        variantId: Long = productId * 100 + 1,
    ): DiscountLine = DiscountLine(
        PurchaseOffer(
            variantId = VariantId.constructFromPersisted(variantId),
            productId = ProductId.constructFromPersisted(productId),
            categoryId = CategoryId.constructFromPersisted(categoryId),
            priceTag = PriceTag.constructFromPersisted(toman(price), compareAt?.let(::toman)),
            available = available,
            maxPerOrder = MaxPerOrder.constructFromPersisted(5),
            inShop = inShop,
            reservable = true,
            returnable = true,
        ),
        Quantity.constructFromPersisted(quantity),
    )

    /** The contract's first cart: «کیف دستی آیدا» caramel (2,790,000 instead of 3,290,000) and KV-1015 brown (690,000 instead of 790,000). */
    val firstCart: List<DiscountLine> = listOf(
        line(2_790_000, compareAt = 3_290_000, productId = 3),
        line(690_000, compareAt = 790_000, productId = 15),
    )
}
