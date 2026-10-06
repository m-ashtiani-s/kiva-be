package com.kiva.application.usecase.product.usecase.query

import com.kiva.application.dto.ProductFacetsDto
import com.kiva.application.repository.ProductCardQm
import com.kiva.application.repository.ProductDetailQm
import com.kiva.application.repository.ProductFacetsQm
import com.kiva.application.repository.ProductFilter
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.repository.ProductRef
import com.kiva.application.repository.VariantQm
import com.kiva.application.usecase.product.ProductFilterQuery
import com.kiva.application.util.MockTransactionManager
import com.kiva.application.util.factory.CategoryFactory
import com.kiva.application.util.factory.ColorFactory
import com.kiva.application.util.factory.MaterialFactory
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.material.entity.Material
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The read model as far as facets need it: hands back [counts] and remembers what it was asked. */
private class FacetsReadModel(var counts: ProductFacetsQm) : ProductQmRepository {
    var askedFilter: ProductFilter? = null
    var askedNow: Instant? = null

    override fun facets(filter: ProductFilter, now: Instant): ProductFacetsQm {
        askedFilter = filter
        askedNow = now
        return counts
    }

    override fun find(filter: ProductFilter, pageable: Pageable): Page<ProductCardQm> = error("not used here")

    override fun findDetail(ref: ProductRef): ProductDetailQm? = error("not used here")

    override fun findRelated(productId: ProductId, limit: Int): List<ProductCardQm>? = error("not used here")

    override fun findVariant(variantId: VariantId): VariantQm? = error("not used here")

    override fun isSlugTaken(slug: Slug): Boolean = error("not used here")

    override fun findTakenSkus(skus: Collection<Sku>): Set<Sku> = error("not used here")

    override fun countActiveByCategory(): Map<Slug, Int> = error("not used here")

    override fun countActiveByMaterial(): Map<Slug, Int> = error("not used here")

    override fun countActiveByColor(): Map<ColorKey, Int> = error("not used here")
}

class GetProductFacetsUcTest {

    private val now = Instant.parse("2026-10-07T08:00:00Z")
    private val transactionManager = MockTransactionManager()

    private fun slug(value: String) = Slug.constructFromPersisted(value)
    private fun color(key: String) = ColorKey.constructFromPersisted(key)

    private val readModel = FacetsReadModel(
        ProductFacetsQm(
            categories = mapOf(slug("shoulder") to 2),
            colors = mapOf(color("black") to 2, color("lilac") to 1),
            materials = mapOf(slug("satin") to 1),
            bagTypes = mapOf(BagType.HOBO to 2),
            minPrice = Money.constructFromPersisted(590_000),
            maxPrice = Money.constructFromPersisted(3_150_001),
            onSale = 1,
            inStock = 2,
            new = 0,
            withVideo = 1,
        ),
    )

    private val categories = object : CategoryRepository {
        private val all = listOf(
            CategoryFactory.generate(id = 1, slug = "shoulder", name = "کیف دوشی", sortOrder = 1),
            CategoryFactory.generate(id = 2, slug = "hand", name = "کیف دستی", sortOrder = 2),
        )
        override fun findAll(): List<Category> = all
        override fun findBySlug(slug: Slug): Category? = error("not used here")
        override fun findById(id: CategoryId): Category? = error("not used here")
    }
    private val colors = object : ColorRepository {
        private val palette = listOf(
            ColorFactory.generate(key = "black", name = "مشکی", hex = "#2B2930", isLight = false, sortOrder = 1),
            ColorFactory.generate(key = "lilac", name = "یاسی", hex = "#C8B6E2", isLight = true, sortOrder = 4),
        )
        override fun findAll(): List<Color> = palette
        override fun findByKey(key: ColorKey): Color? = error("not used here")
    }
    private val materials = object : MaterialRepository {
        private val all = listOf(MaterialFactory.generate(id = 1), MaterialFactory.generate(id = 11, slug = "satin", name = "ساتن"))
        override fun findAll(): List<Material> = all
        override fun findById(id: MaterialId): Material? = error("not used here")
    }

    private val useCase = GetProductFacetsUc(transactionManager, readModel, categories, colors, materials, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `every value of every filter is listed in its own order, with its count, 0 when it gives none, and whether it is picked`() = runTest {
        val facets = useCase.execute(GetProductFacetsUc.Qry(ProductFilterQuery(colors = listOf("lilac"), bagTypes = listOf("HOBO")))).getOrNull()!!

        assertEquals(
            listOf(ProductFacetsDto.Option("shoulder", "کیف دوشی", 2, false), ProductFacetsDto.Option("hand", "کیف دستی", 0, false)),
            facets.categories,
        )
        assertEquals(
            listOf(ProductFacetsDto.Option("black", "مشکی", 2, false, "#2B2930"), ProductFacetsDto.Option("lilac", "یاسی", 1, true, "#C8B6E2")),
            facets.colors,
        )
        assertEquals(
            listOf(ProductFacetsDto.Option("genuine-cow-leather", "چرم طبیعی گاوی", 0, false), ProductFacetsDto.Option("satin", "ساتن", 1, false)),
            facets.materials,
        )
        assertEquals(BagType.entries.map { ProductFacetsDto.BagTypeOption(it, if (it == BagType.HOBO) 2 else 0, it == BagType.HOBO) }, facets.bagTypes)
        assertEquals(listOf(1, 2, 0, 1), listOf(facets.onSaleCount, facets.inStockCount, facets.newCount, facets.withVideoCount))
        assertEquals(now, readModel.askedNow)
        assertEquals(1, transactionManager.transactionsOpened)
    }

    @Test
    fun `the price range is rounded out to the contract's step - the cheapest down, the dearest up`() = runTest {
        val facets = useCase.execute(GetProductFacetsUc.Qry()).getOrNull()!!

        assertEquals(ProductFacetsDto.PriceRange(min = 590_000, max = 3_160_000, step = 10_000), facets.price)
    }

    @Test
    fun `with nothing matching there is no price range`() = runTest {
        readModel.counts = readModel.counts.copy(minPrice = null, maxPrice = null)

        assertNull(useCase.execute(GetProductFacetsUc.Qry()).getOrNull()!!.price)
    }

    @Test
    fun `the filters are checked as the list's, before any transaction`() = runTest {
        val failure = useCase.execute(GetProductFacetsUc.Qry(ProductFilterQuery(minPrice = "900", maxPrice = "100"))).failureOrNull()!!

        assertEquals("VALIDATION_ERROR", failure.code)
        assertEquals(listOf("maxPrice" to "PRICE_RANGE_INVALID"), failure.causes.map { it.field to it.code })
        assertEquals(0, transactionManager.transactionsOpened)
    }
}
