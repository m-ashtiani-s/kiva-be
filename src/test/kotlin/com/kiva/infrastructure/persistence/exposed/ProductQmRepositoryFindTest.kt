package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.repository.PriceRange
import com.kiva.application.repository.ProductCardQm
import com.kiva.application.repository.ProductFilter
import com.kiva.application.repository.ProductQmRepository.NamedInSearch
import com.kiva.application.usecase.product.usecase.query.ProductSorts
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Quantity
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.Occasion
import com.kiva.domain.aggregate.product.model.valueobject.ProductDetails
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.priceTag
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.persistence.exposed.tbl.CategoryTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ColorTbl
import com.kiva.infrastructure.persistence.exposed.tbl.MaterialTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/** The shop's list query on kiva_test, over products saved through the real repositories (so the read model is real). */
class ProductQmRepositoryFindTest {

    private val db = TestDatabase.database
    private val repository = ProductQmRepositoryImpl()
    private val catalog = StoredCatalog()
    private val staff = catalog.staff
    private val now = catalog.now

    @AfterEach
    fun deleteWhatWasStored() = catalog.deleteAll()

    private fun pageable(sort: String = ProductSorts.NEWEST, page: Int = 1, size: Int = 24) =
        Pageable.makeNew(page, size, sort, ProductSorts.names, maxSize = 60).getOrNull()!!

    private fun find(filter: ProductFilter = ProductFilter(), pageable: Pageable = pageable()): Page<ProductCardQm> =
        transaction(db) { repository.find(filter, pageable) }

    private fun slugsOf(filter: ProductFilter = ProductFilter(), sort: String = ProductSorts.NEWEST): List<String> =
        find(filter, pageable(sort)).items.map { it.slug.value }

    /** A product saved and published [at] that moment, so it is in the shop. */
    private fun inShop(
        slug: String,
        colors: List<NewColor> = listOf(NewColor("lilac")),
        price: Long = 1_750_000,
        compareAt: Long? = null,
        details: ProductDetails = ProductEventSamples.fullDetails,
        at: Instant = now,
    ): Product {
        val product = catalog.newProduct(colors, priceTag(price, compareAt), details, slug, at)
        return catalog.change(product.id) { it.publish(staff, at) }
    }

    private fun Product.color(key: String): VariantId = variants.single { it.colorKey.value == key }.id

    private fun restock(variantId: VariantId, bags: Int) =
        catalog.stock(variantId) { it.adjust(StockDelta.constructFromPersisted(bags), AdjustmentReason.RESTOCK, null, staff, now) }

    // ============ what is listed ============

    @Test
    fun `only products in the shop are listed - never a draft, never an archived one`() {
        inShop("in-the-shop")
        catalog.newProduct(listOf(NewColor("lilac")), slug = "a-draft")
        val archived = inShop("archived-one")
        catalog.change(archived.id) { it.archive(staff, now) }

        assertEquals(listOf("in-the-shop"), slugsOf())
    }

    @Test
    fun `each filter narrows the list to what it names`() {
        // A shoulder bag in lilac and black, 3 lilac in stock, new until 2026-10-20, for daily and work.
        val shoulder = inShop("mahak-shoulder-bag", listOf(NewColor("lilac"), NewColor("black")))
        restock(shoulder.color("lilac"), 3)
        // A cream wallet on sale, with a video, sold out, never new, for no occasion in particular.
        inShop(
            "simple-wallet",
            listOf(NewColor("cream", media = listOf(ProductEventSamples.video))),
            price = 590_000,
            compareAt = 790_000,
            details = ProductEventSamples.bareDetails,
        )

        fun slug(value: String) = Slug.constructFromPersisted(value)
        fun color(key: String) = ColorKey.constructFromPersisted(key)
        fun money(toman: Long) = Money.constructFromPersisted(toman)

        assertEquals(listOf("simple-wallet"), slugsOf(ProductFilter(categories = setOf(slug("wallet")))))
        assertEquals(listOf("mahak-shoulder-bag", "simple-wallet"), slugsOf(ProductFilter(colors = setOf(color("black"), color("cream"))), ProductSorts.PRICE_DESC))
        assertEquals(emptyList(), slugsOf(ProductFilter(colors = setOf(color("white")))))
        assertEquals(listOf("mahak-shoulder-bag"), slugsOf(ProductFilter(price = PriceRange.makeNew(money(600_000), money(1_750_000)).getOrNull()!!)))
        assertEquals(listOf("simple-wallet"), slugsOf(ProductFilter(price = PriceRange.makeNew(null, money(590_000)).getOrNull()!!)))
        assertEquals(listOf("simple-wallet"), slugsOf(ProductFilter(onSale = true)))
        assertEquals(listOf("mahak-shoulder-bag"), slugsOf(ProductFilter(inStock = true)))
        assertEquals(listOf("mahak-shoulder-bag"), slugsOf(ProductFilter(newAt = Instant.parse("2026-10-10T00:00:00Z"))))
        assertEquals(emptyList(), slugsOf(ProductFilter(newAt = Instant.parse("2026-10-20T00:00:00Z"))))
        assertEquals(listOf("simple-wallet"), slugsOf(ProductFilter(hasVideo = true)))
        assertEquals(listOf("simple-wallet"), slugsOf(ProductFilter(bagTypes = setOf(BagType.WALLET))))
        assertEquals(listOf("simple-wallet"), slugsOf(ProductFilter(materials = setOf(slug("genuine-cow-leather")))))
        assertEquals(listOf("mahak-shoulder-bag"), slugsOf(ProductFilter(occasions = setOf(Occasion.WORK, Occasion.PARTY))))
        // Filters together: all of them apply.
        assertEquals(emptyList(), slugsOf(ProductFilter(categories = setOf(slug("shoulder")), onSale = true)))
    }

