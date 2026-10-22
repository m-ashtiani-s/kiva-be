package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.infrastructure.auth.JwtAccessTokens
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.application.repository.ProductFilter
import com.kiva.application.usecase.product.usecase.query.FindProductsUc
import com.kiva.application.usecase.product.usecase.query.FindRelatedProductsUc
import com.kiva.application.usecase.product.usecase.query.GetProductFacetsUc
import com.kiva.application.usecase.product.usecase.query.GetProductUc
import com.kiva.application.usecase.product.usecase.query.ProductSorts
import com.kiva.common.pagination.Pageable
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import com.kiva.infrastructure.persistence.exposed.CategoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ColorRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.MaterialRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.SampleMedia
import com.kiva.infrastructure.persistence.exposed.ShopSettingsRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.orFail
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

/**
 * The shop's pictures (step 1.9) over kiva_test: cards, colors and the product page, read from the read model's copy of
 * each color's gallery. Products are made through the real repositories, their files stored in media_tbl.
 */
class WebV1ShopImagesOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = Clock.fixed(Instant.parse("2026-10-07T08:00:00Z"), ZoneOffset.UTC)
    private val catalog = StoredCatalog()
    private val readModel = ProductQmRepositoryImpl()
    private val findProductsUc = FindProductsUc(
        ExposedTransactionManager(db), readModel, CategoryRepositoryImpl(), ColorRepositoryImpl(), MaterialRepositoryImpl(), clock, ShopSettingsRepositoryImpl(),
    )
    private val getProductFacetsUc = GetProductFacetsUc(
        ExposedTransactionManager(db), readModel, CategoryRepositoryImpl(), ColorRepositoryImpl(), MaterialRepositoryImpl(), clock,
    )
    private val findRelatedProductsUc = FindRelatedProductsUc(
        ExposedTransactionManager(db), readModel, CategoryRepositoryImpl(), ColorRepositoryImpl(), clock, ShopSettingsRepositoryImpl(),
    )
    private val getProductUc = GetProductUc(
        ExposedTransactionManager(db), readModel, CategoryRepositoryImpl(), MaterialRepositoryImpl(), ColorRepositoryImpl(), clock, ShopSettingsRepositoryImpl(),
    )

    @AfterEach
    fun deleteWhatWasStored() = catalog.deleteAll()

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp()
            configureAuthentication(JwtAccessTokens(ByteArray(32) { 7 }))
            routing { route(API_V1) { webV1Products(findProductsUc, getProductFacetsUc, getProductUc, findRelatedProductsUc, siteUrl = "https://kiva.ir", mediaUrls = testMediaUrls) } }
        }
        block()
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private val base = PanelTestStack.MEDIA_BASE_URL

    /** A bag in the shop named [name], with these colors and galleries (the first color the default). */
    private fun bag(name: String, vararg colors: Pair<String, List<ProductMedia>>): Product {
        val details = ProductEventSamples.fullDetails.copy(name = ProductName.constructFromPersisted(name))
        val made = catalog.newProduct(colors.map { (key, media) -> NewColor(key, media = media) }, details = details)
        return catalog.change(made.id) { it.publish(catalog.staff, catalog.now) }
    }

    private suspend fun ApplicationTestBuilder.card(name: String, vararg query: String): JsonObject =
        client.get("$API_V1/products?q=$name${query.joinToString("") { "&$it" }}").json().getValue("items").jsonArray.single().jsonObject

    private fun JsonObject.url(): String = getValue("url").jsonPrimitive.content

    @Test
    fun `a card shows the default color's first photo and its second on hover - filtered by lilac, the lilac's`() = withApp {
        bag("آوینا", "black" to listOf(SampleMedia.photo(1), SampleMedia.photo(2, MediaView.BACK)), "lilac" to listOf(SampleMedia.photo(3)))

        val plain = card("آوینا")
        val lilac = card("آوینا", "color=lilac")

        assertEquals("$base/2026/10/med_00000000000000000000P1/1600.webp", plain.getValue("image").jsonObject.url())
        assertEquals("$base/2026/10/med_00000000000000000000P2/1600.webp", plain.getValue("hoverImage").jsonObject.url())
        assertEquals("lilac", lilac.getValue("displayColorKey").jsonPrimitive.content)
        assertEquals("$base/2026/10/med_00000000000000000000P3/1600.webp", lilac.getValue("image").jsonObject.url())
        assertEquals(JsonNull, lilac.getValue("hoverImage"))
        val black = plain.getValue("colors").jsonArray.first().jsonObject
        assertEquals("$base/2026/10/med_00000000000000000000P1/480.webp", black.getValue("imageUrl").jsonPrimitive.content)
        assertEquals("$base/2026/10/med_00000000000000000000P1/160.webp", black.getValue("thumbnailUrl").jsonPrimitive.content)
    }

    @Test
    fun `a product without photos has no picture, as before - a color without photos has no imageUrl`() = withApp {
        bag("بی‌عکس", "black" to emptyList())
        bag("نیمه‌عکس", "black" to listOf(SampleMedia.photo(4)), "cream" to emptyList())

        val none = card("بی‌عکس")
        val half = card("نیمه‌عکس").getValue("colors").jsonArray.map { it.jsonObject }

        assertEquals(JsonNull, none.getValue("image"))
        assertEquals(JsonNull, none.getValue("hoverImage"))
        assertEquals(JsonNull, half.last().getValue("imageUrl"))
        assertEquals(JsonNull, half.last().getValue("thumbnailUrl"))
    }

    @Test
    fun `a video is in the gallery as VIDEO with its poster and length - never the card's picture`() = withApp {
        val bag = bag("ویدیودار", "black" to listOf(ProductEventSamples.video, SampleMedia.photo(5)))

        val card = card("ویدیودار")
        val page = client.get("$API_V1/products/${bag.slug.value}").json()

        assertEquals("$base/2026/10/med_00000000000000000000P5/1600.webp", card.getValue("image").jsonObject.url())
        assertEquals(true, card.getValue("hasVideo").jsonPrimitive.content.toBoolean())
        val gallery = page.getValue("variants").jsonArray.single().jsonObject.getValue("media").jsonArray.map { it.jsonObject }
        assertEquals(listOf("VIDEO", "IMAGE"), gallery.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("$base/${SampleMedia.video.posterPath}", gallery.first().getValue("posterUrl").jsonPrimitive.content)
        assertEquals(14, gallery.first().getValue("durationSec").jsonPrimitive.int)
        assertEquals("$base/2026/10/med_00000000000000000000P5/1600.webp", page.getValue("image").jsonObject.url())
    }

    @Test
    fun `a page of cards reads its pictures with its colors - no statement more for 24 cards than for one`() {
        repeat(24) { bag("پرستو", "black" to listOf(SampleMedia.photo(6), SampleMedia.photo(7)), "lilac" to listOf(SampleMedia.photo(8))) }

        fun statementsFor(size: Int): Int = transaction(db) {
            val before = statementCount
            readModel.find(ProductFilter(words = listOf("پرستو")), Pageable.makeNew(1, size, ProductSorts.DEFAULT, ProductSorts.names).orFail())
            statementCount - before
        }

        assertEquals(statementsFor(1), statementsFor(24))
    }
}
