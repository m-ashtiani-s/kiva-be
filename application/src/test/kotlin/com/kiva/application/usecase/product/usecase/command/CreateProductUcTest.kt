package com.kiva.application.usecase.product.usecase.command

import com.kiva.application.repository.ProductFacetsQm
import com.kiva.application.dto.CreatedProductDto
import com.kiva.application.repository.ProductCardQm
import com.kiva.application.repository.ProductDetailQm
import com.kiva.application.repository.ProductFilter
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.repository.ProductRef
import com.kiva.application.repository.VariantQm
import com.kiva.application.usecase.product.usecase.command.CreateProductUc.Cmd
import com.kiva.application.util.MockTransactionManager
import com.kiva.application.util.factory.CategoryFactory
import com.kiva.application.util.factory.ColorFactory
import com.kiva.application.util.factory.MaterialFactory
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.material.entity.Material
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import com.kiva.domain.aggregate.product.ProductDomainService
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.entity.event.ProductDomainEvent
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.ClosureType
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.Occasion
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.product.repository.ProductIdGenerator
import com.kiva.domain.aggregate.product.repository.ProductRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Products kept in memory as their events, like the real repository. */
private class InMemoryProducts : ProductRepository {
    val streams = mutableMapOf<ProductId, List<ProductDomainEvent>>()

    override fun get(id: ProductId): Product? = streams[id]?.let { Product(it) }

    override fun save(product: Product) {
        streams[product.id] = streams[product.id].orEmpty() + product.getRecordedEvents()
    }
}

/** Ids from 1, like the database sequences on an empty database. */
private class CountingIds : ProductIdGenerator {
    var productsTaken = 0L
    var variantsTaken = 100L

    override fun nextProductId(): ProductId = ProductId.constructFromPersisted(++productsTaken)

    override fun nextVariantId(): VariantId = VariantId.constructFromPersisted(++variantsTaken)
}

/** The read model as far as creating a product needs it: the slugs and codes already in use. */
private class ReadModel(val takenSlugs: Set<String> = emptySet(), val takenSkus: Set<String> = emptySet()) : ProductQmRepository {
    override fun find(filter: ProductFilter, pageable: Pageable): Page<ProductCardQm> = error("not used here")

    override fun findDetail(ref: ProductRef): ProductDetailQm? = error("not used here")

    override fun facets(filter: ProductFilter, now: Instant): ProductFacetsQm = error("not used here")

    override fun findRelated(productId: ProductId, limit: Int): List<ProductCardQm>? = error("not used here")

    override fun isSlugTaken(slug: Slug): Boolean = slug.value in takenSlugs

    override fun findTakenSkus(skus: Collection<Sku>): Set<Sku> = skus.filter { it.value in takenSkus }.toSet()

    override fun findVariant(variantId: VariantId): VariantQm? = error("not used here")

    override fun countActiveByCategory(): Map<Slug, Int> = error("not used here")

    override fun countActiveByMaterial(): Map<Slug, Int> = error("not used here")

    override fun countActiveByColor(): Map<ColorKey, Int> = error("not used here")
}

class CreateProductUcTest {

    private val now = Instant.parse("2026-10-06T08:00:00Z")
    private val staff = UserId.constructFromPersisted(1)

    private val transactionManager = MockTransactionManager()
    private val products = InMemoryProducts()
    private val ids = CountingIds()

    // The shoulder category, satin, and a palette of lilac, black and cream.
    private val categories = object : CategoryRepository {
        private val shoulder = CategoryFactory.generate(id = 1, slug = "shoulder")
        override fun findAll(): List<Category> = listOf(shoulder)
        override fun findBySlug(slug: Slug): Category? = shoulder.takeIf { it.slug == slug }
        override fun findById(id: CategoryId): Category? = shoulder.takeIf { it.id == id }
    }
    private val materials = object : MaterialRepository {
        private val satin = MaterialFactory.generate(id = 11, slug = "satin", name = "ساتن")
        override fun findAll(): List<Material> = listOf(satin)
        override fun findById(id: MaterialId): Material? = satin.takeIf { it.id == id }
    }
    private val colors = object : ColorRepository {
        private val palette = listOf("lilac", "black", "cream").map { ColorFactory.generate(key = it) }
        override fun findAll(): List<Color> = palette
        override fun findByKey(key: ColorKey): Color? = palette.firstOrNull { it.key == key }
    }