    @Test
    fun `search finds every word somewhere in name, category, colors or material, and a % is just a character`() {
        inShop("mahak-shoulder-bag", listOf(NewColor("lilac")))
        inShop("simple-wallet", listOf(NewColor("cream")), details = ProductEventSamples.bareDetails)

        fun search(vararg words: String) = slugsOf(ProductFilter(words = words.toList()), ProductSorts.PRICE_DESC)

        assertEquals(listOf("mahak-shoulder-bag", "simple-wallet"), search("کیف"))
        assertEquals(listOf("simple-wallet"), search("کیف", "پول"))
        assertEquals(listOf("mahak-shoulder-bag"), search("یاسی"))
        assertEquals(listOf("simple-wallet"), search("گاوی"))
        assertEquals(listOf("mahak-shoulder-bag"), search("ماه"))
        assertEquals(emptyList(), search("کیف", "سفید"))
        assertEquals(emptyList(), search("%"))
    }

    @Test
    fun `after a category, material or color gets a new name, refreshing the search text finds its products by it`() {
        inShop("mahak-shoulder-bag", listOf(NewColor("lilac")))
        inShop("simple-wallet", listOf(NewColor("cream")), details = ProductEventSamples.bareDetails)

        // Renamed inside one transaction that is rolled back, so the reference data stays as the migrations made it.
        transaction(db) {
            fun search(word: String) =
                repository.find(ProductFilter(words = listOf(word)), pageable(ProductSorts.PRICE_DESC)).items.map { it.slug.value }

            CategoryTbl.update({ CategoryTbl.slug eq "shoulder" }) { it[name] = "دوشی و هوبو" }
            MaterialTbl.update({ MaterialTbl.slug eq "genuine-cow-leather" }) { it[name] = "چرم بزی" }
            ColorTbl.update({ ColorTbl.colorKey eq "lilac" }) { it[name] = "بنفش روشن" }
            // The search text is a copy: until it is written again, the new names find nothing.
            assertEquals(listOf(emptyList<String>()), listOf("هوبو", "بزی", "بنفش").map(::search).distinct())

            val refreshed = listOf(
                NamedInSearch.Category(Slug.constructFromPersisted("shoulder")),
                NamedInSearch.Material(Slug.constructFromPersisted("genuine-cow-leather")),
                NamedInSearch.Color(ColorKey.constructFromPersisted("lilac")),
            ).map { repository.refreshSearchText(it) }

            assertEquals(listOf(1, 1, 1), refreshed)
            assertEquals(listOf(listOf("mahak-shoulder-bag"), listOf("simple-wallet"), listOf("mahak-shoulder-bag")), listOf("هوبو", "بزی", "بنفش").map(::search))
            rollback()
        }
    }

    // ============ in what order ============

    @Test
    fun `newest puts what can't be bought last, and so does every sort but the price ones`() {
        val older = inShop("older", price = 1_000_000, compareAt = 1_200_000, at = now)
        inShop("sold-out", price = 500_000, at = now.plusSeconds(60))
        val newer = inShop("newer", price = 2_000_000, at = now.plusSeconds(120))
        restock(older.color("lilac"), 4)
        restock(newer.color("lilac"), 4)

        assertEquals(listOf("newer", "older", "sold-out"), slugsOf(sort = ProductSorts.NEWEST))
        // By price, the cheap sold-out bag stays where its price puts it.
        assertEquals(listOf("sold-out", "older", "newer"), slugsOf(sort = ProductSorts.PRICE_ASC))
        assertEquals(listOf("newer", "older", "sold-out"), slugsOf(sort = ProductSorts.PRICE_DESC))
        assertEquals(listOf("older", "newer", "sold-out"), slugsOf(sort = ProductSorts.DISCOUNT_DESC))
        (ProductSorts.names - setOf(ProductSorts.PRICE_ASC, ProductSorts.PRICE_DESC)).forEach { sort ->
            assertEquals("sold-out", slugsOf(sort = sort).last(), "sort $sort")
        }
    }

