package com.kiva.application.usecase.stockalert.usecase.query

import com.kiva.application.dto.AdminStockAlertGroupDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.product.model.policy.StockPolicy
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.stockalert.repository.StockAlertRepository

/**
 * The panel's «موجود شد خبرم کن» queue (`GET /admin/stock-alerts`; ADMIN, CATALOG_MANAGER; roadmap section 15.2,
 * decision 16): the ACTIVE subscriptions grouped by product and color (or «any color»), most waiting first, then the
 * longest waiting; one product's, and only those that can or can't be bought now, when asked. Each row the product, the
 * color and its code, the exact stock and its status by the shop's threshold. Nobody's phone. A page in four reads.
 */
class FindAdminStockAlertGroupsUc(
    private val transactionManager: TransactionManager,
    private val stockAlertRepository: StockAlertRepository,
    private val productQmRepository: ProductQmRepository,
    private val colorRepository: ColorRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
) : QueryUseCase<FindAdminStockAlertGroupsUc.Qry, Page<AdminStockAlertGroupDto>, FindAdminStockAlertGroupsUc.Failure> {

    private val logger = getLogger()

    /** The query parameters as they came. */
    data class Qry(val pageable: Pageable, val productId: String? = null, val inStock: String? = null)

    override suspend fun execute(query: Qry): Result<Page<AdminStockAlertGroupDto>, Failure> {
        logger.info("FindAdminStockAlertGroupsUc.execute productId=${query.productId} inStock=${query.inStock} page=${query.pageable.page}")
        val problems = mutableListOf<ResultFailure>()
        val productId = query.productId?.trim()?.ifEmpty { null }?.let { raw ->
            raw.normalizeDigits().toLongOrNull()?.let { ProductId.makeNew(it).getOrNull() }
                ?: null.also { problems += FieldFailure("productId", Problem.ProductIdInvalid) }
        }
        val inStock = when (query.inStock?.trim()?.ifEmpty { null }) {
            null -> null
            "true" -> true
            "false" -> false
            else -> null.also { problems += FieldFailure("inStock", Problem.BooleanInvalid) }
        }
        if (problems.isNotEmpty()) return Result.failure(Failure.InvalidInput(problems))

        return transactionManager.inTransaction {
            val page = stockAlertRepository.waitingGroups(productId, inStock, query.pageable)
            val products = productQmRepository.findBriefs(page.items.map { it.productId }.distinct())
            val variants = productQmRepository.findVariants(page.items.mapNotNull { it.variantId }.distinct())
            val palette = colorRepository.findAll().associateBy { it.key }
            val threshold = shopSettingsRepository.get().lowStockThreshold
            val items = page.items.map { group ->
                // A subscription is only ever made for a product and a color of it, and the read model forgets neither.
                val product = products[group.productId] ?: error("No product ${group.productId.value} for its stock alerts")
                val variant = group.variantId?.let { variants[it] ?: error("No color ${it.value} for its stock alerts") }
                AdminStockAlertGroupDto(
                    product = product,
                    variantId = group.variantId?.value,
                    color = variant?.let { palette[it.colorKey] },
                    sku = variant?.sku?.value,
                    waitingCount = group.waitingCount,
                    oldestAt = group.oldestOn,
                    // Only the status is read: how many one order may hold doesn't change it.
                    stockStatus = StockPolicy.stockOf(group.available, MaxPerOrder.DEFAULT, threshold).status,
                    availableQuantity = group.available,
                )
            }
            Result.success(Page(items, page.page, page.size, page.totalItems))
        }
    }

    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object BooleanInvalid : Problem("BOOLEAN_INVALID")
        data object ProductIdInvalid : Problem("PRODUCT_ID_INVALID")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}