    private fun useCase(readModel: ReadModel = ReadModel()) = CreateProductUc(
        transactionManager,
        ProductDomainService(products, ids, categories, materials, colors),
        products,
        readModel,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun color(key: String, sku: String? = null, price: Long? = null, compareAt: Long? = null, isDefault: Boolean = false) =
        Cmd.Variant(key, sku, price, compareAt, isDefault, sortOrder = null)

    private fun cmd(
        slug: String = "mahak-shoulder-bag",
        sku: String? = null,
        status: String? = null,
        variants: List<Cmd.Variant> = listOf(color("lilac"), color("black")),
    ) = Cmd(
        name = "کیف دوشی ماهک",
        slug = slug,
        sku = sku,
        categoryId = 1,
        bagType = "HOBO",
        materialId = 11,
        materialDescription = "ساتن با سگک فلزی طلایی",
        price = 1_890_000,
        compareAtPrice = null,
        shortDescription = null,
        description = "<p>کیف دوشی جادار</p>",
        highlights = listOf("یراق‌آلات فلزی ضدزنگ"),
        specs = Cmd.Specs(Cmd.Dimensions(34.0, 26.0, 12.0), 650, "ZIPPER", "بند دوشی ثابت", null, listOf("DAILY", "WORK")),
        newUntil = Instant.parse("2026-11-01T00:00:00Z"),
        maxPerOrder = null,
        returnable = null,
        sortPriority = null,
        status = status,
        variants = variants,
        by = staff,
    )

    private fun stored(id: Long = 1): Product? = products.get(ProductId.constructFromPersisted(id))

    /** The field paths a client would get, the way the HTTP layer builds them from the failure tree. */
    private fun fieldErrors(failure: ResultFailure, parent: String? = null): List<String> {
        val path = listOfNotNull(parent, failure.field).joinToString(".").ifEmpty { null }
        if (failure.causes.isNotEmpty()) return failure.causes.flatMap { fieldErrors(it, path) }
        return listOf("$path ${failure.code}")
    }

    @Nested
    inner class Creating {
        @Test
        fun `a valid request makes a draft with its colors in one transaction and answers the new ids`() = runTest {
            val result = useCase().execute(cmd())

            assertEquals(Result.success(CreatedProductDto(1, mapOf("lilac" to 101L, "black" to 102L))), result)
            val product = stored()!!
            assertEquals(ProductStatus.DRAFT, product.status)
            assertEquals(listOf("KV-1001-LILAC" to true, "KV-1001-BLACK" to false), product.variants.map { it.sku.value to it.isDefault })
            assertEquals(1, transactionManager.transactionsOpened)
        }

        @Test
        fun `every input reaches the product, and what is left out takes the contract's defaults`() = runTest {
            useCase().execute(cmd())

            val product = stored()!!
            val details = product.details
            assertEquals(
                listOf<Any?>(
                    "mahak-shoulder-bag", "KV-1001", "کیف دوشی ماهک", 1L, 11L, BagType.HOBO, "ساتن با سگک فلزی طلایی",
                    "<p>کیف دوشی جادار</p>", listOf("یراق‌آلات فلزی ضدزنگ"), Instant.parse("2026-11-01T00:00:00Z"),
                ),
                listOf(
                    product.slug.value, product.sku.value, details.name.value, details.categoryId.value, details.materialId.value,
                    details.bagType, details.materialDescription?.value, details.description?.value, details.highlights.items, details.newUntil,
                ),
            )
            assertEquals(
                listOf<Any?>(34.0, 650, ClosureType.ZIPPER, "بند دوشی ثابت", null, setOf(Occasion.DAILY, Occasion.WORK)),
                details.specs.let { listOf(it.dimensions?.lengthCm, it.weightGrams, it.closure, it.strap, it.pockets, it.occasions) },
            )
            assertEquals(listOf<Any?>(MaxPerOrder.DEFAULT, true, 0, null), listOf(details.maxPerOrder, details.returnable, details.sortPriority, details.shortDescription))
            assertEquals(PriceTag.constructFromPersisted(Money.constructFromPersisted(1_890_000), null), product.priceTag)
        }

        @Test
        fun `status ACTIVE puts it in the shop in the same save`() = runTest {
            useCase().execute(cmd(status = "ACTIVE"))

            assertEquals(ProductStatus.ACTIVE, stored()?.status)
            assertEquals(1, transactionManager.transactionsOpened)
        }

        @Test
        fun `a typed code is kept, made uppercase, and a blank one is made from the id`() = runTest {
            useCase().execute(cmd(sku = "kv-mahak", variants = listOf(color("lilac", sku = " "), color("black", sku = "kv-mahak-night"))))

            val product = stored()!!
            assertEquals(listOf("KV-MAHAK", "KV-MAHAK-LILAC", "KV-MAHAK-NIGHT"), listOf(product.sku.value) + product.variants.map { it.sku.value })
        }

        @Test
        fun `a color with its own price keeps it, crossed-out price included`() = runTest {
            useCase().execute(cmd(variants = listOf(color("lilac", price = 1_450_000, compareAt = 1_750_000), color("black"))))

            assertEquals(
                listOf(PriceTag.constructFromPersisted(Money.constructFromPersisted(1_450_000), Money.constructFromPersisted(1_750_000)), null),
                stored()!!.variants.map { it.ownPriceTag },
            )
        }
    }

    @Nested
    inner class Refusing {
        @Test
        fun `every wrong input is reported at once under its full path, before any transaction`() = runTest {
            val wrong = cmd(slug = "Mahak Bag", status = "LIVE", variants = listOf(color("lilac", compareAt = 2_000_000), color("Lilac", price = -5)))
                .copy(
                    name = " ",
                    categoryId = 0,
                    bagType = "SHOULDER",
                    price = 1_000_000,
                    compareAtPrice = 900_000,
                    highlights = List(9) { "ویژگی $it" },
                    specs = Cmd.Specs(Cmd.Dimensions(0.0, 26.0, 12.0), 0, "VELCRO", null, null, listOf("DAILY", "PICNIC")),
                    maxPerOrder = 0,
                )

            val failure = useCase().execute(wrong).failureOrNull()!!

            assertEquals("VALIDATION_ERROR", failure.code)
            assertEquals(
                listOf(
                    "slug SLUG_INVALID",
                    "status PRODUCT_STATUS_INVALID",
                    "name PRODUCT_NAME_REQUIRED",
                    "categoryId CATEGORY_ID_INVALID",
                    "bagType BAG_TYPE_INVALID",
                    "highlights HIGHLIGHTS_TOO_MANY",
                    "specs.dimensions DIMENSIONS_INVALID",
                    "specs.closure CLOSURE_TYPE_INVALID",
                    "specs.occasions.1 OCCASION_INVALID",
                    "specs.weightGrams WEIGHT_INVALID",
                    "maxPerOrder MAX_PER_ORDER_INVALID",
                    "compareAtPrice COMPARE_AT_PRICE_NOT_ABOVE_PRICE",
                    "variants.0.priceOverride PRICE_OVERRIDE_REQUIRED",
                    "variants.1.colorKey COLOR_KEY_INVALID",
                    "variants.1.priceOverride MONEY_NEGATIVE",
                ),
                fieldErrors(failure),
            )
            assertEquals(0, transactionManager.transactionsOpened)
        }

        @Test
        fun `a slug in use is DUPLICATE_SLUG, found before any id is taken`() = runTest {
            val result = useCase(ReadModel(takenSlugs = setOf("mahak-shoulder-bag"))).execute(cmd())

            assertEquals(Result.failure(CreateProductUc.Failure.DuplicateSlug(Slug.constructFromPersisted("mahak-shoulder-bag"))), result)
            assertEquals(listOf(FailureKind.CONFLICT, "slug"), result.failureOrNull()?.let { listOf(it.kind, it.field) })
            assertEquals(listOf(0L, 100L), listOf(ids.productsTaken, ids.variantsTaken))
            assertTrue(products.streams.isEmpty())
        }

        @Test
        fun `a code another product has is DUPLICATE_SKU on the input that has it, even one made from the id`() = runTest {
            val takenColorCode = useCase(ReadModel(takenSkus = setOf("KV-1001-BLACK"))).execute(cmd())
            val takenProductCode = useCase(ReadModel(takenSkus = setOf("KV-1002"))).execute(cmd())

            assertEquals(Result.failure(CreateProductUc.Failure.DuplicateSku("variants.1.sku", "KV-1001-BLACK")), takenColorCode)
            assertEquals(Result.failure(CreateProductUc.Failure.DuplicateSku("sku", "KV-1002")), takenProductCode)
            assertTrue(products.streams.isEmpty())
        }

        @Test
        fun `a color outside the palette is COLOR_NOT_FOUND at its place in the list`() = runTest {
            val result = useCase().execute(cmd(variants = listOf(color("lilac"), color("teal"))))

            assertEquals(Result.failure(CreateProductUc.Failure.ColorNotFound(ColorKey.constructFromPersisted("teal"), 1)), result)
            assertEquals("variants.1.colorKey", result.failureOrNull()?.field)
            assertNull(stored())
        }

        @Test
        fun `the same color twice is refused at its second place`() = runTest {
            val result = useCase().execute(cmd(variants = listOf(color("lilac"), color("black"), color("lilac"))))

            assertEquals(Result.failure(CreateProductUc.Failure.ColorRepeated(ColorKey.constructFromPersisted("lilac"), 2)), result)
            assertEquals("variants.2.colorKey", result.failureOrNull()?.field)
        }

        @Test
        fun `a category nobody has is CATEGORY_NOT_FOUND on categoryId`() = runTest {
            val result = useCase().execute(cmd().copy(categoryId = 9))

            assertEquals(Result.failure(CreateProductUc.Failure.CategoryNotFound(CategoryId.constructFromPersisted(9))), result)
            assertEquals(FailureKind.INVALID, result.failureOrNull()?.kind)
        }

        @Test
        fun `status ACTIVE without any color is NO_ACTIVE_VARIANT and nothing is saved`() = runTest {
            val result = useCase().execute(cmd(status = "ACTIVE", variants = emptyList()))

            assertEquals(Result.failure(CreateProductUc.Failure.NoActiveVariant), result)
            assertEquals(FailureKind.BUSINESS_RULE, result.failureOrNull()?.kind)
            assertNull(stored())
        }
    }
}
