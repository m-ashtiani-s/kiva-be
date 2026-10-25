package com.kiva.application.usecase.stockalert.usecase.command

import com.kiva.application.dto.StockAlertDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.stockalert.StockAlerts
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.text.normalizeDigits
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.stockalert.StockAlertDomainService
import com.kiva.domain.aggregate.stockalert.model.policy.StockAlertPolicy
import com.kiva.domain.aggregate.stockalert.repository.StockAlertRepository
import java.time.Clock

/**
 * «موجود شد خبرم کن» (`POST /products/{productId}/stock-alerts`; roadmap section 15.6, decisions 1 to 5): a logged-in
 * customer waits for a color of a product in the shop, or for any of its colors. The SMS goes to the account's phone,
 * read when it is sent. The rules in the contract's order (StockAlertDomainService); two at once for one key: the unique
 * index lets one in, the other is 409 STOCK_ALERT_EXISTS too.
 *
 * Returns the subscription as «اشتراک‌های من» shows it: a documented exception to «a command returns only ids», since
 * nothing else reads one alert.
 */
class CreateStockAlertUc(
    private val transactionManager: TransactionManager,
    private val productQmRepository: ProductQmRepository,
    private val stockAlertRepository: StockAlertRepository,
    private val stockAlertDomainService: StockAlertDomainService,
    private val stockAlerts: StockAlerts,
    private val clock: Clock,
) : CommandUseCase<CreateStockAlertUc.Cmd, StockAlertDto, CreateStockAlertUc.Failure> {

    private val logger = getLogger()

    /** @property userId from the access token; [productId] the path segment as it came; [variantId] and [channel] the body's. */
    data class Cmd(val userId: UserId, val productId: String, val variantId: Long?, val channel: String?)

    override suspend fun execute(command: Cmd): Result<StockAlertDto, Failure> {
        val productId = command.productId.trim().normalizeDigits().toLongOrNull()?.let { ProductId.makeNew(it).getOrNull() }
        logger.info("CreateStockAlertUc.execute userId=${command.userId.value} productId=${productId?.value ?: "(not an id)"} variantId=${command.variantId}")
        val problems = buildList {
            if (command.variantId != null && command.variantId <= 0) add(FieldFailure("variantId", StockAlertDomainService.Failure.VariantNotFound))
            if (command.channel != null && command.channel != SMS) add(FieldFailure("channel", Problem.ChannelInvalid))
        }
        if (problems.isNotEmpty()) return Result.failure(Failure.InvalidInput(problems))
        if (productId == null) return Result.failure(Failure.ProductNotFound)
        val variantId = command.variantId?.let { VariantId.constructFromPersisted(it) }

        return transactionManager.inTransaction {
            val card = productQmRepository.findCards(listOf(productId)).singleOrNull() ?: return@inTransaction Result.failure(Failure.ProductNotFound)
            val now = clock.instant()
            val standing = StockAlertDomainService.Standing(
                activeCount = stockAlertRepository.countActive(command.userId),
                madeSince = stockAlertRepository.createdSince(command.userId, now.minus(StockAlertPolicy.RATE_WINDOW)),
                alreadyActive = stockAlertRepository.activeExists(command.userId, productId, variantId),
            )
            val colors = StockAlertDomainService.ProductColors(productId, card.colors.map { StockAlertDomainService.ProductColors.Color(it.variantId, it.colorKey, it.available) })
            val alert = when (val made = stockAlertDomainService.subscribe(command.userId, colors, variantId, standing, now)) {
                is Result.Success -> made.value
                is Result.Failure -> return@inTransaction Result.failure(
                    if (made.failure is StockAlertDomainService.Failure.VariantNotFound) Failure.InvalidInput(listOf(made.failure)) else Failure.Refused(made.failure),
                )
            }
            try {
                stockAlertRepository.add(alert)
            } catch (e: UniqueTakenException) {
                logger.info("CreateStockAlertUc: ${e.message}")
                return@inTransaction Result.failure(Failure.Refused(StockAlertDomainService.Failure.AlreadySubscribed))
            }
            logger.info("CreateStockAlertUc: alert ${alert.id.value} of product ${productId.value} for user ${command.userId.value}")
            Result.success(stockAlerts.of(listOf(alert)).single())
        }
    }

    private companion object {
        const val SMS = "SMS"
    }

    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        /** Only SMS for now. */
        data object ChannelInvalid : Problem("CHANNEL_INVALID")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        /** Not in the shop: a draft, an archived product, or none at all. */
        data object ProductNotFound : Failure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)

        /** One of StockAlertDomainService's refusals, as it said it (409, 422 or 429). */
        data class Refused(val failure: ResultFailure) : Failure(failure.code, failure.kind, meta = failure.meta)
    }
}
