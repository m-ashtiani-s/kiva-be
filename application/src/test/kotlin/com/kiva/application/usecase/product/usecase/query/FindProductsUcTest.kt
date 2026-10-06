package com.kiva.application.usecase.product.usecase.query

import com.kiva.application.dto.PriceDto
import com.kiva.application.dto.ProductCardDto
import com.kiva.application.repository.PriceRange
import com.kiva.application.repository.ProductCardQm
import com.kiva.application.repository.ProductFilter
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.repository.VariantQm
import com.kiva.application.util.MockTransactionManager
import com.kiva.application.util.factory.CategoryFactory
import com.kiva.application.util.factory.ColorFactory
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.product.model.valueobject.BadgeCode
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.Occasion
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.StockInfo
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The read model as far as the list needs it: hands back [cards] and remembers what it was asked. */
private class ListReadModel(var cards: List<ProductCardQm> = emptyList()) : ProductQmRepository {
    var askedFilter: ProductFilter? = null
    var askedPageable: Pageable? = null

    override fun find(filter: ProductFilter, pageable: Pageable): Page<ProductCardQm> {
        askedFilter = filter
        askedPageable = pageable
        return Page(cards, pageable.page, pageable.size, cards.size.toLong())
    }

    override fun findVariant(variantId: VariantId): VariantQm? = error("not used here")

    override fun isSlugTaken(slug: Slug): Boolean = error("not used here")

    override fun findTakenSkus(skus: Collection<Sku>): Set<Sku> = error("not used here")

    override fun countActiveByCategory(): Map<Slug, Int> = error("not used here")

    override fun countActiveByMaterial(): Map<Slug, Int> = error("not used here")

    override fun countActiveByColor(): Map<ColorKey, Int> = error("not used here")
}

class FindProductsUcTest {

    private val now = Instant.parse("2026-10-07T08:00:00Z")
    private val transactionManager = MockTransactionManager()
    private val readModel = ListReadModel()

    private val categories = object : CategoryRepository {
        private val all = listOf(CategoryFactory.generate(id = 1, slug = "shoulder", name = "کیف دوشی"))
        override fun findAll(): List<Category> = all
        override fun findBySlug(slug: Slug): Category? = error("not used here")
        override fun findById(id: CategoryId): Category? = error("not used here")
    }
    private val colors = object : ColorRepository {
        private val palette = listOf(
            ColorFactory.generate(key = "lilac", name = "یاسی", hex = "#C8B6E2", isLight = true, sortOrder = 4),
            ColorFactory.generate(key = "black", name = "مشکی", hex = "#2B2930", isLight = false, sortOrder = 1),
        )
        override fun findAll(): List<Color> = palette
        override fun findByKey(key: ColorKey): Color? = error("not used here")
    }

    private val useCase = FindProductsUc(
        transactionManager, readModel, categories, colors, Clock.fixed(now, ZoneOffset.UTC), Money.constructFromPersisted(3_000_000),
    )

    private val firstPage = Pageable.makeNew(1, 24, ProductSorts.NEWEST, ProductSorts.names, 60).getOrNull()!!

    private fun money(toman: Long) = Money.constructFromPersisted(toman)
    private fun priceTag(price: Long, compareAt: Long? = null) = PriceTag.constructFromPersisted(money(price), compareAt?.let(::money))

    /** The field paths and codes a client would get, the way the HTTP layer builds them. */
    private fun fieldErrors(failure: ResultFailure, parent: String? = null): List<String> {
        val path = listOfNotNull(parent, failure.field).joinToString(".").ifEmpty { null }
        if (failure.causes.isNotEmpty()) return failure.causes.flatMap { fieldErrors(it, path) }
        return listOf("$path ${failure.code}")
    }

