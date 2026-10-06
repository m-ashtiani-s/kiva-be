package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.dto.PriceDto
import com.kiva.application.dto.ProductCardDto
import com.kiva.application.repository.PriceRange
import com.kiva.application.usecase.product.usecase.query.FindProductsUc
import com.kiva.application.usecase.product.usecase.query.ProductSorts
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.product.model.valueobject.BadgeCode
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.StockInfo
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.configureHttp
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WebV1ProductsTest {

    private val findProductsUc = mockk<FindProductsUc>()

    /** The real HTTP setup (JSON, CallId, StatusPages) with only the products route; no database, no Koin. */
    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp()
            routing { route(API_V1) { webV1Products(findProductsUc) } }
        }
        block()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun pageable(page: Int = 1, size: Int = 24, sort: String = ProductSorts.NEWEST) =
        Pageable.makeNew(page, size, sort, ProductSorts.names, 60).getOrNull()!!

    /** Answers [cards] for any query and keeps the query it got. */
    private fun answering(vararg cards: ProductCardDto): CapturingSlot<FindProductsUc.Qry> {
        val query = slot<FindProductsUc.Qry>()
        coEvery { findProductsUc.execute(capture(query)) } answers {
            Result.success(Page(cards.toList(), query.captured.pageable.page, query.captured.pageable.size, cards.size.toLong()))
        }
        return query
    }

    private fun card(
        stock: StockInfo = StockInfo(StockStatus.LOW_STOCK, shownQuantity = 3, maxOrderQuantity = 3),
        badges: List<BadgeCode> = listOf(BadgeCode.NEW, BadgeCode.SALE, BadgeCode.HAS_VIDEO, BadgeCode.LOW_STOCK),
    ) = ProductCardDto(
        id = 2,
        slug = "nila-crossbody-bag",
        sku = "KV-1002",
        name = "کراس‌بادی نیلا",
        category = ProductCardDto.CategoryRef(3, "cross", "کراس‌بادی"),
        bagType = BagType.CROSSBODY,
        price = PriceDto(1_450_000, 1_750_000, discountPercent = 17, discountAmount = 300_000),
        colors = listOf(
            ProductCardDto.ColorOption(201, "black", "مشکی", "#2B2930", isLight = false, sortOrder = 1, StockStatus.IN_STOCK),
            ProductCardDto.ColorOption(202, "lilac", "یاسی", "#C8B6E2", isLight = true, sortOrder = 4, StockStatus.LOW_STOCK),
        ),
        defaultColorKey = "black",
        stock = stock,
        badges = badges,
        isNew = true,
        hasVideo = true,
        soldCount = 0,
        freeShippingEligible = false,
    )

    @Test
    fun `the parameters reach the use case as they came - lists split, only true turns a flag on`() = withApp {
        val query = answering()

        client.get(
            "$API_V1/products?q=%DA%A9%DB%8C%D9%81&category=shoulder,%20cross,&color=lilac&color=black&minPrice=900&maxPrice=" +
                "&onSale=true&inStock=yes&isNew=TRUE&bagType=HOBO&material=satin&occasion=DAILY,WORK&ids=12,7&page=2&size=12&sort=price_asc",
        )

        assertEquals(
            FindProductsUc.Qry(
                pageable = pageable(page = 2, size = 12, sort = ProductSorts.PRICE_ASC),
                q = "کیف",
                categories = listOf("shoulder", "cross"),
                colors = listOf("lilac", "black"),
                minPrice = "900",
                maxPrice = "",
                onSale = true,
                inStock = false,
                isNew = true,
                hasVideo = false,
                bagTypes = listOf("HOBO"),
                materials = listOf("satin"),
                occasions = listOf("DAILY", "WORK"),
                ids = listOf("12", "7"),
            ),
            query.captured,
        )
    }

    @Test
    fun `without paging parameters it is the first 24, newest first`() = withApp {
        val query = answering()

        val response = client.get("$API_V1/products")

        assertEquals(FindProductsUc.Qry(pageable()), query.captured)
        assertEquals(
            json("""{"items":[],"meta":{"page":1,"size":24,"totalItems":0,"totalPages":0,"hasNext":false},"sort":"newest"}"""),
            response.json(),
        )
    }

    @Test
    fun `paging outside the contract is a 400 before the use case is asked`() = withApp {
        val response = client.get("$API_V1/products?size=61&sort=cheapest")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(
            listOf("size" to "PAGE_SIZE_INVALID", "sort" to "SORT_NOT_ALLOWED"),
            response.json().jsonObject["errors"]!!.jsonArray.map { it.jsonObject["field"]!!.jsonPrimitive.content to it.jsonObject["code"]!!.jsonPrimitive.content },
        )
        coVerify(exactly = 0) { findProductsUc.execute(any()) }
    }

    @Test
    fun `a wrong filter is the use case's 400, every problem under its parameter`() = withApp {
        coEvery { findProductsUc.execute(any()) } returns Result.failure(
            FindProductsUc.Failure.InvalidInput(listOf(FieldFailure("maxPrice", PriceRange.Failure.MinAboveMax))),
        )

        val response = client.get("$API_V1/products?minPrice=900&maxPrice=100")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val problem = response.json().jsonObject
        assertEquals("VALIDATION_ERROR", problem["code"]!!.jsonPrimitive.content)
        assertEquals(
            json("""[{"field":"maxPrice","code":"PRICE_RANGE_INVALID","message":"حداقل قیمت نمی‌تونه از حداکثر قیمت بیشتر باشه"}]"""),
            problem["errors"],
        )
    }

    @Test
    fun `a card is the contract's ProductSummary, its badges and stock in Persian, the exact stock only when few are left`() = withApp {
        answering(card())

        val item = client.get("$API_V1/products").json().jsonObject["items"]!!.jsonArray.single()

        assertEquals(
            json(
                """
                {"id":2,"slug":"nila-crossbody-bag","sku":"KV-1002","name":"کراس‌بادی نیلا","url":"/product/nila-crossbody-bag",
                 "category":{"id":3,"slug":"cross","name":"کراس‌بادی"},"bagType":"CROSSBODY",
                 "price":{"price":1450000,"compareAtPrice":1750000,"discountPercent":17,"discountAmount":300000,"currency":"IRT"},
                 "colors":[
                   {"variantId":201,"color":{"key":"black","name":"مشکی","hex":"#2B2930","isLight":false,"sortOrder":1},"stockStatus":"IN_STOCK"},
                   {"variantId":202,"color":{"key":"lilac","name":"یاسی","hex":"#C8B6E2","isLight":true,"sortOrder":4},"stockStatus":"LOW_STOCK"}],
                 "colorsCount":2,"defaultColorKey":"black","displayColorKey":"black",
                 "badges":[
                   {"code":"NEW","label":"جدید","tone":"DEFAULT"},
                   {"code":"SALE","label":"۱۷٪ تخفیف","tone":"SALE"},
                   {"code":"HAS_VIDEO","label":"ویدیو دارد","tone":"GLASS","icon":"play"},
                   {"code":"LOW_STOCK","label":"فقط ۳ عدد","tone":"DANGER"}],
                 "stock":{"status":"LOW_STOCK","availableQuantity":3,"maxOrderQuantity":3,"label":"فقط ۳ عدد در انبار باقی مونده"},
                 "isNew":true,"hasVideo":true,"soldCount":0,"freeShippingEligible":false}
                """,
            ),
            item,
        )
    }

    @Test
    fun `plenty in stock shows no number, and sold out says so`() = withApp {
        answering(
            card(StockInfo(StockStatus.IN_STOCK, null, 5), badges = emptyList()),
            card(StockInfo(StockStatus.OUT_OF_STOCK, null, 0), badges = listOf(BadgeCode.SOLD_OUT)),
        )

        val items = client.get("$API_V1/products").json().jsonObject["items"]!!.jsonArray.map { it.jsonObject }

        assertEquals(json("""{"status":"IN_STOCK","maxOrderQuantity":5,"label":"موجود در انبار"}"""), items[0]["stock"])
        assertEquals(json("""{"status":"OUT_OF_STOCK","maxOrderQuantity":0,"label":"ناموجود"}"""), items[1]["stock"])
        assertEquals(json("""[{"code":"SOLD_OUT","label":"تمام شد","tone":"DARK"}]"""), items[1]["badges"])
    }

    @Test
    fun `with a color filter the card shows the first asked color the product comes in`() = withApp {
        answering(card())

        val shown = client.get("$API_V1/products?color=white,lilac,black").json().jsonObject["items"]!!.jsonArray.single()
            .let { (it as JsonObject)["displayColorKey"]!!.jsonPrimitive.content }

        assertEquals("lilac", shown)
    }
}
