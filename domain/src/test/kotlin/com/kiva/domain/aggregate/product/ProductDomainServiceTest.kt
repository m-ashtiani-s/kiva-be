package com.kiva.domain.aggregate.product

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.model.valueobject.CategoryIcon
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryName
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.model.valueobject.ColorName
import com.kiva.domain.aggregate.color.model.valueobject.HexColor
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.material.entity.Material
import com.kiva.domain.aggregate.material.model.valueobject.MaterialFamily
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.model.valueobject.MaterialName
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import com.kiva.domain.aggregate.product.ProductTestData.admin
import com.kiva.domain.aggregate.product.ProductTestData.color
import com.kiva.domain.aggregate.product.ProductTestData.details
import com.kiva.domain.aggregate.product.ProductTestData.later
import com.kiva.domain.aggregate.product.ProductTestData.now
import com.kiva.domain.aggregate.product.ProductTestData.priceTag
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.ProductDetails
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.product.repository.ProductIdGenerator
import com.kiva.domain.aggregate.product.repository.ProductRepository
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ============ small in-memory stand-ins for the ports ============

/** Hands out 1, 2, 3... for products and 101, 102... for colors, and counts how many it gave. */
private class FakeProductIdGenerator : ProductIdGenerator {
    var productIdsGiven = 0
        private set
    var variantIdsGiven = 0
        private set

    override fun nextProductId(): ProductId = ProductId.constructFromPersisted((++productIdsGiven).toLong())
    override fun nextVariantId(): VariantId = VariantId.constructFromPersisted(100L + ++variantIdsGiven)
}

private class InMemoryProducts : ProductRepository {
    val saved = mutableMapOf<ProductId, Product>()
    override fun get(id: ProductId): Product? = saved[id]
    override fun save(product: Product) {
        saved[product.id] = product
    }
}

/** Category 1 (کیف دوشی) exists; nothing else. */
private object OneCategory : CategoryRepository {
    private val shoulder = Category.constructFromPersisted(
        CategoryId.constructFromPersisted(1), Slug.constructFromPersisted("shoulder"), CategoryName.constructFromPersisted("کیف دوشی"),
        null, CategoryIcon(BagType.HOBO, ColorKey.constructFromPersisted("lilac")), 1, true,
    )
    override fun findAll() = listOf(shoulder)
    override fun findBySlug(slug: Slug) = shoulder.takeIf { it.slug == slug }
    override fun findById(id: CategoryId) = shoulder.takeIf { it.id == id }
}

/** Material 4 (چرم مصنوعی مات) exists; nothing else. */
private object OneMaterial : MaterialRepository {
    private val matte = Material.constructFromPersisted(
        MaterialId.constructFromPersisted(4), Slug.constructFromPersisted("faux-leather-matte"),
        MaterialName.constructFromPersisted("چرم مصنوعی مات درجه یک"), MaterialFamily.FAUX_LEATHER, null, emptyList(),
    )
    override fun findAll() = listOf(matte)
    override fun findById(id: MaterialId) = matte.takeIf { it.id == id }
}

/** The palette is black, lilac and cream. */
private object ThreeColors : ColorRepository {
    private val palette = listOf("black" to "#2B2930", "lilac" to "#C8B6E2", "cream" to "#EADFCB").mapIndexed { i, (key, hex) ->
        Color.constructFromPersisted(ColorKey.constructFromPersisted(key), ColorName.constructFromPersisted(key), HexColor.constructFromPersisted(hex), false, i)
    }
    override fun findAll() = palette
    override fun findByKey(key: ColorKey) = palette.firstOrNull { it.key == key }
}

// ============ tests ============

class ProductDomainServiceTest {

    private val ids = FakeProductIdGenerator()
    private val products = InMemoryProducts()
    private val service = ProductDomainService(products, ids, OneCategory, OneMaterial, ThreeColors)

    private val mahak = Slug.constructFromPersisted("mahak-shoulder-bag")

    private fun newColor(key: String, sku: String? = null, makeDefault: Boolean = false, sortOrder: Int? = null) =
        ProductDomainService.NewVariant(color(key), sku?.let(Sku::constructFromPersisted), null, makeDefault, sortOrder, emptyList())

    private fun create(details: ProductDetails = details(), vararg colors: ProductDomainService.NewVariant) =
        service.createProduct(mahak, null, details, priceTag(1_890_000), colors.toList(), admin, now)

    private fun Product.eventTypes() = getRecordedEvents().map { it.type.value }

    @Nested
    inner class GettingAProduct {
        @Test
        fun `a stored product is found`() {
            val product = create(colors = arrayOf(newColor("black"))).getOrNull()!!
            products.save(product)

            assertEquals(product, service.getProduct(product.id).getOrNull())
        }

        @Test
        fun `an unknown one is PRODUCT_NOT_FOUND, a 404`() {
            val result = service.getProduct(ProductId.constructFromPersisted(99))

            assertEquals(Result.failure(ProductDomainService.GetProductFailure.ProductNotFound(ProductId.constructFromPersisted(99))), result)
            assertEquals(FailureKind.NOT_FOUND, result.failureOrNull()?.kind)
        }
    }