    @Nested
    inner class Filtering {
        @Test
        fun `nothing asked narrows nothing, and the read model gets the page as it came`() = runTest {
            useCase.execute(FindProductsUc.Qry(firstPage))

            assertEquals(ProductFilter(), readModel.askedFilter)
            assertEquals(firstPage, readModel.askedPageable)
            assertEquals(1, transactionManager.transactionsOpened)
        }

        @Test
        fun `every parameter reaches the read model checked, in its own type`() = runTest {
            useCase.execute(
                FindProductsUc.Qry(
                    pageable = firstPage,
                    q = "  كيف   دوشي  Mahak ",
                    categories = listOf("shoulder", "cross"),
                    colors = listOf("lilac", "black", "lilac"),
                    minPrice = "۵۰۰۰۰۰",
                    maxPrice = " 2500000 ",
                    onSale = true,
                    inStock = true,
                    isNew = true,
                    hasVideo = true,
                    bagTypes = listOf("HOBO"),
                    materials = listOf("satin"),
                    occasions = listOf("PARTY", "DAILY"),
                    ids = listOf("12", "۷"),
                ),
            )

            assertEquals(
                ProductFilter(
                    // Arabic letters made Persian, Latin made lowercase: as the read model's searchText.
                    words = listOf("کیف", "دوشی", "mahak"),
                    categories = setOf(Slug.constructFromPersisted("shoulder"), Slug.constructFromPersisted("cross")),
                    colors = setOf(ColorKey.constructFromPersisted("lilac"), ColorKey.constructFromPersisted("black")),
                    price = PriceRange.makeNew(money(500_000), money(2_500_000)).getOrNull()!!,
                    onSale = true,
                    inStock = true,
                    newAt = now,
                    hasVideo = true,
                    bagTypes = setOf(BagType.HOBO),
                    materials = setOf(Slug.constructFromPersisted("satin")),
                    occasions = setOf(Occasion.PARTY, Occasion.DAILY),
                    ids = listOf(ProductId.constructFromPersisted(12), ProductId.constructFromPersisted(7)),
                ),
                readModel.askedFilter,
            )
        }

        @Test
        fun `empty price parameters are no price filter`() = runTest {
            useCase.execute(FindProductsUc.Qry(firstPage, minPrice = "", maxPrice = "  "))

            assertEquals(PriceRange.ANY, readModel.askedFilter?.price)
        }
    }

    @Nested
    inner class Refusing {
        @Test
        fun `minPrice above maxPrice is PRICE_RANGE_INVALID on maxPrice`() = runTest {
            val failure = useCase.execute(FindProductsUc.Qry(firstPage, minPrice = "900", maxPrice = "100")).failureOrNull()!!

            assertEquals("VALIDATION_ERROR", failure.code)
            assertEquals(listOf("maxPrice PRICE_RANGE_INVALID"), fieldErrors(failure))
            assertEquals(0, transactionManager.transactionsOpened)
        }

        @Test
        fun `every wrong parameter is reported at once under its name, before any transaction`() = runTest {
            val wrong = FindProductsUc.Qry(
                pageable = firstPage,
                q = "ک".repeat(101),
                categories = listOf("shoulder", "Shoulder Bags"),
                colors = listOf("Lilac"),
                minPrice = "ارزان",
                maxPrice = "-5",
                bagTypes = listOf("SHOULDER"),
                materials = listOf("satin"),
                occasions = listOf("PICNIC"),
                ids = listOf("7", "x"),
            )

            val failure = useCase.execute(wrong).failureOrNull()!!

            assertEquals(
                listOf(
                    "q SEARCH_QUERY_TOO_LONG",
                    "ids NUMBER_INVALID",
                    "category SLUG_INVALID",
                    "color COLOR_KEY_INVALID",
                    "bagType BAG_TYPE_INVALID",
                    "occasion OCCASION_INVALID",
                    "minPrice NUMBER_INVALID",
                    "maxPrice MONEY_NEGATIVE",
                ),
                fieldErrors(failure),
            )
            assertNull(readModel.askedFilter)
            assertEquals(0, transactionManager.transactionsOpened)
        }

        @Test
        fun `more ids than the contract allows is IDS_TOO_MANY`() = runTest {
            val failure = useCase.execute(FindProductsUc.Qry(firstPage, ids = List(61) { "${it + 1}" })).failureOrNull()!!

            assertEquals(listOf("ids IDS_TOO_MANY"), fieldErrors(failure))
        }
    }

