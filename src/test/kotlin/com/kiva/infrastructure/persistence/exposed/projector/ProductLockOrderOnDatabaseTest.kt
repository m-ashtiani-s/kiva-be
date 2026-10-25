package com.kiva.infrastructure.persistence.exposed.projector

import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.application.usecase.product.ProductInput
import com.kiva.application.usecase.product.usecase.command.UpdateVariantUc
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.html.JsoupHtmlSanitizer
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.persistence.exposed.AuditTrailImpl
import com.kiva.infrastructure.persistence.exposed.MediaRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lock 7 for the panel's color changes (roadmap section 3, row 20): a change of a color takes its product's row before
 * the color's, the order every stock writer (an order being placed) takes them in. So while someone holds the
 * product's row, a color change waits there, without the color's row; were the order reversed, an order and a color
 * change of the same bag could each hold one row and wait for the other.
 */
class ProductLockOrderOnDatabaseTest {

    private val db = TestDatabase.database
    private val catalog = StoredCatalog()

    @AfterEach
    fun deleteWhatWasStored() = catalog.deleteAll()

    private val updateVariant = UpdateVariantUc(
        ExposedTransactionManager(db), ProductRepositoryImpl(), ProductInput(JsoupHtmlSanitizer), MediaAttachments(MediaRepositoryImpl()), AuditTrailImpl(),
        Clock.systemUTC(),
    )

    /** Whether another transaction holds the color's row: asked without waiting. */
    private fun colorRowIsHeld(variantId: Long): Boolean = transaction(db) {
        try {
            TransactionManager.current().exec("SELECT 1 FROM productvariantqm_tbl WHERE \"variantId\" = $variantId FOR UPDATE NOWAIT")
            false
        } catch (e: Exception) {
            true
        }
    }

    @Test
    fun `a color change waits at its product's row - it never holds the color's row first`() {
        val bag = catalog.newProduct(listOf(NewColor("lilac")))
        val color = bag.variants.single()
        val productHeld = CountDownLatch(1)
        val release = CountDownLatch(1)

        // Someone (an order being placed) holds the product's row.
        val holder = thread {
            transaction(db) {
                ProductQmSummary.lock(bag.id.value)
                productHeld.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
        }
        productHeld.await(10, TimeUnit.SECONDS)
        val change = thread {
            runBlocking {
                updateVariant.execute(UpdateVariantUc.Cmd(bag.id.value.toString(), color.id.value.toString(), ProductInput.Variant("lilac", priceOverride = 1_500_000), PanelActor(catalog.staff, null)))
            }
        }
        // Long enough for the change to reach its first lock.
        Thread.sleep(1_500)

        val heldMeanwhile = colorRowIsHeld(color.id.value)
        release.countDown()
        holder.join()
        change.join()

        assertFalse(heldMeanwhile, "the color change took the color's row while its product's row was someone else's")
        assertTrue(catalog.read(bag.id).variants.single().ownPriceTag != null, "and then it went through")
    }
}
