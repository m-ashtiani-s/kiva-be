package com.kiva.infrastructure.job

import com.kiva.application.port.RequestHash
import com.kiva.application.port.SmsKind
import com.kiva.application.port.SmsSender
import com.kiva.application.usecase.address.AddressFields
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.checkout.usecase.command.PlaceOrderUc
import com.kiva.application.usecase.stockalert.usecase.command.CancelMyStockAlertUc
import com.kiva.application.usecase.stockalert.usecase.command.CreateStockAlertUc
import com.kiva.application.usecase.stockalert.usecase.command.DeleteOldStockAlertsUc
import com.kiva.application.usecase.stockalert.usecase.command.NotifyStockAlertUc
import com.kiva.application.usecase.stockalert.usecase.query.FindDueStockAlertsUc
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertStatus
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.controller.httprest.web.PanelTestStack
import com.kiva.infrastructure.httpserver.controller.httprest.web.RecordingSmsSender
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.persistence.exposed.tbl.StockAlertTbl
import com.kiva.infrastructure.seed.SeededCatalog
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * «موجود شد» over kiva_test (roadmap step 10.2): the job finds the subscriptions whose bag is back, by any way, tells
 * each once after the commit, waits out the quiet hours, doesn't run at all without a pattern, takes 200 a run, and the
 * nightly clean-up forgets the old ended ones. One run at a time, with a clock the test moves.
 */
class EngagementJobsOnDatabaseTest {