    @Nested
    inner class Cards {
        private fun card(
            colors: List<ProductCardQm.Color>,
            priceTag: PriceTag = priceTag(1_890_000),
            hasVideo: Boolean = false,
            newUntil: Instant? = null,
        ) = ProductCardQm(
            productId = ProductId.constructFromPersisted(1),
            slug = Slug.constructFromPersisted("mahak-shoulder-bag"),
            sku = Sku.constructFromPersisted("KV-1001"),
            name = ProductName.constructFromPersisted("کیف دوشی ماهک"),
            categorySlug = Slug.constructFromPersisted("shoulder"),
            bagType = BagType.HOBO,
            priceTag = priceTag,
            hasVideo = hasVideo,
            soldCount = 184,
            newUntil = newUntil,
            maxPerOrder = MaxPerOrder.DEFAULT,
            colors = colors,
        )

        private fun color(variantId: Long, key: String, available: Int, isDefault: Boolean = false) =
            ProductCardQm.Color(VariantId.constructFromPersisted(variantId), ColorKey.constructFromPersisted(key), isDefault, available)

        private suspend fun only(qm: ProductCardQm): ProductCardDto {
            readModel.cards = listOf(qm)
            return useCase.execute(FindProductsUc.Qry(firstPage)).getOrNull()!!.items.single()
        }

        @Test
        fun `a card shows the default color's price, the whole product's stock and each color's own status`() = runTest {
            val dto = only(card(listOf(color(101, "lilac", available = 0, isDefault = true), color(102, "black", available = 3)), priceTag(1_450_000, 1_750_000)))

            assertEquals(
                ProductCardDto(
                    id = 1,
                    slug = "mahak-shoulder-bag",
                    sku = "KV-1001",
                    name = "کیف دوشی ماهک",
                    category = ProductCardDto.CategoryRef(1, "shoulder", "کیف دوشی"),
                    bagType = BagType.HOBO,
                    price = PriceDto(1_450_000, 1_750_000, discountPercent = 17, discountAmount = 300_000),
                    colors = listOf(
                        ProductCardDto.ColorOption(101, "lilac", "یاسی", "#C8B6E2", isLight = true, sortOrder = 4, StockStatus.OUT_OF_STOCK),
                        ProductCardDto.ColorOption(102, "black", "مشکی", "#2B2930", isLight = false, sortOrder = 1, StockStatus.LOW_STOCK),
                    ),
                    defaultColorKey = "lilac",
                    // The lilac is gone, but 3 black bags can still be bought: not sold out, and few left.
                    stock = StockInfo(StockStatus.LOW_STOCK, shownQuantity = 3, maxOrderQuantity = 3),
                    badges = listOf(BadgeCode.SALE, BadgeCode.LOW_STOCK),
                    isNew = false,
                    hasVideo = false,
                    soldCount = 184,
                    freeShippingEligible = false,
                ),
                dto,
            )
        }

        @Test
        fun `plenty left shows no number, and the cart's limit is the product's own`() = runTest {
            val dto = only(card(listOf(color(101, "lilac", available = 12, isDefault = true), color(102, "black", available = 9))))

            assertEquals(StockInfo(StockStatus.IN_STOCK, shownQuantity = null, maxOrderQuantity = MaxPerOrder.DEFAULT.value), dto.stock)
            assertEquals(listOf(StockStatus.IN_STOCK, StockStatus.IN_STOCK), dto.colors.map { it.stockStatus })
        }

        @Test
        fun `a new bag on sale, with a video and above the free-shipping price wears every badge, in the contract's order`() = runTest {
            val dto = only(
                card(
                    listOf(color(101, "lilac", available = 20, isDefault = true)),
                    priceTag = priceTag(3_150_000, 3_500_000),
                    hasVideo = true,
                    newUntil = now.plusSeconds(1),
                ),
            )

            assertEquals(listOf(BadgeCode.NEW, BadgeCode.SALE, BadgeCode.HAS_VIDEO, BadgeCode.FREE_SHIPPING), dto.badges)
            assertEquals(listOf(true, true), listOf(dto.isNew, dto.freeShippingEligible))
        }

        @Test
        fun `a sold-out bag says so, and drops the badges that invite buying`() = runTest {
            val dto = only(card(listOf(color(101, "lilac", available = 0, isDefault = true)), priceTag = priceTag(3_150_000, 3_500_000), newUntil = now))

            // newUntil is not after now: no longer new.
            assertEquals(listOf(BadgeCode.SOLD_OUT), dto.badges)
            assertEquals(StockInfo(StockStatus.OUT_OF_STOCK, null, 0), dto.stock)
        }
    }
}
