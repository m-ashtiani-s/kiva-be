package com.kiva.application.usecase.product.usecase.query

import com.kiva.application.dto.ProductInquiryDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.repository.ProductRef
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.railway.unwrapOr
import com.kiva.common.text.normalizeDigits
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.settings.model.valueobject.SocialChannel
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository

/**
 * «سؤال داری؟» of a product page (`GET /products/{productId}/inquiry`, public; roadmap section 15.2, decision 18): the
 * product in the shop and the color asked about (the default one when none is), and the shop's messengers that take a
 * prefilled text, in the settings' order. The text and the links are the HTTP layer's (ProductTexts.inquiryText).
 *
 * A product not in the shop is PRODUCT_NOT_FOUND, as for its page; a color it doesn't come in now is 400 COLOR_NOT_FOUND
 * on `color` (unlike the page, which falls back: here the text would name a color the shop doesn't sell).
 */
class GetProductInquiryUc(
    private val transactionManager: TransactionManager,
    private val productQmRepository: ProductQmRepository,
    private val colorRepository: ColorRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
) : QueryUseCase<GetProductInquiryUc.Qry, ProductInquiryDto, GetProductInquiryUc.Failure> {

    private val logger = getLogger()

    /** The path segment and `?color=` as they arrived. */
    data class Qry(val productId: String, val color: String? = null)

    override suspend fun execute(query: Qry): Result<ProductInquiryDto, Failure> {
        logger.info("GetProductInquiryUc.execute productId=${query.productId} color=${query.color}")

        // A malformed color is the client's mistake (400); an empty one is no color.
        val asked = query.color?.trim()?.ifEmpty { null }?.let { raw ->
            ColorKey.makeNew(raw).forField("color").unwrapOr { return Result.failure(Failure.InvalidInput(listOf(it))) }
        }
        // A path that can't be a product's number names none: not found, before any transaction.
        val productId = query.productId.trim().normalizeDigits().toLongOrNull()?.let { ProductId.makeNew(it).getOrNull() }
            ?: return Result.failure(Failure.ProductNotFound)

        return transactionManager.inTransaction {
            val product = productQmRepository.findDetail(ProductRef.ById(productId)) ?: return@inTransaction Result.failure(Failure.ProductNotFound)
            val variant = if (asked == null) {
                // A product goes into the shop only with an active color, and its last one can't be taken out.
                product.variants.firstOrNull { it.isDefault } ?: product.variants.firstOrNull() ?: error("Product ${productId.value} is in the shop without a color")
            } else {
                product.variants.firstOrNull { it.colorKey == asked }
                    ?: return@inTransaction Result.failure(Failure.InvalidInput(listOf(FieldFailure("color", Failure.ColorNotFound))))
            }
            val color = colorRepository.findByKey(variant.colorKey) ?: error("No color ${variant.colorKey.value} in the palette")
            val settings = shopSettingsRepository.get()
            Result.success(
                ProductInquiryDto(
                    productCode = product.card.sku.value,
                    productName = product.card.name.value,
                    colorName = color.name.value,
                    channels = settings.social.filter { it.channel in PREFILLED },
                    supportHours = settings.supportHours,
                ),
            )
        }
    }

    companion object {
        /** The messengers whose link takes a text; Instagram's doesn't (decision 18). */
        val PREFILLED = setOf(SocialChannel.RUBIKA, SocialChannel.TELEGRAM, SocialChannel.BALE)
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        /** `color` is not a color key, or not one of the product's colors (400 VALIDATION_ERROR on `color`). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object ColorNotFound : Failure("COLOR_NOT_FOUND", FailureKind.INVALID)

        data object ProductNotFound : Failure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