    @Nested
    inner class CreatingAProduct {
        @Test
        fun `a draft with its colors in the given order, with ids handed out and one event each, numbered 1, 2, 3`() {
            val product = create(colors = arrayOf(newColor("black"), newColor("lilac", makeDefault = true))).getOrNull()!!

            assertEquals(1L, product.id.value)
            assertEquals("KV-1001", product.sku.value)
            assertEquals(listOf(101L, 102L), product.variants.map { it.id.value })
            assertEquals(listOf(0, 1), product.variants.map { it.sortOrder })
            assertEquals(102L, product.defaultVariant()?.id?.value)
            assertEquals(listOf("ProductCreated", "VariantAdded", "VariantAdded"), product.eventTypes())
            assertEquals(listOf(1L, 2L, 3L), product.getRecordedEvents().map { it.sequence.value })
        }

        @Test
        fun `a draft without colors is fine too`() {
            assertTrue(create().getOrNull()!!.variants.isEmpty())
        }

        @Test
        fun `it never saves, because saving is the use case's job in its one transaction`() {
            create(colors = arrayOf(newColor("black")))

            assertTrue(products.saved.isEmpty())
        }

        @Test
        fun `an unknown category is CATEGORY_NOT_FOUND on the categoryId field, and no id is used up`() {
            val result = create(details().copy(categoryId = CategoryId.constructFromPersisted(99)))

            assertEquals(Result.failure(ProductDomainService.CreateProductFailure.CategoryNotFound(CategoryId.constructFromPersisted(99))), result)
            assertEquals("categoryId", result.failureOrNull()?.field)
            assertEquals(FailureKind.INVALID, result.failureOrNull()?.kind)
            assertEquals(0, ids.productIdsGiven)
        }

        @Test
        fun `an unknown material is MATERIAL_NOT_FOUND on the materialId field`() {
            val result = create(details().copy(materialId = MaterialId.constructFromPersisted(99)))

            assertEquals(Result.failure(ProductDomainService.CreateProductFailure.MaterialNotFound(MaterialId.constructFromPersisted(99))), result)
        }

        @Test
        fun `a color that is not in the palette is COLOR_NOT_FOUND, before anything is created`() {
            val result = create(colors = arrayOf(newColor("black"), newColor("teal")))

            assertEquals(Result.failure(ProductDomainService.CreateProductFailure.ColorNotFound(color("teal"))), result)
            assertEquals(0, ids.productIdsGiven)
        }

        @Test
        fun `the same color twice in the list is a conflict`() {
            val result = create(colors = arrayOf(newColor("black"), newColor("black")))

            assertEquals(Result.failure(ProductDomainService.CreateProductFailure.ColorRepeated(color("black"))), result)
            assertEquals(FailureKind.CONFLICT, result.failureOrNull()?.kind)
        }

        @Test
        fun `two colors with the same typed SKU is DUPLICATE_SKU`() {
            val result = create(colors = arrayOf(newColor("black", sku = "MAHAK-1"), newColor("lilac", sku = "MAHAK-1")))

            assertEquals(Result.failure(ProductDomainService.CreateProductFailure.VariantSkuRepeated(color("lilac"))), result)
            assertEquals("DUPLICATE_SKU", result.failureOrNull()?.code)
        }
    }

    @Nested
    inner class ChangingDetails {
        @Test
        fun `new content is recorded when its category and material exist`() {
            val product = create().getOrNull()!!

            assertTrue(service.changeDetails(product, details(name = "کیف دوشی ماهک جدید"), admin, later).isSuccess)
            assertEquals("ProductDetailsChanged", product.eventTypes().last())
        }

        @Test
        fun `an unknown category or material is refused and the product stays as it was`() {
            val product = create().getOrNull()!!

            val badCategory = service.changeDetails(product, details().copy(categoryId = CategoryId.constructFromPersisted(99)), admin, later)
            val badMaterial = service.changeDetails(product, details().copy(materialId = MaterialId.constructFromPersisted(99)), admin, later)

            assertEquals("CATEGORY_NOT_FOUND", badCategory.failureOrNull()?.code)
            assertEquals("MATERIAL_NOT_FOUND", badMaterial.failureOrNull()?.code)
            assertEquals(listOf("ProductCreated"), product.eventTypes())
        }

        @Test
        fun `an archived product is refused with the aggregate's reason`() {
            val product = create().getOrNull()!!
            product.archive(admin, now)

            assertEquals(
                Result.failure(ProductDomainService.ChangeDetailsFailure.ProductIsArchived),
                service.changeDetails(product, details(name = "اسم دیگه"), admin, later),
            )
        }
    }

    @Nested
    inner class AddingAColor {
        @Test
        fun `a palette color gets a new id and goes after the colors already there`() {
            val product = create(colors = arrayOf(newColor("black", sortOrder = 4))).getOrNull()!!

            service.addVariant(product, newColor("lilac"), admin, later)

            val lilac = product.variants.last()
            assertEquals(102L, lilac.id.value)
            assertEquals(5, lilac.sortOrder)
        }

        @Test
        fun `a color not in the palette is COLOR_NOT_FOUND, and no id is used up`() {
            val product = create().getOrNull()!!

            assertEquals(Result.failure(ProductDomainService.AddVariantFailure.ColorNotFound(color("teal"))), service.addVariant(product, newColor("teal"), admin, later))
            assertEquals(0, ids.variantIdsGiven)
        }

        @Test
        fun `the aggregate's refusals come back as the service's own failures`() {
            val product = create(colors = arrayOf(newColor("black"))).getOrNull()!!

            assertEquals(
                Result.failure(ProductDomainService.AddVariantFailure.ColorAlreadyAdded(color("black"))),
                service.addVariant(product, newColor("black"), admin, later),
            )

            product.archive(admin, later)
            assertEquals(Result.failure(ProductDomainService.AddVariantFailure.ProductIsArchived), service.addVariant(product, newColor("lilac"), admin, later))
        }
    }
}
