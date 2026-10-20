package com.kiva.application.usecase.product.usecase.query

import com.kiva.application.dto.ActorNameDto
import com.kiva.application.dto.AdminProductDto
import com.kiva.application.dto.AdminVariantDto
import com.kiva.application.dto.ProductDetailDto
import com.kiva.application.usecase.product.ProductCards
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock

/**
 * One product for the panel (`GET /admin/products/{productId}`; ADMIN, CATALOG_MANAGER), whatever its status, and the
 * body of every product write (the routes read it right after the command).
 *
 * The shop's part is made exactly as the shop's page makes it (ProductCards): only active colors, the shop's stock
 * rule; a product without an active color has no default color and is sold out. [AdminProductDto.variants] has every
 * color with the exact stock. `createdBy` is the creator's account as it is now. Nobody (a missing product or an id
 * that is not a positive number) is 404 PRODUCT_NOT_FOUND.
 */
class GetAdminProductUc(
    private val transactionManager: TransactionManager,
    private val productQmRepository: ProductQmRepository,
    private val categoryRepository: CategoryRepository,
    private val materialRepository: MaterialRepository,
    private val colorRepository: ColorRepository,
    private val userRepository: UserRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val clock: Clock,
) : QueryUseCase<GetAdminProductUc.Qry, AdminProductDto, GetAdminProductUc.Failure> {

    private val logger = getLogger()

    /** @property productId the path's `{productId}` as it came. */
    data class Qry(val productId: String)

    override suspend fun execute(query: Qry): Result<AdminProductDto, Failure> {
        logger.info("GetAdminProductUc.execute product=${query.productId}")
        val id = query.productId.trim().toLongOrNull()?.let { ProductId.makeNew(it).getOrNull() } ?: return Result.failure(Failure.ProductNotFound)
        val now = clock.instant()

        return transactionManager.inTransaction {
            val product = productQmRepository.findAdminDetail(id) ?: return@inTransaction Result.failure(Failure.ProductNotFound)
            val detail = product.detail

            // The read model's foreign keys keep these lookups whole.
            val category = categoryRepository.findBySlug(detail.card.categorySlug) ?: error("No category ${detail.card.categorySlug.value}")
            val material = materialRepository.findBySlug(detail.materialSlug) ?: error("No material ${detail.materialSlug.value}")
            val palette = colorRepository.findAll().associateBy { it.key }
            val maxPerOrder = detail.card.maxPerOrder
            val settings = shopSettingsRepository.get()
            val card = ProductCards.cardOf(detail.card, category, palette, now, settings.freeShippingThreshold, settings.lowStockThreshold)

            val shopPart = ProductDetailDto(
                card = card,
                selectedColorKey = card.defaultColorKey,
                shortDescription = detail.shortDescription?.value,
                description = detail.description?.value,
                highlights = detail.highlights.items,
                material = ProductDetailDto.MaterialRef(material.id.value, material.slug.value, material.name.value, material.family),
                materialDescription = detail.materialDescription?.value,
                specs = detail.specs,
                variants = detail.variants.map {
                    ProductCards.variantOf(
                        it.variantId, it.sku, it.colorKey, it.priceTag, it.isDefault, it.available, it.media, palette, maxPerOrder, settings.lowStockThreshold,
                    )
                },
                returnable = detail.returnable,
                reservable = detail.reservable,
                maxPerOrder = maxPerOrder.value,
                publishedAt = detail.publishedOn,
                updatedAt = detail.updatedOn,
            )
            Result.success(
                AdminProductDto(
                    detail = shopPart,
                    status = product.status,
                    variants = product.variants.map {
                        AdminVariantDto(
                            shop = ProductCards.variantOf(
                                it.variantId, it.sku, it.colorKey, it.priceTag, it.isDefault, it.available, it.media, palette, maxPerOrder, settings.lowStockThreshold,
                            ),
                            barcode = it.barcode,
                            isActive = it.isActive,
                            stockOnHand = it.onHand,
                            stockReserved = it.reserved,
                            soldCount = it.soldCount,
                            // Stock alerts come in phase 10.
                            stockAlertSubscribers = 0,
                        )
                    },
                    newUntil = detail.card.newUntil,
                    sortPriority = product.sortPriority,
                    publishAt = product.publishAt,
                    tags = product.tags,
                    createdAt = product.createdOn,
                    createdBy = ActorNameDto.of(product.createdBy, product.createdBy.takeIf { it != UserId.SYSTEM }?.let(userRepository::findById)),
                    version = product.version,
                ),
            )
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object ProductNotFound : Failure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
