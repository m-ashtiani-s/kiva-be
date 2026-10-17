package com.kiva.application.usecase.product.usecase.command

import com.kiva.application.port.AuditChange
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.application.usecase.product.ProductInput
import com.kiva.application.util.MockTransactionManager
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.product.ProductDomainService
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateProductUcTest {

    private val now = Instant.parse("2026-10-06T08:00:00Z")
    private val staff = PanelActor(UserId.constructFromPersisted(1), "10.0.0.1")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val transactionManager = MockTransactionManager()
    private val products = InMemoryProducts()
    private val ids = CountingIds()
    private val audit = RecordingAuditTrail()
    private val input = ProductInput(HtmlSanitizer { it })
    private val domain = ProductDomainService(products, ids, Catalog.categories, Catalog.materials, Catalog.colors)

    private val useCase = UpdateProductUc(transactionManager, domain, products, input, audit, clock)

    private fun fields(
        name: String? = "کیف دوشی ماهک",
        slug: String? = "mahak-shoulder-bag",
        sku: String? = null,
        price: Long? = 1_890_000,
        compareAtPrice: Long? = null,
    ) = ProductInput.Fields(
        name = name, slug = slug, sku = sku, categoryId = 1, bagType = "HOBO", materialId = 11, materialDescription = null,
        price = price, compareAtPrice = compareAtPrice, shortDescription = null, description = "<p>جادار</p>", highlights = null,
        specs = null, tags = null, newUntil = null, maxPerOrder = null, returnable = null, reservable = null, sortPriority = null, seo = null,
    )

    /** Product 1 made through CreateProductUc, one color: version 2. */
    private suspend fun existing(): Product {
        val create = CreateProductUc(
            transactionManager, domain, products, ReadModel(), input, MediaAttachments(InMemoryMedia()), InventoryDomainService(InMemoryInventories()),
            InMemoryInventories(), RecordingAuditTrail(), clock,
        )
        create.execute(CreateProductUc.Cmd(fields(), null, listOf(ProductInput.Variant("lilac")), staff))
        return stored()
    }

    private fun stored(): Product = products.get(ProductId.constructFromPersisted(1))!!

    private fun cmd(fields: ProductInput.Fields = fields(), ifMatch: String? = null, productId: String = "1") =
        UpdateProductUc.Cmd(productId, fields, ifMatch, staff)

    @Nested
    inner class IfMatch {
        @ParameterizedTest
        @ValueSource(strings = ["W/\"2\"", "\"2\"", "2", " W/\"2\" "])
        fun `the three accepted shapes of the current version let the save through`(ifMatch: String) = runTest {
            existing()

            val result = useCase.execute(cmd(fields(name = "کیف ماهک"), ifMatch))

            assertEquals(Result.success(ProductId.constructFromPersisted(1)), result)
            assertEquals(3, stored().version)
        }

        @ParameterizedTest
        @ValueSource(strings = ["W/2", "\"2", "abc", "", "W/\"\"", "2,3"])
        fun `any other shape is IF_MATCH_INVALID on the If-Match header, before anything is read`(ifMatch: String) = runTest {
            val failure = useCase.execute(cmd(ifMatch = ifMatch)).failureOrNull()!!

            assertEquals("VALIDATION_ERROR", failure.code)
            assertEquals(listOf("IF_MATCH_INVALID" to "If-Match"), failure.causes.map { it.code to it.field })
            assertEquals(0, transactionManager.transactionsOpened)
        }

        @Test
        fun `a stale version is 412 CHANGED_BY_SOMEONE_ELSE with the current ETag, and nothing is recorded`() = runTest {
            existing()

            val failure = useCase.execute(cmd(fields(name = "کیف ماهک"), ifMatch = "W/\"1\"")).failureOrNull()!!

            assertEquals(UpdateProductUc.Failure.ChangedBySomeoneElse(2), failure)
            assertEquals(FailureKind.PRECONDITION_FAILED, failure.kind)
            assertEquals(mapOf<String, Any>("currentEtag" to "W/\"2\""), failure.meta)
            assertEquals(2, stored().version)
            assertTrue(audit.recorded.isEmpty())
        }

        @Test
        fun `a stale version comes before every other rule, even a wrong form or a changed slug`() = runTest {
            existing()

            val wrongForm = useCase.execute(cmd(fields(price = 1_000_000, compareAtPrice = 900_000), ifMatch = "1")).failureOrNull()
            val otherSlug = useCase.execute(cmd(fields(slug = "other-bag"), ifMatch = "1")).failureOrNull()

            assertEquals(UpdateProductUc.Failure.ChangedBySomeoneElse(2), wrongForm)
            assertEquals(UpdateProductUc.Failure.ChangedBySomeoneElse(2), otherSlug)
        }

        @Test
        fun `without If-Match the save goes through unchecked`() = runTest {
            existing()

            assertEquals(Result.success(ProductId.constructFromPersisted(1)), useCase.execute(cmd(fields(name = "کیف ماهک"))))
        }
    }

    @Nested
    inner class Rules {
        @Test
        fun `a product nobody has, or a malformed id, is PRODUCT_NOT_FOUND`() = runTest {
            assertEquals(Result.failure(UpdateProductUc.Failure.NotFound), useCase.execute(cmd(productId = "9")))
            assertEquals(Result.failure(UpdateProductUc.Failure.NotFound), useCase.execute(cmd(productId = "abc")))
        }

        @Test
        fun `the slug and the code never change, but a code left out or the same one is fine`() = runTest {
            existing()

            assertEquals(listOf("SLUG_IMMUTABLE" to "slug"), useCase.execute(cmd(fields(slug = "other-bag"))).failureOrNull()!!.causes.map { it.code to it.field })
            assertEquals(listOf("SKU_IMMUTABLE" to "sku"), useCase.execute(cmd(fields(sku = "KV-9"))).failureOrNull()!!.causes.map { it.code to it.field })
            assertEquals(Result.success(ProductId.constructFromPersisted(1)), useCase.execute(cmd(fields(sku = "kv-1001"))))
            assertEquals(Result.success(ProductId.constructFromPersisted(1)), useCase.execute(cmd(fields(sku = " "))))
        }

        @Test
        fun `an archived product is PRODUCT_ARCHIVED`() = runTest {
            existing().archive(staff.userId, now).getOrNull()!!.also(products::save)

            val result = useCase.execute(cmd(fields(name = "کیف ماهک")))

            assertEquals(Result.failure(UpdateProductUc.Failure.Archived), result)
            assertEquals(FailureKind.BUSINESS_RULE, result.failureOrNull()?.kind)
        }

        @Test
        fun `the same values again record no event and no change log`() = runTest {
            existing()

            assertEquals(Result.success(ProductId.constructFromPersisted(1)), useCase.execute(cmd()))
            assertEquals(2, stored().version)
            assertTrue(audit.recorded.isEmpty())
        }

        @Test
        fun `a change of the price alone logs PRODUCT_PRICE_CHANGED with the price before and after`() = runTest {
            existing()

            useCase.execute(cmd(fields(price = 1_750_000)))

            val row = audit.recorded.single()
            assertEquals(AuditAction.PRODUCT_PRICE_CHANGED, row.action)
            assertEquals(listOf(AuditChange("price", "1890000", "1750000")), row.changes)
        }

        @Test
        fun `price and name together log one PRODUCT_UPDATED with both`() = runTest {
            existing()

            useCase.execute(cmd(fields(name = "کیف ماهک", price = 1_750_000)))

            val row = audit.recorded.single()
            assertEquals(listOf<Any?>(AuditAction.PRODUCT_UPDATED, "1", "کیف ماهک"), listOf(row.action, row.entityId, row.entityLabel))
            assertEquals(listOf(AuditChange("name", "کیف دوشی ماهک", "کیف ماهک"), AuditChange("price", "1890000", "1750000")), row.changes)
            assertEquals(4, stored().version)
        }

        @Test
        fun `a wrong form without If-Match is the form's problems, nothing read`() = runTest {
            val failure = useCase.execute(cmd(fields(name = null, price = null))).failureOrNull()!!

            assertEquals(listOf("name", "price"), failure.causes.map { it.field })
            assertEquals(0, transactionManager.transactionsOpened)
            assertNull(products.get(ProductId.constructFromPersisted(1)))
        }
    }
}
