package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.repository.VariantQm
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.orFail
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The read model as the use cases read it, on kiva_test, filled by saving products through the real repository. */
class ProductQmRepositoryImplTest {

    private val db = TestDatabase.database
    private val repository = ProductQmRepositoryImpl()
    private val catalog = StoredCatalog()
    private val staff = catalog.staff
    private val now = catalog.now

    @AfterEach
    fun deleteWhatWasStored() = catalog.deleteAll()

    private fun slug(value: String) = Slug.constructFromPersisted(value)
    private fun color(key: String) = ColorKey.constructFromPersisted(key)

    @Test
    fun `findVariant finds any product's color, one out of the shop too, and null for a color no product has`() {
        val product = catalog.newProduct(listOf(NewColor("lilac"), NewColor("black")))
        val black = product.variants.single { it.colorKey.value == "black" }
        catalog.change(product.id) { it.deactivateVariant(black.id, staff, now) }

        assertEquals(
            VariantQm(black.id, product.id, color("black"), black.sku, isActive = false),
            transaction(db) { repository.findVariant(black.id) },
        )
        assertNull(transaction(db) { repository.findVariant(VariantId.constructFromPersisted(999_999)) })
    }

    @Test
    fun `only products in the shop are counted, each once per category, material and active color`() {
        // In the shop: a shoulder bag in lilac and black (black then taken out), and a wallet in black.
        val shoulderBag = catalog.newProduct(listOf(NewColor("lilac"), NewColor("black")))
        catalog.change(shoulderBag.id) {
            it.publish(staff, now).orFail()
            it.deactivateVariant(shoulderBag.variants.single { v -> v.colorKey.value == "black" }.id, staff, now)
        }
        val wallet = catalog.newProduct(listOf(NewColor("black")), details = ProductEventSamples.bareDetails)
        catalog.change(wallet.id) { it.publish(staff, now) }
        // Not in the shop: a draft, and an archived product that was published once.
        catalog.newProduct(listOf(NewColor("lilac"), NewColor("cream")))
        val archived = catalog.newProduct(listOf(NewColor("cream")))
        catalog.change(archived.id) {
            it.publish(staff, now).orFail()
            it.archive(staff, now)
        }

        transaction(db) {
            assertEquals(mapOf(slug("shoulder") to 1, slug("wallet") to 1), repository.countActiveByCategory())
            assertEquals(mapOf(slug("faux-leather-matte") to 1, slug("genuine-cow-leather") to 1), repository.countActiveByMaterial())
            assertEquals(mapOf(color("lilac") to 1, color("black") to 1), repository.countActiveByColor())
        }
    }

    @Test
    fun `with nothing in the shop every count is empty`() {
        catalog.newProduct(listOf(NewColor("lilac")))

        transaction(db) {
            assertEquals(emptyMap(), repository.countActiveByCategory())
            assertEquals(emptyMap(), repository.countActiveByMaterial())
            assertEquals(emptyMap(), repository.countActiveByColor())
        }
    }
}