    private val db = TestDatabase.database
    // 11:30 in Tehran: not a quiet hour.
    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val catalog = StoredCatalog()
    private val sms = RecordingSmsSender()
    private var patternSet = true
    private val sender = object : SmsSender by sms {
        override fun canSend(kind: SmsKind): Boolean = patternSet
    }
    private val repository = panel.stockAlertRepository
    private val jobs = EngagementJobs(
        FindDueStockAlertsUc(panel.transactions, repository),
        NotifyStockAlertUc(panel.transactions, repository, panel.productQm, panel.users, sender, PanelTestStack.SITE_URL, clock),
        DeleteOldStockAlertsUc(panel.transactions, repository, clock),
        sender,
        clock,
    )

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        catalog.deleteAll()
        seeded.deleteSeeded()
    }

    private fun published(colors: List<String>): Product {
        val product = catalog.newProduct(colors.map { NewColor(it) }, at = clock.now)
        catalog.change(product.id) { it.publish(catalog.staff, clock.now) }
        return product
    }

    private fun Product.variant(key: String): Long = variants.single { it.colorKey.value == key }.id.value

    private fun restock(variantId: Long, bags: Int) = catalog.stock(VariantId.constructFromPersisted(variantId)) {
        it.adjust(StockDelta.constructFromPersisted(bags), if (bags > 0) AdjustmentReason.RESTOCK else AdjustmentReason.DAMAGED, null, catalog.staff, clock.now)
    }

    private fun subscribe(n: Int, productId: Long, variantId: Long? = null): Long {
        val who = panel.caller(PanelTestStack.phone(900 + n)).user.id
        val made = runBlocking { panel.stockAlertUseCases.create.execute(CreateStockAlertUc.Cmd(who, productId.toString(), variantId, null)) }
        return made.getOrNull()?.id ?: error("not subscribed: $made")
    }

    private fun statusOf(alertId: Long): StockAlertStatus = transaction(db) {
        StockAlertTbl.selectAll().where { StockAlertTbl.alertId eq alertId }.single()[StockAlertTbl.status]
    }

    private fun notifiedVariantOf(alertId: Long): Long? = transaction(db) {
        StockAlertTbl.selectAll().where { StockAlertTbl.alertId eq alertId }.single()[StockAlertTbl.notifiedVariantId]
    }

    private fun run() = runBlocking { jobs.notifyBackInStock() }

    @Test
    fun `the engagement's jobs and when they run`() {
        assertEquals(
            listOf("notify-back-in-stock" to Schedule.Every(Duration.ofMinutes(5)), "delete-old-stock-alerts" to Schedule.DailyAt(LocalTime.of(4, 45), com.kiva.domain.aggregate.order.model.policy.ShopCalendar.ZONE)),
            jobs.all().map { it.name to it.schedule },
        )
    }

    @Test
    fun `stock back by an adjustment - the next run tells them once, with the color's link - a second run tells nobody`() {
        val bag = published(listOf("lilac", "black"))
        val alert = subscribe(1, bag.id.value, bag.variant("lilac"))
        run()
        assertEquals(0, sms.backInStock.size, "nothing back yet")

        restock(bag.variant("lilac"), 2)
        run()
        run()

        assertEquals(StockAlertStatus.NOTIFIED, statusOf(alert))
        val (to, name, link) = sms.backInStock.single()
        assertEquals(PanelTestStack.phone(901), to.value)
        assertEquals(bag.details.name.value, name)
        assertEquals("${PanelTestStack.SITE_URL}/product/${bag.slug.value}?color=lilac", link)
    }

    @Test
    fun `«any color» - one of three back - told with that color`() {
        val bag = published(listOf("lilac", "black", "olive"))
        val alert = subscribe(1, bag.id.value)

        restock(bag.variant("olive"), 1)
        run()

        assertEquals(bag.variant("olive"), notifiedVariantOf(alert))
        assertTrue(sms.backInStock.single().third.endsWith("?color=olive"))
    }

    @Test
    fun `an unpaid order expires and gives the last bag back - the job tells them, no adjustment at all`() {
        val nilaOlive = panel.purchase.variantOf("nila-crossbody-bag", "olive")
        val nilaId = transaction(db) {
            com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl.selectAll()
                .where { com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl.variantId eq nilaOlive }
                .single()[com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl.productId]
        }
        // Only one left, and an unpaid order holds it.
        restock(nilaOlive, -(panel.purchase.availableOf(nilaOlive) - 1))
        val buyer = panel.purchase.customer(PanelTestStack.phone(950))
        runBlocking { panel.purchase.addCartItem.execute(AddCartItemUc.Cmd(CartCaller.Customer(buyer), nilaOlive, 1)) }.getOrNull() ?: error("not added")
        val key = UUID.randomUUID().toString()
        val placed = runBlocking {
            panel.purchase.placeOrder.execute(
                PlaceOrderUc.Cmd(
                    userId = buyer, idempotencyKey = key, requestHash = RequestHash.of(key), addressId = null,
                    newAddress = AddressFields(null, 8, 394, "خیابان ولیعصر، کوچه نسترن", null, null, "1965843117", "سارا محمدی", "09123456789", true, null, null),
                    newAddressAsDefault = null, saveNewAddress = false, messenger = PlaceOrderUc.MessengerFields("TELEGRAM", "09121112233", null, null, saveAsDefault = false),
                    shippingMethod = "POST", reserve = null, paymentGateway = "ZARINPAL", customerNote = null, gift = null, expectedPayable = null, acceptTerms = true,
                ),
            )
        }
        assertTrue(placed.getOrNull() != null, "$placed")
        assertEquals(0, panel.purchase.availableOf(nilaOlive))
        val alert = subscribe(1, nilaId, nilaOlive)

        clock.advance(Duration.ofMinutes(16))
        runBlocking { panel.purchase.jobs.expireUnpaidOrders() }
        run()

        assertEquals(StockAlertStatus.NOTIFIED, statusOf(alert))
        assertEquals(1, sms.backInStock.size)
    }

    @Test
    fun `found due, then called off or sold again before its turn - checked again under its lock, nobody told`() {
        val bag = published(listOf("lilac", "black"))
        val calledOff = subscribe(1, bag.id.value, bag.variant("lilac"))
        val soldAgain = subscribe(2, bag.id.value, bag.variant("black"))
        restock(bag.variant("lilac"), 1)
        restock(bag.variant("black"), 1)
        val due = runBlocking { FindDueStockAlertsUc(panel.transactions, repository).execute(FindDueStockAlertsUc.Qry()) }.getOrNull().orEmpty()
        assertEquals(setOf(calledOff, soldAgain), due.map { it.value }.toSet())

        // Between the finding and the telling: one is called off, the other's last bag goes.
        val owner = panel.caller(PanelTestStack.phone(901)).user.id
        runBlocking { panel.stockAlertUseCases.cancel.execute(CancelMyStockAlertUc.Cmd(owner, calledOff.toString())) }
        restock(bag.variant("black"), -1)
        val notify = NotifyStockAlertUc(panel.transactions, repository, panel.productQm, panel.users, sender, PanelTestStack.SITE_URL, clock)
        val outcomes = due.map { runBlocking { notify.execute(NotifyStockAlertUc.Cmd(it)) }.getOrNull() }

        assertEquals(listOf(NotifyStockAlertUc.Outcome.NOT_DUE, NotifyStockAlertUc.Outcome.NOT_DUE), outcomes)
        assertEquals(listOf(StockAlertStatus.CANCELLED, StockAlertStatus.ACTIVE), listOf(statusOf(calledOff), statusOf(soldAgain)))
        assertEquals(0, sms.backInStock.size)
    }

    @Test
    fun `23-00 in Tehran nobody is told - 08-05 they are`() {
        val bag = published(listOf("lilac"))
        val alert = subscribe(1, bag.id.value)
        restock(bag.variant("lilac"), 1)

        clock.advance(Duration.ofHours(11).plusMinutes(30)) // 23:00 in Tehran
        run()
        assertEquals(listOf(StockAlertStatus.ACTIVE, 0), listOf(statusOf(alert), sms.backInStock.size))

        clock.advance(Duration.ofHours(9).plusMinutes(5)) // 08:05 the next morning
        run()
        assertEquals(listOf(StockAlertStatus.NOTIFIED, 1), listOf(statusOf(alert), sms.backInStock.size))
    }

    @Test
    fun `without a pattern the job doesn't run - the subscription stays ACTIVE for when it is set`() {
        val bag = published(listOf("lilac"))
        val alert = subscribe(1, bag.id.value)
        restock(bag.variant("lilac"), 1)
        patternSet = false

        run()
        assertEquals(StockAlertStatus.ACTIVE, statusOf(alert))

        patternSet = true
        run()
        assertEquals(StockAlertStatus.NOTIFIED, statusOf(alert))
    }

    /** [n] «any color» subscriptions of [productId] by made-up accounts, made a second apart (the first the oldest). */
    private fun waiting(n: Int, productId: Long, status: StockAlertStatus = StockAlertStatus.ACTIVE, endedOn: Instant? = null) = transaction(db) {
        StockAlertTbl.batchInsert((1..n).toList()) { i ->
            this[StockAlertTbl.alertId] = 7_000_000L + i
            this[StockAlertTbl.userId] = 8_000_000L + i
            this[StockAlertTbl.productId] = productId
            this[StockAlertTbl.variantId] = null
            this[StockAlertTbl.status] = status
            this[StockAlertTbl.createdOn] = clock.now.minusSeconds(1000L - i).atOffset(ZoneOffset.UTC)
            if (status == StockAlertStatus.NOTIFIED) {
                this[StockAlertTbl.notifiedOn] = endedOn!!.atOffset(ZoneOffset.UTC)
                this[StockAlertTbl.notifiedVariantId] = 1
            }
        }
    }

    private fun countOf(status: StockAlertStatus): Long = transaction(db) {
        StockAlertTbl.selectAll().where { StockAlertTbl.status eq status }.count()
    }

    @Test
    fun `250 waiting - the first run tells the 200 oldest, the second the other 50`() {
        val bag = published(listOf("lilac"))
        restock(bag.variant("lilac"), 1)
        waiting(250, bag.id.value)
        try {
            run()
            assertEquals(200L, countOf(StockAlertStatus.NOTIFIED))
            assertEquals(StockAlertStatus.ACTIVE, statusOf(7_000_250L), "the newest waits for the next run")
            run()
            assertEquals(250L, countOf(StockAlertStatus.NOTIFIED))
        } finally {
            transaction(db) { exec("DELETE FROM stockalert_tbl WHERE \"alertId\" > 7000000 AND \"alertId\" <= 7000250") }
        }
    }

    @Test
    fun `the nightly clean-up forgets one told 91 days ago - not one told 89 days ago, nor one waiting for 100 days`() {
        val bag = published(listOf("lilac"))
        waiting(2, bag.id.value, StockAlertStatus.NOTIFIED, endedOn = clock.now.minus(Duration.ofDays(91)))
        transaction(db) { exec("UPDATE stockalert_tbl SET \"notifiedOn\" = '${clock.now.minus(Duration.ofDays(89))}' WHERE \"alertId\" = 7000002") }
        val old = subscribe(1, bag.id.value)
        transaction(db) { exec("UPDATE stockalert_tbl SET \"createdOn\" = '${clock.now.minus(Duration.ofDays(100))}' WHERE \"alertId\" = $old") }

        runBlocking { DeleteOldStockAlertsUc(panel.transactions, repository, clock).execute(DeleteOldStockAlertsUc.Cmd) }

        val left = transaction(db) { StockAlertTbl.selectAll().where { StockAlertTbl.productId eq bag.id.value }.map { it[StockAlertTbl.alertId] }.toSet() }
        assertEquals(setOf(7_000_002L, old), left)
        transaction(db) { exec("DELETE FROM stockalert_tbl WHERE \"alertId\" = 7000002") }
    }
}
