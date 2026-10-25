package com.kiva.application.usecase.product.usecase.query

import com.kiva.application.dto.GalleryItemDto
import com.kiva.application.dto.PriceDto
import com.kiva.application.dto.ProductDetailDto
import com.kiva.application.dto.UploadedMediaDto
import com.kiva.application.repository.ProductCardQm
import com.kiva.application.repository.ProductDetailQm
import com.kiva.application.repository.ProductFacetsQm
import com.kiva.application.repository.ProductFilter
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.repository.ProductRef
import com.kiva.application.repository.VariantQm
import com.kiva.application.util.MockTransactionManager
import com.kiva.application.util.UnusedCategoryRepository
import com.kiva.application.util.UnusedColorRepository
import com.kiva.application.util.UnusedMaterialRepository
import com.kiva.application.util.UnusedProductQmRepository
import com.kiva.application.util.factory.CategoryFactory
import com.kiva.application.util.factory.ColorFactory
import com.kiva.application.util.factory.MaterialFactory
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
import com.kiva.domain.aggregate.material.entity.Material
import com.kiva.domain.aggregate.material.model.valueobject.MaterialFamily
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.Highlights
import com.kiva.domain.aggregate.product.model.valueobject.MaterialDescription
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductDescription
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.ProductSpecs
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.StockInfo
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The read model as far as a page needs it: hands back [product] and remembers how it was asked for. */
private class PageReadModel(var product: ProductDetailQm? = null) : UnusedProductQmRepository() {
    var askedRef: ProductRef? = null

    override fun findDetail(ref: ProductRef): ProductDetailQm? {
        askedRef = ref
        return product
    }

    override fun findVariant(variantId: VariantId): VariantQm? = error("not used here")

    override fun findRelated(productId: ProductId, limit: Int): List<ProductCardQm>? = error("not used here")

    override fun findTakenSkus(skus: Collection<Sku>): Set<Sku> = error("not used here")

    override fun countActiveByMaterial(): Map<Slug, Int> = error("not used here")

    override fun refreshSearchText(of: ProductQmRepository.NamedInSearch): Int = error("not used here")
}

class GetProductUcTest {

    private val now = Instant.parse("2026-10-07T08:00:00Z")
    private val transactionManager = MockTransactionManager()
    private val readModel = PageReadModel()

    private val categories = object : UnusedCategoryRepository() {
        private val shoulder = CategoryFactory.generate(id = 1, slug = "shoulder", name = "کیف دوشی")
        override fun findAll(): List<Category> = listOf(shoulder)
        override fun findBySlug(slug: Slug): Category? = shoulder.takeIf { it.slug == slug }
    }
    private val materials = object : UnusedMaterialRepository() {
        private val matte = MaterialFactory.generate(id = 4, slug = "faux-leather-matte", name = "چرم مصنوعی مات", family = MaterialFamily.FAUX_LEATHER)
        override fun findAll(): List<Material> = listOf(MaterialFactory.generate(id = 1), matte)
    }
    private val colors = object : UnusedColorRepository() {
        private val palette = listOf(
            ColorFactory.generate(key = "lilac", name = "یاسی", hex = "#C8B6E2", isLight = true, sortOrder = 4),
            ColorFactory.generate(key = "black", name = "مشکی", hex = "#2B2930", isLight = false, sortOrder = 1),
        )
        override fun findAll(): List<Color> = palette
    }

    private val useCase = GetProductUc(
        transactionManager, readModel, categories, materials, colors, Clock.fixed(now, ZoneOffset.UTC), ShopSettingsFactory.repositoryOf(),
    )

    private fun money(toman: Long) = Money.constructFromPersisted(toman)
    private fun priceTag(price: Long, compareAt: Long? = null) = PriceTag.constructFromPersisted(money(price), compareAt?.let(::money))

    private fun item(id: String, type: MediaType, view: MediaView, alt: String?) = GalleryItemDto(
        UploadedMediaDto(id, type, "image/webp", "p/1/$id/1600.webp", "p/1/$id/320.webp", emptyList(), null, 1600, 1200, null, alt, null),
        view,
    )

    private val photo = item("med_0000000000000000000001", MediaType.IMAGE, MediaView.FRONT, "نمای جلو")
    private val video = item("med_0000000000000000000002", MediaType.VIDEO, MediaView.SIDE, null)

    private val lilac = ProductDetailQm.Variant(
        VariantId.constructFromPersisted(101), ColorKey.constructFromPersisted("lilac"), Sku.constructFromPersisted("KV-1001-LILAC"),
        priceTag(1_450_000, 1_750_000), isDefault = true, available = 12, media = listOf(photo, video),
    )
    private val black = ProductDetailQm.Variant(
        VariantId.constructFromPersisted(102), ColorKey.constructFromPersisted("black"), Sku.constructFromPersisted("KV-1001-BLACK"),
        priceTag(1_890_000), isDefault = false, available = 2, media = emptyList(),
    )

