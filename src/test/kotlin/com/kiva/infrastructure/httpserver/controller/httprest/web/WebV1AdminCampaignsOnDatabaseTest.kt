package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.priceTag
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.CampaignTbl
import com.kiva.infrastructure.seed.SeededCatalog
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Campaigns in the panel (`/admin/campaigns*`; ADMIN, CONTENT_EDITOR) over kiva_test (roadmap step 11.1): the form's
 * rules, the slug and the overlap (also two at once, against the EXCLUDE constraint), the products, the change log.
 */
class WebV1AdminCampaignsOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = MutableClock(Instant.parse("2026-10-20T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val catalog = StoredCatalog()

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

    private val editor by lazy { panel.callerWith(Role.CONTENT_EDITOR) }

    /** A product in the shop at [price], crossed out from [compareAt] when given, with [bags] left. */
    private fun published(price: Long, compareAt: Long? = null, bags: Int = 3): Product {
        val product = catalog.newProduct(listOf(NewColor("lilac")), priceTag = priceTag(price, compareAt), at = clock.now)
        catalog.change(product.id) { it.publish(catalog.staff, clock.now) }
        if (bags > 0) catalog.stock(product.variants.single().id) { it.adjust(StockDelta.constructFromPersisted(bags), AdjustmentReason.RESTOCK, null, catalog.staff, clock.now) }
        return product
    }

    private fun body(slug: String = "paeez-1405", starts: String = "2026-10-21T00:00:00Z", ends: String = "2026-11-01T00:00:00Z", extra: String = ""): String =
        """{"slug":"$slug","title":"جشنواره پاییزه","subtitle":"تا پایان جشنواره فرصت داری","icon":"fire","startsAt":"$starts","endsAt":"$ends"$extra}"""

    private suspend fun ApplicationTestBuilder.create(body: String, caller: PanelTestStack.Caller = editor): HttpResponse =
        client.post("$API_V1/admin/campaigns") {
            header(HttpHeaders.Authorization, caller.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.update(id: Any, body: String): HttpResponse =
        client.put("$API_V1/admin/campaigns/$id") {
            header(HttpHeaders.Authorization, editor.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.fieldErrors(): List<String> =
        json()["errors"]?.jsonArray.orEmpty().map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" }

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun auditOf(id: String): List<Pair<String, JsonElement?>> = transaction(db) {
        AuditLogTbl.selectAll().where { (AuditLogTbl.entityType eq "CAMPAIGN") and (AuditLogTbl.entityId eq id) }
            .orderBy(AuditLogTbl.id).map { it[AuditLogTbl.action] to it[AuditLogTbl.changes] }
    }

    @Test
    fun `made, replaced and deleted - each with its row in the change log - the products the shop would show, biggest discount first`() = panel.app {
        val small = published(900_000, compareAt = 1_000_000)
        val big = published(700_000, compareAt = 1_000_000)
        val soldOut = published(500_000, compareAt = 1_000_000, bags = 0)
        val full = published(800_000)

        val made = create(body(extra = ""","productIds":[${small.id.value},${big.id.value},${soldOut.id.value},${full.id.value}]"""))

        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        val campaign = made.json()
        assertEquals(ContractSchemas.propertiesOf("Campaign") + "productIds", campaign.keys)
        val id = campaign.text("id")!!
        assertEquals(listOf(big.id.value, small.id.value).map { it.toString() }, campaign.getValue("products").jsonArray.map { it.jsonObject.text("id") })
        assertEquals(listOf("/products?campaign=paeez-1405", "true", clock.now.toString()), listOf(campaign.text("seeAllUrl"), campaign.text("isActive"), campaign.text("serverTime")))
        assertEquals(4, campaign.getValue("productIds").jsonArray.size)

        val replaced = update(id, """{"slug":"paeez-1405","title":"جشنواره پاییزه","startsAt":"2026-10-21T00:00:00Z","endsAt":"2026-11-02T00:00:00Z"}""")
        assertEquals(HttpStatusCode.OK, replaced.status, replaced.bodyAsText())
        val after = replaced.json()
        assertEquals(listOf<String?>(null, null, "2026-11-02T00:00:00Z"), listOf(after.text("subtitle"), after.text("icon"), after.text("endsAt")), "PUT replaces the whole campaign")
        assertTrue(after.getValue("productIds").jsonArray.isEmpty())
        assertEquals(HttpStatusCode.OK, update(id, """{"slug":"paeez-1405","title":"جشنواره پاییزه","startsAt":"2026-10-21T00:00:00Z","endsAt":"2026-11-02T00:00:00Z"}""").status)

        assertEquals(HttpStatusCode.NoContent, client.delete("$API_V1/admin/campaigns/$id") { header(HttpHeaders.Authorization, editor.bearer) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("$API_V1/admin/campaigns/$id") { header(HttpHeaders.Authorization, editor.bearer) }.status)
        assertEquals(listOf("CAMPAIGN_CREATED", "CAMPAIGN_UPDATED", "CAMPAIGN_DELETED"), auditOf(id).map { it.first }, "a save that changes nothing writes no row")
        val changed = auditOf(id)[1].second!!.jsonArray.map { it.jsonObject.text("field") }.toSet()
        assertEquals(setOf("subtitle", "icon", "endsAt", "productIds"), changed)
    }

    @Test
    fun `the form - every problem at once - ends before it starts 400 DATE_RANGE_INVALID - an archived product 400 on its index - the slug again 409`() = panel.app {
        val archived = published(900_000, compareAt = 1_000_000)
        catalog.change(archived.id) { it.archive(catalog.staff, clock.now) }

        val bad = create("""{"slug":"Paeez","title":"","startsAt":"2026-11-01T00:00:00Z","endsAt":"2026-10-01T00:00:00Z","productIds":[5,5]}""")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals(setOf("slug SLUG_INVALID", "title FIELD_REQUIRED", "endsAt DATE_RANGE_INVALID", "productIds.1 LIST_ITEM_DUPLICATE"), bad.fieldErrors().toSet())

        val withArchived = create(body(extra = ""","productIds":[${archived.id.value}]"""))
        assertEquals(listOf("productIds.0 PRODUCT_NOT_FOUND"), withArchived.fieldErrors())

        assertEquals(HttpStatusCode.Created, create(body(slug = "paeez-1405")).status)
        val again = create(body(slug = "paeez-1405", starts = "2027-01-01T00:00:00Z", ends = "2027-02-01T00:00:00Z"))
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("DUPLICATE_SLUG", again.json().text("code"))
        assertEquals(HttpStatusCode.NotFound, update("abc", body()).status)
        assertEquals(HttpStatusCode.NotFound, update(999_999, body()).status)
    }

    @Test
    fun `two active campaigns sharing a moment 409 CAMPAIGN_OVERLAP with the other's id - one switched off may - back to back may`() = panel.app {
        val first = create(body(slug = "paeez")).json().text("id")

        val overlapping = create(body(slug = "yalda", starts = "2026-10-31T00:00:00Z", ends = "2026-11-10T00:00:00Z"))
        assertEquals(HttpStatusCode.Conflict, overlapping.status)
        assertEquals(listOf("CAMPAIGN_OVERLAP", first), overlapping.json().let { listOf(it.text("code"), it.getValue("meta").jsonObject.text("campaignId")) })

        assertEquals(HttpStatusCode.Created, create(body(slug = "yalda", starts = "2026-10-31T00:00:00Z", ends = "2026-11-10T00:00:00Z", extra = ""","isActive":false""")).status)
        assertEquals(HttpStatusCode.Created, create(body(slug = "after", starts = "2026-11-01T00:00:00Z", ends = "2026-11-05T00:00:00Z")).status, "[) ranges: back to back")
    }

    @Test
    fun `two overlapping campaigns made at once, 10 times - one 201 and one 409 every time`() = panel.app {
        repeat(10) { round ->
            transaction(db) { CampaignTbl.deleteAll() }
            val statuses = coroutineScope {
                listOf("a$round", "b$round").map { slug -> async(Dispatchers.IO) { create(body(slug = "x-$slug")).status } }.awaitAll()
            }
            assertEquals(setOf(HttpStatusCode.Created, HttpStatusCode.Conflict), statuses.toSet(), "round $round: $statuses")
        }
    }

    @Test
    fun `the campaigns are ADMIN and CONTENT_EDITOR's - OPERATOR and CATALOG_MANAGER 403`() = panel.app {
        assertEquals(HttpStatusCode.Created, create(body(slug = "by-admin"), panel.callerWith(Role.ADMIN)).status)
        assertEquals(HttpStatusCode.Forbidden, create(body(slug = "by-operator", starts = "2027-01-01T00:00:00Z", ends = "2027-02-01T00:00:00Z"), panel.callerWith(Role.OPERATOR)).status)
        assertEquals(HttpStatusCode.Forbidden, client.get("$API_V1/admin/campaigns") { header(HttpHeaders.Authorization, panel.callerWith(Role.CATALOG_MANAGER).bearer) }.status)
        val list = client.get("$API_V1/admin/campaigns") { header(HttpHeaders.Authorization, editor.bearer) }
        assertEquals(listOf("by-admin"), Json.parseToJsonElement(list.bodyAsText()).jsonArray.map { it.jsonObject.text("slug") })
    }
}
