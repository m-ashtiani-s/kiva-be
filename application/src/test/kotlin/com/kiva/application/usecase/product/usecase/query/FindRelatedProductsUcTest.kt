package com.kiva.application.usecase.product.usecase.query

import com.kiva.application.util.UnusedColorRepository
import com.kiva.application.util.UnusedCategoryRepository
import com.kiva.application.util.UnusedProductQmRepository
import com.kiva.application.repository.ProductCardQm
import com.kiva.application.repository.ProductDetailQm
import com.kiva.application.repository.ProductFacetsQm
import com.kiva.application.repository.ProductFilter
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.repository.ProductRef
import com.kiva.application.repository.VariantQm
import com.kiva.application.util.MockTransactionManager
import com.kiva.application.util.factory.CategoryFactory
import com.kiva.application.util.factory.ColorFactory
import com.kiva.application.util.factory.ShopSettingsFactory
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The read model as far as related products need it: [related] for a product in the shop, null for any other. */
private class RelatedReadModel(var related: List<ProductCardQm>?) : UnusedProductQmRepository() {
    var asked: Pair<ProductId, Int>? = null

    override fun findRelated(productId: ProductId, limit: Int): List<ProductCardQm>? {
        asked = productId to limit
        return related
    }

    override fun findDetail(ref: ProductRef): ProductDetailQm? = error("not used here")

    override fun findVariant(variantId: VariantId): VariantQm? = error("not used here")

    override fun findTakenSkus(skus: Collection<Sku>): Set<Sku> = error("not used here")

    override fun countActiveByMaterial(): Map<Slug, Int> = error("not used here")

    override fun refreshSearchText(of: ProductQmRepository.NamedInSearch): Int = error("not used here")
}

class FindRelatedProductsUcTest {

    private val now = Instant.parse("2026-10-07T08:00:00Z")
    private val transactionManager = MockTransactionManager()

    private val hasti = ProductCardQm(
        productId = ProductId.constructFromPersisted(9),
        slug = Slug.constructFromPersisted("hasti-shoulder-bag"),
        sku = Sku.constructFromPersisted("KV-1009"),
        name = ProductName.constructFromPersisted("کیف دوشی هستی"),
        categorySlug = Slug.constructFromPersisted("shoulder"),
        bagType = BagType.HOBO,
        priceTag = PriceTag.constructFromPersisted(Money.constructFromPersisted(2_150_000), null),
        hasVideo = false,
        soldCount = 0,
        newUntil = null,
        maxPerOrder = MaxPerOrder.DEFAULT,
        colors = listOf(ProductCardQm.Color(VariantId.constructFromPersisted(901), ColorKey.constructFromPersisted("black"), isDefault = true, available = 2, price = Money.constructFromPersisted(1_450_000))),
    )
    private val readModel = RelatedReadModel(listOf(hasti))

    private val categories = object : UnusedCategoryRepository() {
        private val all = listOf(CategoryFactory.generate(id = 1, slug = "shoulder", name = "کیف دوشی"))
        override fun findAll(): List<Category> = all
        override fun findById(id: CategoryId): Category? = error("not used here")
    }
    private val colors = object : UnusedColorRepository() {
        private val palette = listOf(ColorFactory.generate(key = "black", name = "مشکی", hex = "#2B2930", isLight = false, sortOrder = 1))
        override fun findAll(): List<Color> = palette
    }

    private val useCase = FindRelatedProductsUc(
        transactionManager, readModel, categories, colors, Clock.fixed(now, ZoneOffset.UTC), ShopSettingsFactory.repositoryOf(),
    )

    @Test
    fun `related products are cards made as in the list, eight by default`() = runTest {
        val cards = useCase.execute(FindRelatedProductsUc.Qry("1")).getOrNull()!!

        assertEquals(listOf("hasti-shoulder-bag"), cards.map { it.slug })
        assertEquals(listOf("کیف دوشی", "LOW_STOCK"), cards.single().let { listOf(it.category.name, it.stock.status.name) })
        assertEquals(ProductId.constructFromPersisted(1) to 8, readModel.asked)
        assertEquals(1, transactionManager.transactionsOpened)
    }

    @Test
    fun `the limit asked for reaches the read model`() = runTest {
        useCase.execute(FindRelatedProductsUc.Qry("۱", limit = "24"))

        assertEquals(ProductId.constructFromPersisted(1) to 24, readModel.asked)
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "25", "eight", "-1"])
    fun `a limit outside 1 to 24 is LIMIT_INVALID on limit, before any transaction`(limit: String) = runTest {
        val failure = useCase.execute(FindRelatedProductsUc.Qry("1", limit)).failureOrNull()!!

        assertEquals(listOf("limit" to "LIMIT_INVALID"), failure.causes.map { it.field to it.code })
        assertEquals(0, transactionManager.transactionsOpened)
    }

    @Test
    fun `a product not in the shop is PRODUCT_NOT_FOUND`() = runTest {
        readModel.related = null

        assertEquals(Result.failure(FindRelatedProductsUc.Failure.ProductNotFound), useCase.execute(FindRelatedProductsUc.Qry("1")))
    }

    @Test
    fun `a product number that can name no product is PRODUCT_NOT_FOUND without asking anyone`() = runTest {
        assertEquals(Result.failure(FindRelatedProductsUc.Failure.ProductNotFound), useCase.execute(FindRelatedProductsUc.Qry("mahak")))
        assertNull(readModel.asked)
    }
}