    @Test
    fun `newest is by the first time in the shop - not when it was made, and going out and back in doesn't make it new`() {
        val monthAgo = now.minusSeconds(30 * 24 * 3600)
        val yesterday = now.minusSeconds(24 * 3600)
        // Made and put in the shop yesterday.
        restock(inShop("made-yesterday", at = yesterday).color("lilac"), 4)
        // Made a month ago, a draft until today.
        val draft = catalog.newProduct(listOf(NewColor("lilac")), slug = "draft-until-today", at = monthAgo)
        catalog.change(draft.id) { it.publish(staff, now) }
        restock(draft.color("lilac"), 4)
        // In the shop since a month ago; taken out and put back today.
        val back = inShop("back-in-shop", at = monthAgo)
        catalog.change(back.id) { it.unpublish(staff, now) }
        catalog.change(back.id) { it.publish(staff, now.plusSeconds(60)) }
        restock(back.color("lilac"), 4)

        assertEquals(listOf("draft-until-today", "made-yesterday", "back-in-shop"), slugsOf(sort = ProductSorts.NEWEST))
    }

    @Test
    fun `the sorts it knows are exactly ProductSorts, the ones the route allows`() {
        assertEquals(ProductSorts.names, ProductQmRepositoryImpl.sortOrders.keys)
    }

    @Test
    fun `ids come back in the order asked for, paged in that order, without what is not in the shop`() {
        val first = inShop("first")
        val second = inShop("second")
        val third = inShop("third")
        val draft = catalog.newProduct(listOf(NewColor("lilac")), slug = "a-draft")
        val ids = listOf(third, draft, first, second).map { it.id }

        assertEquals(listOf("third", "first", "second"), slugsOf(ProductFilter(ids = ids), ProductSorts.PRICE_ASC))

        val secondPage = find(ProductFilter(ids = ids), pageable(page = 2, size = 2))
        assertEquals(listOf("second"), secondPage.items.map { it.slug.value })
        assertEquals(3, secondPage.totalItems)
        assertEquals(emptyList(), find(ProductFilter(ids = listOf(ProductId.constructFromPersisted(999_999)))).items)
    }

    @Test
    fun `a page holds its share and knows the total`() {
        repeat(5) { inShop("bag-number-$it", at = now.plusSeconds(it.toLong())) }

        val third = find(pageable = pageable(page = 3, size = 2))

        assertEquals(listOf("bag-number-0"), third.items.map { it.slug.value })
        assertEquals(listOf(5L, 3L, false), listOf(third.totalItems, third.totalPages, third.hasNext))
    }

    // ============ what a card holds ============

    @Test
    fun `a card has the default color's price and its active colors in display order, with what each can still sell`() {
        val product = inShop(
            "mahak-shoulder-bag",
            listOf(NewColor("lilac", ownPriceTag = priceTag(1_450_000, 1_750_000)), NewColor("black"), NewColor("cream")),
        )
        catalog.change(product.id) { it.deactivateVariant(product.color("cream"), staff, now) }
        restock(product.color("lilac"), 5)
        catalog.stock(product.color("lilac")) {
            it.reserve(OrderCode.constructFromPersisted("KV-218340"), Quantity.constructFromPersisted(2), UserId.SYSTEM, now)
        }

        val card = find().items.single()

        assertEquals(priceTag(1_450_000, 1_750_000), card.priceTag)
        assertEquals(
            listOf(
                ProductCardQm.Color(product.color("lilac"), ColorKey.constructFromPersisted("lilac"), isDefault = true, available = 3),
                ProductCardQm.Color(product.color("black"), ColorKey.constructFromPersisted("black"), isDefault = false, available = 0),
            ),
            card.colors,
        )
        assertEquals(
            listOf<Any?>("KV-${1000 + product.id.value}", "کیف دوشی ماهک", "shoulder", BagType.HOBO, Instant.parse("2026-10-20T00:00:00Z"), 3),
            listOf(card.sku.value, card.name.value, card.categorySlug.value, card.bagType, card.newUntil, card.maxPerOrder.value),
        )
    }

    @Test
    fun `an empty page needs no second query and is still a page`() {
        val page = find(ProductFilter(words = listOf("هیچ‌چیز")))

        assertEquals(listOf(0L, emptyList<ProductCardQm>()), listOf(page.totalItems, page.items))
    }
}
