package com.kiva.infrastructure.httpserver.controller.httprest.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kiva.application.usecase.product.usecase.command.PublishScheduledProductUc
import com.kiva.application.usecase.product.usecase.query.FindDueScheduledProductsUc
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.repository.ProductRepository
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.persistence.exposed.OfferQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `PATCH /admin/products/{productId}/status`, `DELETE /admin/products/{productId}` and the publish job over kiva_test
 * (step 1.6). The job is run the way PanelJobs runs it: the due candidates, then each one by itself.
 */
class WebV1AdminProductStatusOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2036-04-02T09:00:00Z"))
    private val stack = PanelTestStack(clock)
    private val catalog = StoredCatalog()
    private val db = stack.db

    @AfterEach
    fun deleteWhatWasStored() {
        catalog.deleteAll()
        stack.deleteWhatWasStored()
    }

    private val manager get() = stack.callerWith(Role.CATALOG_MANAGER)

    private suspend fun ApplicationTestBuilder.status(id: ProductId, body: String): HttpResponse = client.patch("$API_V1/admin/products/${id.value}/status") {
        header(HttpHeaders.Authorization, manager.bearer)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun ApplicationTestBuilder.remove(id: ProductId): HttpResponse =
        client.delete("$API_V1/admin/products/${id.value}") { header(HttpHeaders.Authorization, manager.bearer) }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun draft(colors: List<String> = listOf("lilac")): Product = catalog.newProduct(colors.map { NewColor(it) }, at = clock.instant())

    /** One run of the publish job. */
    private suspend fun runJob() {
        stack.findDueScheduled.execute(FindDueScheduledProductsUc.Qry).getOrNull()!!
            .forEach { stack.publishScheduled.execute(PublishScheduledProductUc.Cmd(it)) }
    }

    private fun eventTypes(id: ProductId): List<String> = transaction(db) {
        EventStoreTbl.selectAll().where { (EventStoreTbl.aggregateName eq "PRODUCT") and (EventStoreTbl.aggregateId eq id.value.toString()) }
            .orderBy(EventStoreTbl.sequence).map { it[EventStoreTbl.eventType] }
    }

    private fun auditCount(id: ProductId): Long = transaction(db) {
        AuditLogTbl.selectAll().where { (AuditLogTbl.entityType eq "PRODUCT") and (AuditLogTbl.entityId eq id.value.toString()) }.count()
    }

    @Test
    fun `scheduled for 10 minutes on it stays a draft - 11 minutes later the job publishes it at the moment it runs`() = stack.app {
        val bag = draft()
        val at = clock.instant().plus(Duration.ofMinutes(10))

        val response = status(bag.id, """{"status":"ACTIVE","publishAt":"$at"}""")

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("DRAFT", response.json().getValue("status").jsonPrimitive.content)
        assertEquals(at, Instant.parse(response.json().getValue("publishAt").jsonPrimitive.content))

        clock.advance(Duration.ofMinutes(5))
        runJob()
        assertEquals("DRAFT", catalog.read(bag.id).status.name)

        clock.advance(Duration.ofMinutes(6))
        runJob()
        val row = transaction(db) { ProductQmTbl.selectAll().where { ProductQmTbl.productId eq bag.id.value }.single() }
        assertEquals("ACTIVE", row[ProductQmTbl.status].name)
        assertEquals(clock.instant(), row[ProductQmTbl.publishedOn]?.toInstant())
        assertEquals(null, row[ProductQmTbl.publishAt])
        assertEquals(listOf("ProductCreated", "VariantAdded", "ProductPublishScheduled", "ProductPublished"), eventTypes(bag.id))
    }

    @Test
    fun `a product that lost its active color after being scheduled is not published - its schedule goes, with a WARN`() = stack.app {
        val bag = draft()
        status(bag.id, """{"status":"ACTIVE","publishAt":"${clock.instant().plus(Duration.ofMinutes(10))}"}""")
        catalog.change(bag.id) { it.deactivateVariant(it.variants.single().id, catalog.staff, clock.instant()) }
        clock.advance(Duration.ofMinutes(11))

        val logger = LoggerFactory.getLogger(PublishScheduledProductUc::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            runJob()
        } finally {
            logger.detachAppender(appender)
        }

        val stored = catalog.read(bag.id)
        assertEquals("DRAFT", stored.status.name)
        assertEquals(null, stored.publishAt)
        val warn = appender.list.single { it.level == Level.WARN }.formattedMessage
        assertTrue("product ${bag.id.value} was due to be published" in warn && "its schedule was dropped" in warn, warn)
    }

    @Test
    fun `two runs of the job on one product at the same moment publish it once`() {
        val bag = draft()
        catalog.change(bag.id) { it.schedulePublish(clock.instant().plus(Duration.ofMinutes(1)), catalog.staff, clock.instant()) }
        clock.advance(Duration.ofMinutes(2))
        val barrier = CyclicBarrier(2)
        val bothReadFirst = object : ProductRepository by ProductRepositoryImpl() {
            override fun get(id: ProductId): Product? = ProductRepositoryImpl().get(id).also { barrier.await(10, TimeUnit.SECONDS) }
        }
        val job = PublishScheduledProductUc(ExposedTransactionManager(db), bothReadFirst, clock)
        val outcomes = arrayOfNulls<PublishScheduledProductUc.Outcome>(2)

        (0..1).map { i -> thread { outcomes[i] = runBlocking { job.execute(PublishScheduledProductUc.Cmd(bag.id)).getOrNull() } } }.forEach { it.join() }

        assertEquals(setOf(PublishScheduledProductUc.Outcome.PUBLISHED, PublishScheduledProductUc.Outcome.NOTHING), outcomes.toSet())
        assertEquals(1, eventTypes(bag.id).count { it == "ProductPublished" })
    }

    @Test
    fun `DELETE archives with 204 - again 204 with no event and no change log row`() = stack.app {
        val bag = catalog.change(draft().id) { it.publish(catalog.staff, clock.instant()) }

        val first = remove(bag.id)
        val events = eventTypes(bag.id).size
        val rows = auditCount(bag.id)
        val second = remove(bag.id)

        assertEquals(HttpStatusCode.NoContent, first.status, first.bodyAsText())
        assertEquals(HttpStatusCode.NoContent, second.status)
        assertEquals("ProductArchived", eventTypes(bag.id).last())
        assertEquals(events, eventTypes(bag.id).size)
        assertEquals(1, rows)
        assertEquals(rows, auditCount(bag.id))
        assertEquals(HttpStatusCode.NotFound, remove(ProductId.constructFromPersisted(987_654_321)).status)
    }

    @Test
    fun `an archived product's color in a cart is no longer purchasable`() = stack.app {
        val bag = catalog.change(draft().id) { it.publish(catalog.staff, clock.instant()) }
        val variantId = bag.variants.single().id
        catalog.stock(variantId) { it.adjust(StockDelta.constructFromPersisted(3), AdjustmentReason.RESTOCK, null, catalog.staff, clock.instant()) }
        assertTrue(transaction(db) { OfferQmRepositoryImpl().findOffers(listOf(variantId)).getValue(variantId).offer.isPurchasable })

        remove(bag.id)

        assertFalse(transaction(db) { OfferQmRepositoryImpl().findOffers(listOf(variantId)).getValue(variantId).offer.isPurchasable })
    }

    @Test
    fun `the table's refusals over HTTP - 422 with their codes, 400 PUBLISH_AT_NOT_ALLOWED on publishAt`() = stack.app {
        val active = catalog.change(draft().id) { it.publish(catalog.staff, clock.instant()) }
        val bare = draft(colors = emptyList())

        val again = status(active.id, """{"status":"ACTIVE"}""")
        val noColor = status(bare.id, """{"status":"ACTIVE"}""")
        val withPublishAt = status(active.id, """{"status":"ACTIVE","publishAt":"${clock.instant().plusSeconds(600)}"}""")

        assertEquals(HttpStatusCode.UnprocessableEntity to "PRODUCT_ALREADY_ACTIVE", again.status to again.json().getValue("code").jsonPrimitive.content)
        assertEquals(HttpStatusCode.UnprocessableEntity to "NO_ACTIVE_VARIANT", noColor.status to noColor.json().getValue("code").jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, withPublishAt.status)
        val error = withPublishAt.json().getValue("errors").jsonArray.single().jsonObject
        assertEquals("publishAt" to "PUBLISH_AT_NOT_ALLOWED", error.getValue("field").jsonPrimitive.content to error.getValue("code").jsonPrimitive.content)
        assertEquals(JsonNull, status(active.id, """{"status":"DRAFT"}""").json().getValue("publishAt"))
    }

    @Test
    fun `the database refuses a publishAt on a product in the shop`() {
        val bag = catalog.change(draft().id) { it.publish(catalog.staff, clock.instant()) }

        assertFailsWith<ExposedSQLException> {
            transaction(db) {
                ProductQmTbl.update({ ProductQmTbl.productId eq bag.id.value }) { it[publishAt] = clock.instant().plusSeconds(600).atOffset(ZoneOffset.UTC) }
            }
        }
    }
}
