package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.dto.PriceDto
import com.kiva.application.dto.ProductCardDto
import com.kiva.application.dto.ProductDetailDto
import com.kiva.application.dto.ProductFacetsDto
import com.kiva.application.dto.ProductListDto
import com.kiva.application.repository.PriceRange
import com.kiva.application.usecase.product.ProductFilterQuery
import com.kiva.application.usecase.product.usecase.query.FindProductsUc
import com.kiva.application.usecase.product.usecase.query.FindRelatedProductsUc
import com.kiva.application.usecase.product.usecase.query.GetProductFacetsUc
import com.kiva.application.usecase.product.usecase.query.GetProductUc
import com.kiva.application.usecase.product.usecase.query.ProductSorts
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.material.model.valueobject.MaterialFamily
import com.kiva.domain.aggregate.product.model.valueobject.BadgeCode
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.ClosureType
import com.kiva.domain.aggregate.product.model.valueobject.Dimensions
import com.kiva.domain.aggregate.product.model.valueobject.MediaType
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.product.model.valueobject.Occasion
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.domain.aggregate.product.model.valueobject.ProductSpecs
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class WebV1ProductsTest {

    private val findProductsUc = mockk<FindProductsUc>()
    private val getProductFacetsUc = mockk<GetProductFacetsUc>()
    private val getProductUc = mockk<GetProductUc>()
    private val findRelatedProductsUc = mockk<FindRelatedProductsUc>()

    /** The real HTTP setup (JSON, CallId, StatusPages) with only the products route; no database, no Koin. */
    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp()
            routing { route(API_V1) { webV1Products(findProductsUc, getProductFacetsUc, getProductUc, findRelatedProductsUc, siteUrl = "https://kiva.ir") } }
        }
        block()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun pageable(page: Int = 1, size: Int = 24, sort: String = ProductSorts.NEWEST) =
        Pageable.makeNew(page, size, sort, ProductSorts.names, 60).getOrNull()!!

    /**
     * Answers [cards] for any query and keeps the query it got. The applied filters are [applied], or else the asked
     * colors named by their keys, as the card's picture depends on them.
     */
    private fun answering(vararg cards: ProductCardDto, applied: ProductListDto.AppliedFilters? = null): CapturingSlot<FindProductsUc.Qry> {
        val query = slot<FindProductsUc.Qry>()
        coEvery { findProductsUc.execute(capture(query)) } answers {
            val asked = query.captured
            Result.success(
                ProductListDto(
                    page = Page(cards.toList(), asked.pageable.page, asked.pageable.size, cards.size.toLong()),
                    applied = applied ?: ProductListDto.AppliedFilters(colors = asked.filters.colors.map { ProductListDto.Swatch(it, it, "#000000") }),
                ),
            )
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
                filters = ProductFilterQuery(
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
            json(
                """
                {"items":[],"meta":{"page":1,"size":24,"totalItems":0,"totalPages":0,"hasNext":false},
                 "title":"فروشگاه کیوا","breadcrumbs":[{"label":"خانه","url":"/"},{"label":"فروشگاه","url":null}],"appliedFilters":[],"sort":"newest","facets":null}
                """,
            ),
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
                   {"variantId":201,"color":{"key":"black","name":"مشکی","hex":"#2B2930","isLight":false,"sortOrder":1,"productCount":null},"stockStatus":"IN_STOCK"},
                   {"variantId":202,"color":{"key":"lilac","name":"یاسی","hex":"#C8B6E2","isLight":true,"sortOrder":4,"productCount":null},"stockStatus":"LOW_STOCK"}],
                 "colorsCount":2,"defaultColorKey":"black","displayColorKey":"black","image":null,
                 "badges":[
                   {"code":"NEW","label":"جدید","tone":"DEFAULT","icon":null},
                   {"code":"SALE","label":"۱۷٪ تخفیف","tone":"SALE","icon":null},
                   {"code":"HAS_VIDEO","label":"ویدیو دارد","tone":"GLASS","icon":"play"},
                   {"code":"LOW_STOCK","label":"فقط ۳ عدد","tone":"DANGER","icon":null}],
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

        assertEquals(json("""{"status":"IN_STOCK","availableQuantity":null,"maxOrderQuantity":5,"label":"موجود در انبار"}"""), items[0]["stock"])
        assertEquals(json("""{"status":"OUT_OF_STOCK","availableQuantity":null,"maxOrderQuantity":0,"label":"ناموجود"}"""), items[1]["stock"])
        assertEquals(json("""[{"code":"SOLD_OUT","label":"تمام شد","tone":"DARK","icon":null}]"""), items[1]["badges"])
    }

    @Test
    fun `with a color filter the card shows the first asked color the product comes in`() = withApp {
        answering(card())

        val shown = client.get("$API_V1/products?color=white,lilac,black").json().jsonObject["items"]!!.jsonArray.single()
            .let { (it as JsonObject)["displayColorKey"]!!.jsonPrimitive.content }

        assertEquals("lilac", shown)
    }

    // ============ one product's page ============

    private val photo = ProductMedia.constructFromPersisted(MediaType.IMAGE, "https://cdn.kiva.ir/p/2/black/front.webp", MediaView.FRONT, "نمای جلو")

    private fun page() = ProductDetailDto(
        card = card(StockInfo(StockStatus.LOW_STOCK, shownQuantity = 3, maxOrderQuantity = 3), badges = listOf(BadgeCode.SALE, BadgeCode.LOW_STOCK)),
        selectedColorKey = "lilac",
        shortDescription = "کراس‌بادی سبک برای هر روز",
        description = "<p>کراس‌بادی نیلا</p>",
        highlights = listOf("یراق‌آلات فلزی ضدزنگ"),
        material = ProductDetailDto.MaterialRef(5, "faux-leather-soft", "چرم مصنوعی سافت", MaterialFamily.FAUX_LEATHER),
        materialDescription = null,
        specs = ProductSpecs.constructFromPersisted(
            Dimensions.constructFromPersisted(22.0, 16.0, 8.5), 420, ClosureType.MAGNETIC_FLAP, "بند بلند قابل تنظیم", null,
            setOf(Occasion.TRAVEL, Occasion.DAILY),
        ),
        variants = listOf(
            ProductDetailDto.Variant(
                201, "KV-1002-BLACK", ProductDetailDto.Swatch("black", "مشکی", "#2B2930", false, 1), isDefault = true,
                PriceDto(1_450_000, 1_750_000, 17, 300_000), StockInfo(StockStatus.IN_STOCK, null, 5), listOf(photo), hasVideo = false,
            ),
            ProductDetailDto.Variant(
                202, "KV-1002-LILAC", ProductDetailDto.Swatch("lilac", "یاسی", "#C8B6E2", true, 4), isDefault = false,
                PriceDto(1_450_000, 1_750_000, 17, 300_000), StockInfo(StockStatus.LOW_STOCK, 3, 3), emptyList(), hasVideo = false,
            ),
        ),
        returnable = true,
        maxPerOrder = 5,
        publishedAt = Instant.parse("2026-10-06T08:00:00Z"),
        updatedAt = Instant.parse("2026-10-06T09:30:00Z"),
    )

    @Test
    fun `GET products by idOrSlug passes the segment and color on as they came`() = withApp {
        coEvery { getProductUc.execute(any()) } returns Result.success(page())

        client.get("$API_V1/products/KV-1002?color=lilac")

        coVerify(exactly = 1) { getProductUc.execute(GetProductUc.Qry("KV-1002", "lilac")) }
    }

    @Test
    fun `a page is the card, then its spec table, colors, breadcrumbs and the canonical address of its slug`() = withApp {
        coEvery { getProductUc.execute(any()) } returns Result.success(page())

        val body = client.get("$API_V1/products/2").json().jsonObject

        assertEquals(listOf("lilac", "lilac"), listOf("selectedColorKey", "displayColorKey").map { body[it]!!.jsonPrimitive.content })
        assertEquals(
            json(
                """
                [{"key":"MATERIAL","label":"جنس","value":"چرم مصنوعی سافت","swatches":null,"tone":null},
                 {"key":"DIMENSIONS","label":"ابعاد (طول × ارتفاع × عرض)","value":"۲۲ × ۱۶ × ۸٫۵ سانتی‌متر","swatches":null,"tone":null},
                 {"key":"WEIGHT","label":"وزن","value":"۴۲۰ گرم","swatches":null,"tone":null},
                 {"key":"CLOSURE","label":"نوع بسته شدن","value":"درب مگنتی","swatches":null,"tone":null},
                 {"key":"STRAP","label":"بند و دسته","value":"بند بلند قابل تنظیم","swatches":null,"tone":null},
                 {"key":"COLORS","label":"رنگ‌های موجود","value":"مشکی، یاسی","swatches":[
                   {"key":"black","name":"مشکی","hex":"#2B2930","isLight":false,"sortOrder":1,"productCount":null},
                   {"key":"lilac","name":"یاسی","hex":"#C8B6E2","isLight":true,"sortOrder":4,"productCount":null}],"tone":null},
                 {"key":"OCCASIONS","label":"مناسب برای","value":"روزمره، سفر","swatches":null,"tone":null},
                 {"key":"CATEGORY","label":"دسته‌بندی","value":"کراس‌بادی","swatches":null,"tone":null},
                 {"key":"SKU","label":"کد محصول","value":"KV-1002","swatches":null,"tone":null},
                 {"key":"STOCK","label":"موجودی انبار","value":"فقط ۳ عدد باقی مانده","tone":"DANGER","swatches":null}]
                """,
            ),
            body["specTable"],
        )
        assertEquals(
            json(
                """
                [{"label":"خانه","url":"/"},{"label":"فروشگاه","url":"/products"},
                 {"label":"کراس‌بادی","url":"/products?category=cross"},{"label":"کراس‌بادی نیلا","url":null}]
                """,
            ),
            body["breadcrumbs"],
        )
        assertEquals(
            json(
                """
                {"id":201,"sku":"KV-1002-BLACK","color":{"key":"black","name":"مشکی","hex":"#2B2930","isLight":false,"sortOrder":1,"productCount":null},
                 "isDefault":true,"price":{"price":1450000,"compareAtPrice":1750000,"discountPercent":17,"discountAmount":300000,"currency":"IRT"},
                 "stock":{"status":"IN_STOCK","availableQuantity":null,"maxOrderQuantity":5,"label":"موجود در انبار"},
                 "media":[{"type":"IMAGE","url":"https://cdn.kiva.ir/p/2/black/front.webp","alt":"نمای جلو","view":"FRONT","viewLabel":"نمای جلو",
                           "sortOrder":0,"isPrimary":true}],
                 "hasVideo":false}
                """,
            ),
            body["variants"]!!.jsonArray[0],
        )
        assertEquals(
            json(
                """
                {"dimensions":{"lengthCm":22.0,"heightCm":16.0,"widthCm":8.5},"weightGrams":420,
                 "closure":{"type":"MAGNETIC_FLAP","label":"درب مگنتی"},"straps":[{"label":"بند بلند قابل تنظیم"}],"pockets":null,
                 "occasions":[{"code":"DAILY","label":"روزمره"},{"code":"TRAVEL","label":"سفر"}]}
                """,
            ),
            body["specs"],
        )
        assertEquals(
            json(
                """{"title":"کراس‌بادی نیلا | کیوا","description":"کراس‌بادی سبک برای هر روز","canonicalUrl":"https://kiva.ir/product/nila-crossbody-bag"}""",
            ),
            body["seo"],
        )
        assertEquals("https://kiva.ir/product/nila-crossbody-bag", body["shareUrl"]!!.jsonPrimitive.content)
        assertEquals(
            json("""{"average":0.0,"count":0,"distribution":[${(5 downTo 1).joinToString(",") { """{"stars":$it,"count":0,"percent":0.0}""" }}]}"""),
            body["ratingSummary"],
        )
        assertEquals(json("""{"id":5,"slug":"faux-leather-soft","name":"چرم مصنوعی سافت","family":"FAUX_LEATHER"}"""), body["material"])
        assertEquals(json("""{"returnable":true,"maxPerOrder":5}"""), body["policies"])
        assertEquals(
            listOf("2026-10-06T08:00:00Z", "2026-10-06T09:30:00Z"),
            listOf("publishedAt", "updatedAt").map { body[it]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `a product not in the shop is a 404 PRODUCT_NOT_FOUND problem in Persian`() = withApp {
        coEvery { getProductUc.execute(any()) } returns Result.failure(GetProductUc.Failure.ProductNotFound)

        val response = client.get("$API_V1/products/a-draft")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(
            json("""{"status":404,"code":"PRODUCT_NOT_FOUND","message":"این محصول پیدا نشد","instance":"/api/v1/products/a-draft","errors":null,"meta":null}"""),
            JsonObject(response.json().jsonObject - "traceId"),
        )
    }

    // ============ the list's headings and chips ============

    @Test
    fun `one category names the page, and every filter in use is a chip, as on the prototype`() = withApp {
        answering(
            applied = ProductListDto.AppliedFilters(
                q = "کیف",
                categories = listOf(ProductCardDto.CategoryRef(1, "shoulder", "کیف دوشی")),
                colors = listOf(ProductListDto.Swatch("lilac", "یاسی", "#C8B6E2")),
                minPrice = 500_000,
                maxPrice = 2_500_000,
                onSale = true,
                inStock = true,
                isNew = true,
                hasVideo = true,
                bagTypes = listOf(BagType.HOBO),
                materials = listOf(ProductListDto.MaterialRef("satin", "ساتن")),
                occasions = listOf(Occasion.PARTY),
            ),
        )

        val body = client.get("$API_V1/products").json().jsonObject

        assertEquals("کیف دوشی", body["title"]!!.jsonPrimitive.content)
        assertEquals(
            json("""[{"label":"خانه","url":"/"},{"label":"فروشگاه","url":"/products"},{"label":"کیف دوشی","url":null}]"""),
            body["breadcrumbs"],
        )
        assertEquals(
            json(
                """
                [{"key":"q","value":"کیف","label":"جستجو: «کیف»","swatchHex":null},
                 {"key":"category","value":"shoulder","label":"کیف دوشی","swatchHex":null},
                 {"key":"color","value":"lilac","label":"یاسی","swatchHex":"#C8B6E2"},
                 {"key":"price","value":null,"label":"۵۰۰٬۰۰۰ تا ۲٬۵۰۰٬۰۰۰ تومان","swatchHex":null},
                 {"key":"onSale","value":null,"label":"تخفیف‌خورده","swatchHex":null},
                 {"key":"inStock","value":null,"label":"موجود","swatchHex":null},
                 {"key":"isNew","value":null,"label":"جدید","swatchHex":null},
                 {"key":"hasVideo","value":null,"label":"ویدیو دار","swatchHex":null},
                 {"key":"bagType","value":"HOBO","label":"دوشی هوبو","swatchHex":null},
                 {"key":"material","value":"satin","label":"ساتن","swatchHex":null},
                 {"key":"occasion","value":"PARTY","label":"مهمانی و مجالس","swatchHex":null}]
                """,
            ),
            body["appliedFilters"],
        )
    }

    @Test
    fun `the sale view without a category is titled so, and one end of the price is a chip of its own`() = withApp {
        answering(applied = ProductListDto.AppliedFilters(onSale = true, maxPrice = 900_000))

        val body = client.get("$API_V1/products").json().jsonObject

        assertEquals("تخفیف‌خورده‌ها", body["title"]!!.jsonPrimitive.content)
        assertEquals(json("""[{"key":"price","value":null,"label":"تا ۹۰۰٬۰۰۰ تومان","swatchHex":null},{"key":"onSale","value":null,"label":"تخفیف‌خورده","swatchHex":null}]"""), body["appliedFilters"])
    }

    // ============ facets ============

    private fun facets() = ProductFacetsDto(
        categories = listOf(ProductFacetsDto.Option("shoulder", "کیف دوشی", 2, selected = true)),
        colors = listOf(ProductFacetsDto.Option("lilac", "یاسی", 1, selected = false, hex = "#C8B6E2")),
        materials = listOf(ProductFacetsDto.Option("satin", "ساتن", 0, selected = false)),
        bagTypes = listOf(ProductFacetsDto.BagTypeOption(BagType.HOBO, 2, selected = false)),
        price = ProductFacetsDto.PriceRange(590_000, 3_160_000, 10_000),
        onSaleCount = 1,
        inStockCount = 2,
        newCount = 0,
        withVideoCount = 1,
    )

    private val facetsJson =
        """
        {"categories":[{"value":"shoulder","label":"کیف دوشی","count":2,"selected":true,"hex":null}],
         "colors":[{"value":"lilac","label":"یاسی","count":1,"selected":false,"hex":"#C8B6E2"}],
         "materials":[{"value":"satin","label":"ساتن","count":0,"selected":false,"hex":null}],
         "bagTypes":[{"value":"HOBO","label":"دوشی هوبو","count":2,"selected":false,"hex":null}],
         "price":{"min":590000,"max":3160000,"step":10000},
         "onSaleCount":1,"inStockCount":2,"newCount":0,"withVideoCount":1}
        """

    @Test
    fun `GET products facets reads the same filters as the list and answers the contract's ProductFacets`() = withApp {
        val query = slot<GetProductFacetsUc.Qry>()
        coEvery { getProductFacetsUc.execute(capture(query)) } returns Result.success(facets())

        val response = client.get("$API_V1/products/facets?category=shoulder&color=lilac,black&onSale=true&minPrice=1000")

        assertEquals(json(facetsJson), response.json())
        assertEquals(
            ProductFilterQuery(categories = listOf("shoulder"), colors = listOf("lilac", "black"), onSale = true, minPrice = "1000"),
            query.captured.filters,
        )
    }

    @Test
    fun `the list carries its facets only when asked for, counted on the same filters`() = withApp {
        answering(card())
        val query = slot<GetProductFacetsUc.Qry>()
        coEvery { getProductFacetsUc.execute(capture(query)) } returns Result.success(facets())

        val without = client.get("$API_V1/products?color=lilac").json().jsonObject
        val with = client.get("$API_V1/products?color=lilac&includeFacets=true").json().jsonObject

        assertEquals(JsonNull, without["facets"])
        assertEquals(json(facetsJson), with["facets"])
        assertEquals(ProductFilterQuery(colors = listOf("lilac")), query.captured.filters)
        coVerify(exactly = 1) { getProductFacetsUc.execute(any()) }
    }

    // ============ related products ============

    @Test
    fun `GET related passes the product number and limit on, and answers a list of cards`() = withApp {
        coEvery { findRelatedProductsUc.execute(FindRelatedProductsUc.Qry("2", "4")) } returns Result.success(listOf(card()))

        val body = client.get("$API_V1/products/2/related?limit=4").json()

        assertEquals(listOf("nila-crossbody-bag"), body.jsonArray.map { it.jsonObject["slug"]!!.jsonPrimitive.content })
        // No filter here: the card shows its default color.
        assertEquals("black", body.jsonArray.single().jsonObject["displayColorKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `related of a product not in the shop is a 404 PRODUCT_NOT_FOUND`() = withApp {
        coEvery { findRelatedProductsUc.execute(any()) } returns Result.failure(FindRelatedProductsUc.Failure.ProductNotFound)

        val response = client.get("$API_V1/products/999/related")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("PRODUCT_NOT_FOUND", response.json().jsonObject["code"]!!.jsonPrimitive.content)
    }
}
