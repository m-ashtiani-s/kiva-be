package com.kiva.infrastructure.ops

import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.order.usecase.command.CancelMyOrderUc
import com.kiva.application.usecase.product.ProductInput
import com.kiva.application.usecase.product.usecase.command.CreateProductUc
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.controller.httprest.web.PanelTestStack
import com.kiva.infrastructure.persistence.exposed.tbl.CategoryTbl
import com.kiva.infrastructure.persistence.exposed.tbl.MaterialTbl
import com.kiva.infrastructure.seed.DevCatalogData
import com.kiva.infrastructure.seed.DevDiscountCodeSeeder
import com.kiva.infrastructure.seed.SeededCatalog
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * tools/ops/purge_seed.sql (LAUNCH.md, step 6.7) on kiva_test: the 16 sample bags and the sample codes as the seeders
 * make them, a real product beside them, test orders bought the way the shop buys, and then the script, checked and
 * rolled back in one transaction.
 *
 * kiva_test is shared, and other tests leave payments, notes and change-log rows in it on purpose; the script removes
 * all of those, so it never runs outside that rolled-back transaction. In it, before the script, the open payments and
 * stock holds other tests left are settled, as they would be on a server before launch.
 */
class PurgeSeedSqlOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-21T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val db = TestDatabase.database
    private val purge: String = Files.readString(Path.of("tools/ops/purge_seed.sql"))

    // What other tests had left before this one started.
    private var lastPaymentBefore = 0L
    private var lastEventBefore = 0L

    @BeforeEach
    fun seed() {
        lastPaymentBefore = transaction(db) { long("SELECT coalesce(max(\"pId\"), 0) FROM payment_tbl") }
        lastEventBefore = transaction(db) { long("SELECT coalesce(max(\"pId\"), 0) FROM eventstore_tbl") }
        runBlocking {
            seeded.seeder.seed()
            DevDiscountCodeSeeder(ExposedTransactionManager(db), clock).seed()
            seeded.createProduct.execute(realBag()).getOrNull() ?: error("the real bag was refused")
        }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        seeded.deleteSeeded()
        seeded.deleteProducts(listOf(REAL_SLUG))
    }

    private val purchase get() = panel.purchase
    private val nilaOlive by lazy { purchase.variantOf("nila-crossbody-bag", "olive") }
    private val mahakBlack by lazy { purchase.variantOf("mahak-shoulder-bag", "black") }
    private val realBlack by lazy { purchase.variantOf(REAL_SLUG, "black") }

    /** A product the owner made: a sample bag's texts under its own code and address. */
    private fun realBag(): CreateProductUc.Cmd {
        val bag = DevCatalogData.bags.first()
        val (categoryId, materialId) = transaction(db) {
            CategoryTbl.select(CategoryTbl.id).where { CategoryTbl.slug eq bag.categorySlug }.single()[CategoryTbl.id].value to
                MaterialTbl.select(MaterialTbl.id).where { MaterialTbl.slug eq bag.materialSlug }.single()[MaterialTbl.id].value
        }
        return CreateProductUc.Cmd(
            fields = ProductInput.Fields(
                name = "کیف واقعی", slug = REAL_SLUG, sku = REAL_SKU, categoryId = categoryId, bagType = bag.bagType,
                materialId = materialId, materialDescription = bag.material, price = bag.price, compareAtPrice = null,
                shortDescription = null, description = DevCatalogData.description(bag), highlights = DevCatalogData.highlights(bag),
                specs = DevCatalogData.specsByBagType.getValue(bag.bagType), tags = null, newUntil = null, maxPerOrder = null,
                returnable = null, reservable = true, sortPriority = null, seo = null,
            ),
            status = "ACTIVE",
            variants = listOf(ProductInput.Variant(colorKey = "black", stockQuantity = 5)),
            actor = null,
        )
    }

    private fun customer(n: Int): UserId = purchase.customer(PanelTestStack.phone(n))

    private fun cancel(userId: UserId, code: OrderCode) {
        runBlocking { purchase.myOrderUseCases.cancelMyOrder.execute(CancelMyOrderUc.Cmd(userId, code.value, "CHANGED_MIND", null)) }
            .getOrNull() ?: error("not cancelled")
    }

    // ============ running the script ============

    /** What the script said (its NOTICE lines), and the connection to look at the database with before it rolls back. */
    private class Purged(val notices: List<String>, val sql: Connection)

    /** Runs [purge] after [before], hands what it did to [check], and rolls everything back. */
    private fun <T> purged(before: String = "", check: Purged.() -> T): T = transaction(db) {
        val sql = connection.connection as Connection
        try {
            sql.createStatement().use {
                it.execute(
                    "UPDATE payment_tbl SET status = 'EXPIRED', \"failureReason\" = 'EXPIRED', \"settledOn\" = now() " +
                        "WHERE \"pId\" <= $lastPaymentBefore AND status IN ('INITIATED', 'REDIRECTED', 'VERIFYING')",
                )
                it.execute(
                    "DELETE FROM eventstore_tbl WHERE \"pId\" <= $lastEventBefore AND \"aggregateName\" = 'INVENTORY' " +
                        "AND \"eventType\" IN ('StockReserved', 'ReservationReleased', 'StockShipped')",
                )
                if (before.isNotEmpty()) it.execute(before)
            }
            val notices = sql.createStatement().use { statement ->
                statement.execute(purge)
                generateSequence(statement.warnings) { it.nextWarning }.map { it.message.orEmpty() }.toList()
            }
            Purged(notices, sql).check()
        } finally {
            rollback()
        }
    }

    private fun Purged.long(query: String): Long = sql.createStatement().use { s -> s.executeQuery(query).use { it.next(); it.getLong(1) } }

    private fun Purged.strings(query: String): List<String> =
        sql.createStatement().use { s -> s.executeQuery(query).use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() } }

    private fun JdbcTransaction.long(query: String): Long =
        exec(query) { it.next(); it.getLong(1) } ?: error("no row")

    // ============ what goes and what stays ============

    @Test
    fun `the sample bags, the test orders and all about them go - the real product, the users and the settings stay`() {
        val buyer = customer(301)
        val first = panel.paidOrders.paid(buyer, nilaOlive)
        cancel(buyer, panel.paidOrders.paid(buyer, realBlack))
        val shopper = customer(302)
        listOf(mahakBlack, realBlack).forEach { runBlocking { purchase.addCartItem.execute(AddCartItemUc.Cmd(CartCaller.Customer(shopper), it, 1)) } }
        val orderSequence = transaction(db) { long("SELECT last_value FROM orderid_seq") }

        purged(
            before = "INSERT INTO ordernote_tbl (\"orderId\", text, \"authorId\", \"createdOn\") " +
                "SELECT \"orderId\", 'مشتری زنگ زد', ${buyer.value}, now() FROM orderqm_tbl WHERE code = '${first.value}'; " +
                "INSERT INTO auditlog_tbl (\"at\", \"actorId\", action, \"entityType\", \"entityId\") " +
                "VALUES (now(), ${buyer.value}, 'ORDER_NOTE_ADDED', 'ORDER', '${first.value}'), (now(), ${buyer.value}, 'SETTINGS_UPDATED', 'SETTINGS', 'shop')",
        ) {
            assertEquals(
                listOf<Long>(0, 0, 0, 0, 0, 0, 0, 0, 0),
                listOf(
                    "SELECT count(*) FROM orderqm_tbl", "SELECT count(*) FROM payment_tbl", "SELECT count(*) FROM paymentlog_tbl",
                    "SELECT count(*) FROM refund_tbl", "SELECT count(*) FROM ordernote_tbl", "SELECT count(*) FROM discountredemption_tbl",
                    "SELECT count(*) FROM idempotencykey_tbl", "SELECT count(*) FROM reservationgroup_tbl",
                    "SELECT count(*) FROM eventstore_tbl WHERE \"aggregateName\" = 'ORDER'",
                ).map { long(it) },
            )
            assertEquals(listOf(REAL_SLUG), strings("SELECT slug FROM productqm_tbl WHERE slug IN (${(seeded.slugs + REAL_SLUG).joinToString { "'$it'" }})"))
            assertEquals(0, long("SELECT count(*) FROM productvariantqm_tbl WHERE sku LIKE 'KV-10%'"))
            assertEquals(listOf(0L, 0L), listOf(long("SELECT sum(\"soldCount\") FROM productqm_tbl"), long("SELECT sum(\"soldCount\") FROM productvariantqm_tbl")))
            // The real bag keeps its events and its stock: 5, back from the cancelled order.
            assertTrue(long("SELECT count(*) FROM eventstore_tbl WHERE \"aggregateName\" = 'INVENTORY' AND \"aggregateId\" = '$realBlack'") > 0)
            assertEquals(5, long("SELECT \"onHand\" - reserved FROM productvariantqm_tbl WHERE \"variantId\" = $realBlack"))
            assertEquals(0, long("SELECT count(*) FROM eventstore_tbl WHERE \"aggregateName\" = 'INVENTORY' AND \"aggregateId\" = '$mahakBlack'"))
            // The shopper's cart keeps only the real bag; the users stay.
            assertEquals(listOf(realBlack.toString()), strings("SELECT i.\"variantId\" FROM cartitem_tbl i JOIN cart_tbl c ON c.\"pId\" = i.\"cartId\" WHERE c.\"userId\" = ${shopper.value}"))
            assertEquals(2, long("SELECT count(*) FROM user_tbl WHERE \"pId\" IN (${buyer.value}, ${shopper.value})"))
            assertEquals(listOf<String>(), strings("SELECT code FROM discountcode_tbl WHERE code IN ('KIVA10', 'WELCOME', 'PAEEZ15')"))
            assertEquals(listOf("SETTINGS"), strings("SELECT \"entityType\" FROM auditlog_tbl WHERE \"actorId\" = ${buyer.value}"))
            assertEquals(1, long("SELECT count(*) FROM shopsettings_tbl"))
            // Numbers go on, and the walls are back up.
            assertEquals(orderSequence, long("SELECT last_value FROM orderid_seq"))
            assertEquals(
                listOf("O", "O", "O"),
                strings("SELECT tgenabled FROM pg_trigger WHERE tgname IN ('paymentlog_no_change', 'ordernote_no_change', 'auditlog_guard') ORDER BY tgname"),
            )
            assertTrue(notices.any { it.startsWith("purge_seed: 16 sample bags") && "3 sample codes" in it }, notices.toString())
        }
    }

    @Test
    fun `the photos of the orders and of the bags alone are released - one a real product shows too stays`() {
        val media = listOf("med_purgeSeedPreShipment01", "med_purgeSeedBagOnly000001", "med_purgeSeedShared0000001")
        val rows = media.zip(listOf("PRE_SHIPMENT", "PRODUCT", "PRODUCT")).joinToString { (id, purpose) ->
            "('$id', '$purpose', 'IMAGE', 'image/webp', 10, 10, 1, '[{\"w\": 10}]', '2026/10/$id', 0, now(), now())"
        }
        val gallery = { ids: List<String> -> ids.joinToString(prefix = "'[", postfix = "]'") { "{\"type\": \"IMAGE\", \"view\": null, \"alt\": null, \"mediaId\": \"$it\"}" } }

        val attached = purged(
            before = "INSERT INTO media_tbl (\"mediaId\", purpose, type, \"mimeType\", width, height, bytes, variants, \"storageDir\", \"uploadedBy\", \"uploadedOn\", \"attachedOn\") VALUES $rows; " +
                "UPDATE productvariantqm_tbl SET media = ${gallery(media.drop(1))} WHERE \"variantId\" = $mahakBlack; " +
                "UPDATE productvariantqm_tbl SET media = ${gallery(media.takeLast(1))} WHERE \"variantId\" = $realBlack",
        ) {
            strings("SELECT \"mediaId\" FROM media_tbl WHERE \"mediaId\" IN (${media.joinToString { "'$it'" }}) AND \"attachedOn\" IS NOT NULL")
        }

        assertEquals(listOf("med_purgeSeedShared0000001"), attached)
    }

    @Test
    fun `a sample code whose label was changed stays, and a bag whose address was changed stays and is named`() {
        val notices = purged(
            before = "UPDATE discountcode_tbl SET label = 'کد خودمون' WHERE code = 'KIVA10'; " +
                "UPDATE productqm_tbl SET slug = 'termeh-wallet-real' WHERE slug = 'termeh-wallet'",
        ) {
            assertEquals(listOf("KIVA10"), strings("SELECT code FROM discountcode_tbl WHERE code IN ('KIVA10', 'WELCOME', 'PAEEZ15')"))
            assertEquals(1, long("SELECT count(*) FROM productqm_tbl WHERE slug = 'termeh-wallet-real'"))
            notices
        }

        assertTrue(notices.any { "KV-1008 (termeh-wallet-real)" in it }, notices.toString())
        assertTrue(notices.any { it.startsWith("purge_seed: 15 sample bags") && "2 sample codes" in it }, notices.toString())
        // Its slug changed back to the seeded one when the transaction rolled back, so the clean-up finds it.
    }

    // ============ when it refuses ============

    @Test
    fun `a payment still open stops it before anything goes`() {
        panel.paidOrders.place(customer(303), nilaOlive)

        val refused = assertFailsWith<SQLException> { purged { } }

        assertTrue("1 payment(s) are still open" in refused.message.orEmpty(), refused.message)
        assertEquals(16, transaction(db) { long("SELECT count(*) FROM productqm_tbl WHERE sku LIKE 'KV-10%'") })
    }

    @Test
    fun `a test order still holding a real product's stock stops it, and says which`() {
        val code = panel.paidOrders.paid(customer(304), realBlack)

        val refused = assertFailsWith<SQLException> { purged { } }

        assertTrue("still hold stock of real products ($REAL_SKU-BLACK for ${code.value})" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a sample bag still held by a test order is no reason to stop - it goes with the order`() {
        panel.paidOrders.paid(customer(305), nilaOlive)

        val left = purged { long("SELECT count(*) FROM productqm_tbl WHERE sku LIKE 'KV-10%'") + long("SELECT count(*) FROM orderqm_tbl") }

        assertEquals(0, left)
    }

    private companion object {
        const val REAL_SLUG = "purge-test-real-bag"
        const val REAL_SKU = "KV-9001"
    }
}
