package com.kiva.domain.aggregate.stockalert

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.stockalert.entity.StockAlert
import com.kiva.domain.aggregate.stockalert.model.policy.StockAlertPolicy
import com.kiva.domain.aggregate.stockalert.repository.StockAlertIdGenerator
import java.time.Duration
import java.time.Instant

/**
 * The rules of subscribing to «موجود شد خبرم کن» (roadmap section 15.2, decisions 2 to 5), in the contract's order: the
 * color ([Failure.VariantNotFound]), one ACTIVE per key ([Failure.AlreadySubscribed]), not in stock now
 * ([Failure.InStock]), the 30 limit ([Failure.LimitReached]), then the hourly cap ([Failure.TooMany], with how long to
 * wait). The product being in the shop is the use case's (404 before any of these).
 */
class StockAlertDomainService(private val ids: StockAlertIdGenerator) {

    private val logger = getLogger()

    /** A product in the shop as subscribing needs it: its active colors in display order, with what each can still sell. */
    data class ProductColors(val productId: ProductId, val colors: List<Color>) {
        data class Color(val variantId: VariantId, val colorKey: ColorKey, val available: Int)
    }

    /**
     * What the customer already has: [activeCount] ACTIVE subscriptions, [madeSince] the moments of those they made in
     * the last hour, and whether one ACTIVE for this very key exists ([alreadyActive]).
     */
    data class Standing(val activeCount: Int, val madeSince: List<Instant>, val alreadyActive: Boolean)

    fun subscribe(userId: UserId, product: ProductColors, variantId: VariantId?, standing: Standing, now: Instant): Result<StockAlert, Failure> {
        logger.info("subscribe user=${userId.value} product=${product.productId.value} variant=${variantId?.value}")
        val wanted = if (variantId == null) product.colors else product.colors.filter { it.variantId == variantId }
        if (variantId != null && wanted.isEmpty()) return Result.failure(Failure.VariantNotFound)
        if (standing.alreadyActive) return Result.failure(Failure.AlreadySubscribed)
        val inStock = wanted.filter { it.available > 0 }
        if (inStock.isNotEmpty()) return Result.failure(Failure.InStock(inStock.map { it.colorKey.value }))
        if (standing.activeCount >= StockAlertPolicy.MAX_ACTIVE) return Result.failure(Failure.LimitReached)
        val recent = standing.madeSince.filter { it.isAfter(now.minus(StockAlertPolicy.RATE_WINDOW)) }.sorted()
        if (recent.size >= StockAlertPolicy.MAX_PER_HOUR) {
            // The one that leaves the window first frees the next place.
            val freed = recent[recent.size - StockAlertPolicy.MAX_PER_HOUR].plus(StockAlertPolicy.RATE_WINDOW)
            return Result.failure(Failure.TooMany(maxOf(1, Duration.between(now, freed).toSeconds())))
        }
        return Result.success(StockAlert.subscribe(ids.nextStockAlertId(), userId, product.productId, variantId, now))
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field = field, meta = meta) {
        /** Not an active color of this product (400 on `variantId`). */
        data object VariantNotFound : Failure("VARIANT_NOT_FOUND", FailureKind.INVALID, field = "variantId")

        /** One ACTIVE subscription for this product and color, or for «any color», already (409). */
        data object AlreadySubscribed : Failure("STOCK_ALERT_EXISTS", FailureKind.CONFLICT)

        /** It can be bought now (422): the colors that can, for «any color». */
        data class InStock(val colorKeys: List<String>) : Failure("PRODUCT_IN_STOCK", FailureKind.BUSINESS_RULE, meta = mapOf("inStockColorKeys" to colorKeys))

        /** 30 ACTIVE ones already (422, `meta.limit`). */
        data object LimitReached : Failure("STOCK_ALERT_LIMIT_REACHED", FailureKind.BUSINESS_RULE, meta = mapOf("limit" to StockAlertPolicy.MAX_ACTIVE))

        /** 10 made in the last hour (429), and in how many seconds the next one may be made. */
        data class TooMany(val retryAfterSeconds: Long) : Failure("RATE_LIMITED", FailureKind.RATE_LIMITED, meta = mapOf("retryAfterSeconds" to retryAfterSeconds))
    }
}
