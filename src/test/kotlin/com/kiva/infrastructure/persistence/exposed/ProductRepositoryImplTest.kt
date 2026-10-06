package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Products stored as events in kiva_test and read back, each step in its own transaction as in the app.
 * Every product gets a fresh id from the real sequence; its events are deleted after each test.
 */
class ProductRepositoryImplTest {

    private val db = TestDatabase.database
    private val repository = ProductRepositoryImpl()
    private val ids = ProductIdGeneratorImpl()

    private val admin = UserId.constructFromPersisted(12)
    private val now = Instant.parse("2026-10-06T08:00:00Z")
    private val stored = mutableListOf<ProductId>()

    @AfterEach
    fun deleteWhatWasStored() {
        transaction(db) {
            EventStoreTbl.deleteWhere { aggregateId inList stored.map { it.value.toString() } }
            // Saving projects into the read model too; the next test reuses the slug.
            ProductVariantQmTbl.deleteWhere { productId inList stored.map { it.value } }
            ProductQmTbl.deleteWhere { productId inList stored.map { it.value } }
        }
    }

    private fun priceTag(price: Long, compareAt: Long? = null) =
        PriceTag.constructFromPersisted(Money.constructFromPersisted(price), compareAt?.let { Money.constructFromPersisted(it) })

    private fun Result<Product, *>.done() = assertTrue(isSuccess, "refused: $this")

    /** Every field of a product, its colors' too (a Variant's equals only compares ids). */
    private fun Product.snapshot(): List<Any?> = listOf(
        id, slug, sku, details, priceTag, status,
        variants.map { listOf(it.id, it.colorKey, it.sku, it.ownPriceTag, it.isDefault, it.isActive, it.sortOrder, it.media) },
    )

    private fun read(id: ProductId): Product? = transaction(db) { repository.get(id) }

    private fun save(product: Product) = transaction(db) { repository.save(product) }

    private fun streamLength(id: ProductId): Long =
        transaction(db) { EventStoreTbl.selectAll().where { EventStoreTbl.aggregateId eq id.value.toString() }.count() }

    /** A published product with two colors, a new price and a gallery, the lilac taken out: 7 events. Saved. */
    private fun storedProduct(): Product = transaction(db) {
        val product = Product.create(
            ids.nextProductId(), Slug.constructFromPersisted("mahak-shoulder-bag"), null,
            ProductEventSamples.fullDetails, priceTag(1_750_000, 2_100_000), admin, now,
        ).getOrNull()!!
        val lilac = ids.nextVariantId()
        val black = ids.nextVariantId()

        product.addVariant(lilac, ColorKey.constructFromPersisted("lilac"), null, priceTag(1_450_000, 1_750_000), true, 0, listOf(ProductEventSamples.frontPhoto), admin, now).done()
        product.addVariant(black, ColorKey.constructFromPersisted("black"), null, null, false, 1, emptyList(), admin, now).done()
        product.changePrice(priceTag(1_890_000), admin, now).done()
        product.replaceVariantMedia(black, listOf(ProductEventSamples.video), admin, now).done()
        product.publish(admin, now).done()
        product.deactivateVariant(lilac, admin, now).done()

        repository.save(product)
        stored += product.id
        product
    }

    @Test
    fun `a saved product reads back with exactly the same state`() {
        val saved = storedProduct()

        assertEquals(saved.snapshot(), read(saved.id)?.snapshot())
        assertEquals(7, streamLength(saved.id))
    }

    @Test
    fun `a product read back records nothing old again and numbers its next event after its last`() {
        val loaded = read(storedProduct().id)!!
        assertTrue(loaded.getRecordedEvents().isEmpty())

        loaded.changeDetails(ProductEventSamples.bareDetails, admin, now).done()

        assertEquals(listOf(8L), loaded.getRecordedEvents().map { it.sequence.value })
    }

    @Test
    fun `saving again adds only the new events`() {
        val loaded = read(storedProduct().id)!!
        loaded.changeDetails(ProductEventSamples.bareDetails, admin, now).done()

        save(loaded)

        assertEquals(8, streamLength(loaded.id))
        assertEquals(loaded.snapshot(), read(loaded.id)?.snapshot())
    }

    @Test
    fun `a product nobody saved is null`() {
        assertNull(read(transaction(db) { ids.nextProductId() }))
    }

    @Test
    fun `another kind of stream with the same id is not read as the product's`() {
        val saved = storedProduct()
        transaction(db) {
            // What the stock of a variant with the same number might look like (step 4).
            EventStoreTbl.insert {
                it[eventId] = UUID.randomUUID()
                it[aggregateName] = "INVENTORY"
                it[aggregateId] = saved.id.value.toString()
                it[sequence] = 1
                it[eventType] = "StockReceived"
                it[payloadVersion] = 1
                it[payload] = "{}"
                it[occurredOn] = now.atOffset(ZoneOffset.UTC)
                it[occurredByUserId] = 0
            }
        }

        assertEquals(saved.snapshot(), read(saved.id)?.snapshot())
    }

    @Test
    fun `two copies changed and saved one after the other - the second is refused and nothing of it is stored`() {
        val saved = storedProduct()
        val first = read(saved.id)!!
        val second = read(saved.id)!!

        first.changePrice(priceTag(1_690_000, 1_890_000), admin, now).done()
        second.changeDetails(ProductEventSamples.bareDetails, admin, now).done()
        second.unpublish(admin, now).done()
        save(first)

        val refused = assertFailsWith<ConcurrentChangeException> { save(second) }

        assertEquals("PRODUCT", refused.aggregateName.value)
        assertEquals(saved.id.value.toString(), refused.aggregateId.value)
        assertEquals(8, streamLength(saved.id))
        assertEquals(first.snapshot(), read(saved.id)?.snapshot())
    }
}