    private val mahak = ProductDetailQm(
        card = ProductCardQm(
            productId = ProductId.constructFromPersisted(1),
            slug = Slug.constructFromPersisted("mahak-shoulder-bag"),
            sku = Sku.constructFromPersisted("KV-1001"),
            name = ProductName.constructFromPersisted("کیف دوشی ماهک"),
            categorySlug = Slug.constructFromPersisted("shoulder"),
            bagType = BagType.HOBO,
            priceTag = priceTag(1_450_000, 1_750_000),
            hasVideo = true,
            soldCount = 0,
            newUntil = null,
            maxPerOrder = MaxPerOrder.DEFAULT,
            colors = listOf(lilac, black).map { ProductCardQm.Color(it.variantId, it.colorKey, it.isDefault, it.available, Money.constructFromPersisted(1_450_000)) },
        ),
        materialSlug = Slug.constructFromPersisted("faux-leather-matte"),
        materialDescription = MaterialDescription.constructFromPersisted("چرم مصنوعی مات درجه یک"),
        shortDescription = null,
        description = ProductDescription.constructFromPersisted("<p>کیف دوشی جادار</p>"),
        highlights = Highlights.constructFromPersisted(listOf("یراق‌آلات فلزی ضدزنگ")),
        specs = ProductSpecs.NONE,
        returnable = true,
        reservable = false,
        publishedOn = Instant.parse("2026-10-06T08:00:00Z"),
        updatedOn = Instant.parse("2026-10-06T09:00:00Z"),
        variants = listOf(lilac, black),
    )

    @Test
    fun `a page is the card as in the list, then the colors each with their own price, stock and gallery`() = runTest {
        readModel.product = mahak

        val page = useCase.execute(GetProductUc.Qry("mahak-shoulder-bag")).getOrNull()!!

        assertEquals("lilac", page.selectedColorKey)
        assertEquals(StockInfo(StockStatus.IN_STOCK, null, 5), page.card.stock)
        assertEquals(
            listOf(
                ProductDetailDto.Variant(
                    101, "KV-1001-LILAC", ProductDetailDto.Swatch("lilac", "یاسی", "#C8B6E2", true, 4), isDefault = true,
                    PriceDto(1_450_000, 1_750_000, 17, 300_000), StockInfo(StockStatus.IN_STOCK, null, 5), listOf(photo, video), hasVideo = true,
                    stockAlertSubscribed = false, // a guest waits for nothing (phase 10)
                ),
                // Two left of black: only now is the number shown.
                ProductDetailDto.Variant(
                    102, "KV-1001-BLACK", ProductDetailDto.Swatch("black", "مشکی", "#2B2930", false, 1), isDefault = false,
                    PriceDto(1_890_000, null, 0, 0), StockInfo(StockStatus.LOW_STOCK, 2, 2), emptyList(), hasVideo = false, stockAlertSubscribed = false,
                ),
            ),
            page.variants,
        )
        assertEquals(
            listOf<Any?>(
                ProductDetailDto.MaterialRef(4, "faux-leather-matte", "چرم مصنوعی مات", MaterialFamily.FAUX_LEATHER), "چرم مصنوعی مات درجه یک",
                null, "<p>کیف دوشی جادار</p>", listOf("یراق‌آلات فلزی ضدزنگ"), true, false, 5,
                Instant.parse("2026-10-06T08:00:00Z"), Instant.parse("2026-10-06T09:00:00Z"),
            ),
            listOf(
                page.material, page.materialDescription, page.shortDescription, page.description, page.highlights, page.returnable, page.reservable,
                page.maxPerOrder, page.publishedAt, page.updatedAt,
            ),
        )
        assertEquals(1, transactionManager.transactionsOpened)
    }

    @Test
    fun `the reference reaches the read model as what it is`() = runTest {
        readModel.product = mahak

        useCase.execute(GetProductUc.Qry("KV-1001"))

        assertEquals(ProductRef.BySku(Sku.constructFromPersisted("KV-1001")), readModel.askedRef)
    }

    @Test
    fun `the color asked for is shown first, and one the product doesn't come in falls back to the default`() = runTest {
        readModel.product = mahak

        val black = useCase.execute(GetProductUc.Qry("1", color = "black")).getOrNull()!!
        val white = useCase.execute(GetProductUc.Qry("1", color = "white")).getOrNull()!!
        val empty = useCase.execute(GetProductUc.Qry("1", color = " ")).getOrNull()!!

        assertEquals(listOf("black", "lilac", "lilac"), listOf(black.selectedColorKey, white.selectedColorKey, empty.selectedColorKey))
    }

    @Test
    fun `a malformed color is VALIDATION_ERROR on color, before any transaction`() = runTest {
        val failure = useCase.execute(GetProductUc.Qry("1", color = "Lilac")).failureOrNull()!!

        assertEquals("VALIDATION_ERROR", failure.code)
        assertEquals(listOf("color" to "COLOR_KEY_INVALID"), failure.causes.map { it.field to it.code })
        assertEquals(0, transactionManager.transactionsOpened)
    }

    @Test
    fun `a product the read model doesn't have in the shop is PRODUCT_NOT_FOUND`() = runTest {
        readModel.product = null

        assertEquals(Result.failure(GetProductUc.Failure.ProductNotFound), useCase.execute(GetProductUc.Qry("mahak-shoulder-bag")))
    }

    @Test
    fun `a reference that can name no product is PRODUCT_NOT_FOUND without asking anyone`() = runTest {
        assertEquals(Result.failure(GetProductUc.Failure.ProductNotFound), useCase.execute(GetProductUc.Qry("Mahak Bag")))
        assertNull(readModel.askedRef)
        assertEquals(0, transactionManager.transactionsOpened)
    }
}
