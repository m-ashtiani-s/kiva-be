package com.kiva.infrastructure.httpserver

import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminBlogUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.StockAlertUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminBlog
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1StockAlerts
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminStockAlertUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminStockAlerts
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1ProductInquiry
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminMarketingUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminMarketing
import com.kiva.infrastructure.httpserver.controller.httprest.web.HomeUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Home
import com.kiva.infrastructure.httpserver.controller.httprest.web.SearchUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Search
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1MyDashboard
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1SharedWishlist
import com.kiva.application.usecase.account.usecase.command.ConfirmPhoneChangeUc
import com.kiva.application.usecase.account.usecase.command.RequestPhoneChangeUc
import com.kiva.application.usecase.account.usecase.command.UpdateMyProfileUc
import com.kiva.application.usecase.account.usecase.query.GetMeUc
import com.kiva.application.usecase.address.usecase.command.AddAddressUc
import com.kiva.application.usecase.address.usecase.command.DeleteAddressUc
import com.kiva.application.usecase.address.usecase.command.SetDefaultAddressUc
import com.kiva.application.usecase.address.usecase.command.UpdateAddressUc
import com.kiva.application.usecase.address.usecase.query.GetMyAddressUc
import com.kiva.application.usecase.address.usecase.query.ListMyAddressesUc
import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.admin.usecase.command.SetUserRolesUc
import com.kiva.application.usecase.admin.usecase.query.FindCustomersUc
import com.kiva.application.usecase.admin.usecase.query.GetCustomerUc
import com.kiva.application.usecase.audit.usecase.query.ListAuditLogUc
import com.kiva.application.usecase.auth.usecase.command.LogoutUc
import com.kiva.application.usecase.auth.usecase.command.RefreshSessionUc
import com.kiva.application.usecase.auth.usecase.command.SendLoginCodeUc
import com.kiva.application.usecase.auth.usecase.command.VerifyLoginCodeUc
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.cart.usecase.command.ApplyDiscountCodeUc
import com.kiva.application.usecase.cart.usecase.command.ChangeCartItemUc
import com.kiva.application.usecase.cart.usecase.command.ChooseShippingMethodUc
import com.kiva.application.usecase.cart.usecase.command.ClearCartUc
import com.kiva.application.usecase.cart.usecase.command.MergeGuestCartUc
import com.kiva.application.usecase.cart.usecase.command.OpenCartUc
import com.kiva.application.usecase.cart.usecase.command.RemoveCartItemUc
import com.kiva.application.usecase.cart.usecase.command.RemoveDiscountCodeUc
import com.kiva.application.usecase.cart.usecase.command.SetCartReservationUc
import com.kiva.application.usecase.cart.usecase.query.GetCartUc
import com.kiva.application.usecase.catalog.usecase.query.GetCategoryUc
import com.kiva.application.usecase.catalog.usecase.query.ListCategoriesUc
import com.kiva.application.usecase.catalog.usecase.query.ListColorsUc
import com.kiva.application.usecase.catalog.usecase.query.ListMaterialsUc
import com.kiva.application.usecase.checkout.usecase.command.HandleGatewayCallbackUc
import com.kiva.application.usecase.checkout.usecase.command.PlaceOrderUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentAttemptUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentUc
import com.kiva.application.usecase.checkout.usecase.query.GetCheckoutContextUc
import com.kiva.application.usecase.checkout.usecase.query.GetPaymentInitUc
import com.kiva.application.usecase.checkout.usecase.query.GetPaymentResultUc
import com.kiva.application.usecase.geo.usecase.query.ListCitiesOfProvinceUc
import com.kiva.application.usecase.geo.usecase.query.ListProvincesUc
import com.kiva.application.usecase.media.usecase.command.UploadMediaUc
import com.kiva.application.usecase.order.usecase.command.ReorderUc
import com.kiva.application.usecase.order.usecase.query.GetMyOrderUc
import com.kiva.application.usecase.order.usecase.query.GetOrderSummaryUc
import com.kiva.application.usecase.order.usecase.query.ListMyOrdersUc
import com.kiva.application.usecase.product.usecase.query.FindProductsUc
import com.kiva.application.usecase.product.usecase.query.FindRelatedProductsUc
import com.kiva.application.usecase.product.usecase.query.GetProductFacetsUc
import com.kiva.application.usecase.product.usecase.query.GetProductUc
import com.kiva.application.usecase.reservation.usecase.query.GetMyReservationUc
import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy
import com.kiva.infrastructure.common.http.KivaJson
import com.kiva.infrastructure.common.http.configureStatusPages
import com.kiva.infrastructure.config.AppConfig
import com.kiva.infrastructure.httpserver.controller.httprest.web.AddressUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminAuditUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminCatalogUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminContentUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminCustomerUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminDiscountCodeUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminInventoryUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminMediaUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminOrderUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminProductUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminRefundUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminReturnUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminReviewUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminReportUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminSettingsUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.CartUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.CheckoutUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.ContentUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.MeUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.MyOrderUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.MyReturnUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.MyReviewUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.ReviewUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.OTP_SEND_RATE_LIMIT
import com.kiva.infrastructure.httpserver.controller.httprest.web.PaymentUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.TRACKING_LOOKUP_RATE_LIMIT
import com.kiva.infrastructure.httpserver.controller.httprest.web.CONTACT_RATE_LIMIT
import com.kiva.infrastructure.httpserver.controller.httprest.web.NEWSLETTER_RATE_LIMIT
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminNewsletterUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminNewsletter
import com.kiva.infrastructure.httpserver.controller.httprest.web.ContactUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Contact
import com.kiva.infrastructure.httpserver.controller.httprest.web.AdminSupportUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminSupport
import com.kiva.domain.aggregate.contact.model.policy.ContactPolicy
import com.kiva.infrastructure.httpserver.controller.httprest.web.TrackingUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.devFakeGateway
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Addresses
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminAudit
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminCatalog
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminContent
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminCustomers
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminDiscountCodes
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminInventory
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminMedia
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminOrders
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminProducts
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminRefunds
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminReturns
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminReviews
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminReports
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1AdminSettings
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Auth
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Cart
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Catalog
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Checkout
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Content
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Geo
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Me
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1MyOrders
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1MyReturns
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1MyReviews
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Wishlist
import com.kiva.infrastructure.httpserver.controller.httprest.web.WishlistUseCases
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Reviews
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1MyUploads
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Payments
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Products
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Tracking
import com.kiva.infrastructure.httpserver.route.healthRoutes
import com.kiva.infrastructure.media.DiskMediaStorage
import com.kiva.infrastructure.media.MediaUrls
import com.kiva.infrastructure.payment.FakePaymentGateway
import com.kiva.infrastructure.payment.PaymentProvider
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.koin.ktor.ext.get
import org.koin.ktor.ext.inject
import org.slf4j.event.Level
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toKotlinDuration

/** Every public API route lives under this prefix (from the API contract). `/health` stays outside it. */
const val API_V1 = "/api/v1"

/** Response header carrying the id of the request, the same id as `traceId` in error bodies and in the log. */
const val TRACE_ID_HEADER = "X-Trace-Id"

/** Request header of POST /orders and the payment retries (contract `Idempotency-Key`). */
const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

/** `POST /tracking/lookup`: this many per address in [TRACKING_LOOKUP_WINDOW]. */
const val TRACKING_LOOKUPS_PER_IP = 10
val TRACKING_LOOKUP_WINDOW = 10.minutes

/** A guest's cart, sent by the client and handed out by the server (contract `X-Cart-Token`). */
const val CART_TOKEN_HEADER = "X-Cart-Token"

/**
 * @param allowedOrigins the frontends a browser may call the API from (`kiva.http.allowedOrigins`). Empty in tests that
 *   don't care: then no browser origin is allowed, which is the safe side.
 * @param trustedProxyHops `kiva.http.trustedProxyHops`: 1 behind our nginx, 0 anywhere else (and in tests).
 */
fun Application.configureHttp(allowedOrigins: List<String> = emptyList(), trustedProxyHops: Int = 0) {
    // Behind our nginx only: the client's address is then the last entry of X-Forwarded-For, the one nginx added.
    // Anywhere else the header is ignored, because anyone can send one to get past the limit per address.
    if (trustedProxyHops == 1) {
        install(XForwardedHeaders) { useLastProxy() }
    }
    install(ContentNegotiation) {
        json(KivaJson)
    }
    // In memory, so it starts again from zero on a restart: only a speed bump. The limits per phone are in the database.
    install(RateLimit) {
        register(OTP_SEND_RATE_LIMIT) {
            rateLimiter(limit = OtpPolicy.MAX_SENDS_PER_IP, refillPeriod = OtpPolicy.IP_WINDOW.toKotlinDuration())
            requestKey { call -> call.request.origin.remoteAddress }
        }
        register(TRACKING_LOOKUP_RATE_LIMIT) {
            rateLimiter(limit = TRACKING_LOOKUPS_PER_IP, refillPeriod = TRACKING_LOOKUP_WINDOW)
            requestKey { call -> call.request.origin.remoteAddress }
        }
        register(CONTACT_RATE_LIMIT) {
            rateLimiter(limit = ContactPolicy.MAX_PER_IP, refillPeriod = ContactPolicy.IP_WINDOW.toKotlinDuration())
            requestKey { call -> call.request.origin.remoteAddress }
        }
        register(NEWSLETTER_RATE_LIMIT) {
            rateLimiter(limit = ContactPolicy.MAX_SUBSCRIBES_PER_IP, refillPeriod = ContactPolicy.SUBSCRIBE_WINDOW.toKotlinDuration())
            requestKey { call -> call.request.origin.remoteAddress }
        }
    }
    // The shop and the admin panel live on other origins (localhost:3100 and :3200 in development, kiva.ir and
    // admin.kiva.ir in production), so the browser asks before every call that isn't "simple" (JSON body,
    // Authorization, Idempotency-Key, ...) and blocks the answer unless these headers allow it. Exact origins only, never "*": the API takes logins and payments.
    install(CORS) {
        val origins = allowedOrigins.toSet()
        allowOrigins { it in origins }
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowNonSimpleContentTypes = true // application/json bodies
        // The refresh token's cookie: without this the browser neither keeps the cookie of a login answer nor sends
        // it to /auth/refresh. Safe only because the origins are exact.
        allowCredentials = true
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.IfMatch)
        allowHeader(IDEMPOTENCY_KEY_HEADER)
        allowHeader(CART_TOKEN_HEADER)
        // Response headers the frontend's JavaScript may read; the browser hides all others.
        exposeHeader(TRACE_ID_HEADER)
        exposeHeader(CART_TOKEN_HEADER)
        exposeHeader(HttpHeaders.ETag)
        exposeHeader(HttpHeaders.RetryAfter)
        // The receipt's file name (phase 14): the page names the download after it.
        exposeHeader(HttpHeaders.ContentDisposition)
        // The browser may reuse an allowed preflight for this long instead of asking before every call.
        maxAgeInSeconds = 600
    }
    // A short random id per request: shown to the client on errors and printed on every log line of that
    // request, so "the error with traceId 4bf92f3577b34da6" can be found in the log.
    install(CallId) {
        generate { UUID.randomUUID().toString().replace("-", "").take(16) }
        replyToHeader(TRACE_ID_HEADER)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("traceId")
    }
    configureStatusPages()
}

fun Application.configureRouting() {
    val listColorsUc by inject<ListColorsUc>()
    val listMaterialsUc by inject<ListMaterialsUc>()
    val listCategoriesUc by inject<ListCategoriesUc>()
    val getCategoryUc by inject<GetCategoryUc>()
    val findProductsUc by inject<FindProductsUc>()
    val getProductFacetsUc by inject<GetProductFacetsUc>()
    val getProductUc by inject<GetProductUc>()
    val findRelatedProductsUc by inject<FindRelatedProductsUc>()
    val listProvincesUc by inject<ListProvincesUc>()
    val listCitiesOfProvinceUc by inject<ListCitiesOfProvinceUc>()
    val listMyAddressesUc by inject<ListMyAddressesUc>()
    val getMyAddressUc by inject<GetMyAddressUc>()
    val addAddressUc by inject<AddAddressUc>()
    val updateAddressUc by inject<UpdateAddressUc>()
    val deleteAddressUc by inject<DeleteAddressUc>()
    val setDefaultAddressUc by inject<SetDefaultAddressUc>()
    val openCartUc by inject<OpenCartUc>()
    val getCartUc by inject<GetCartUc>()
    val addCartItemUc by inject<AddCartItemUc>()
    val changeCartItemUc by inject<ChangeCartItemUc>()
    val removeCartItemUc by inject<RemoveCartItemUc>()
    val clearCartUc by inject<ClearCartUc>()
    val chooseShippingMethodUc by inject<ChooseShippingMethodUc>()
    val setCartReservationUc by inject<SetCartReservationUc>()
    val applyDiscountCodeUc by inject<ApplyDiscountCodeUc>()
    val removeDiscountCodeUc by inject<RemoveDiscountCodeUc>()
    val mergeGuestCartUc by inject<MergeGuestCartUc>()
    val getCheckoutContextUc by inject<GetCheckoutContextUc>()
    val placeOrderUc by inject<PlaceOrderUc>()
    val startPaymentUc by inject<StartPaymentUc>()
    val getOrderSummaryUc by inject<GetOrderSummaryUc>()
    val getPaymentInitUc by inject<GetPaymentInitUc>()
    val handleGatewayCallbackUc by inject<HandleGatewayCallbackUc>()
    val getPaymentResultUc by inject<GetPaymentResultUc>()
    val startPaymentAttemptUc by inject<StartPaymentAttemptUc>()
    val listMyOrdersUc by inject<ListMyOrdersUc>()
    val getMyOrderUc by inject<GetMyOrderUc>()
    val reorderUc by inject<ReorderUc>()
    val getMyReservationUc by inject<GetMyReservationUc>()
    val sendLoginCodeUc by inject<SendLoginCodeUc>()
    val verifyLoginCodeUc by inject<VerifyLoginCodeUc>()
    val refreshSessionUc by inject<RefreshSessionUc>()
    val logoutUc by inject<LogoutUc>()
    val getMeUc by inject<GetMeUc>()
    val updateMyProfileUc by inject<UpdateMyProfileUc>()
    val requestPhoneChangeUc by inject<RequestPhoneChangeUc>()
    val confirmPhoneChangeUc by inject<ConfirmPhoneChangeUc>()
    val checkPanelAccessUc by inject<CheckPanelAccessUc>()
    val findCustomersUc by inject<FindCustomersUc>()
    val getCustomerUc by inject<GetCustomerUc>()
    val setUserRolesUc by inject<SetUserRolesUc>()
    val listAuditLogUc by inject<ListAuditLogUc>()
    val uploadMediaUc by inject<UploadMediaUc>()
    val mediaStorage by inject<DiskMediaStorage>()
    val mediaUrls by inject<MediaUrls>()
    val adminCatalogUseCases = AdminCatalogUseCases(
        checkPanelAccess = checkPanelAccessUc,
        listCategories = get(), getCategory = get(), createCategory = get(), updateCategory = get(), deleteCategory = get(),
        getColor = get(), createColor = get(), updateColor = get(), deleteColor = get(),
        listMaterials = get(), getMaterial = get(), createMaterial = get(), updateMaterial = get(), deleteMaterial = get(),
    )
    val adminProductUseCases = AdminProductUseCases(
        checkPanelAccess = checkPanelAccessUc, findProducts = get(), getProduct = get(), createProduct = get(), updateProduct = get(),
        changeStatus = get(), archiveProduct = get(), addVariant = get(), updateVariant = get(), deactivateVariant = get(),
    )
    val adminInventoryUseCases = AdminInventoryUseCases(checkPanelAccess = checkPanelAccessUc, adjustInventory = get(), listAdjustments = get())
    val adminOrderUseCases = AdminOrderUseCases(
        checkPanelAccess = checkPanelAccessUc, findOrders = get(), getOrder = get(), addNote = get(), setPhotos = get(), sendPhotos = get(),
        createRefund = get(), changeStatus = get(), ship = get(), resolveChange = get(),
    )
    val config by inject<AppConfig>()

    routing {
        healthRoutes()
        // Outside /api/v1: the pretend bank's page is not the shop's, and exists only with the fake gateway.
        if (config.payment.provider == PaymentProvider.FAKE) devFakeGateway(get<FakePaymentGateway>())
        // Development only: the app serves the uploads itself. On the server nginx serves {root} as /media.
        if (config.media.serveLocally) staticFiles("/media", config.media.root.toFile())
        route(API_V1) {
            webV1Auth(
                AuthUseCases(sendLoginCodeUc, verifyLoginCodeUc, getMeUc, refreshSessionUc, logoutUc, mergeGuestCartUc, getCartUc),
                config.http.allowedOrigins,
                mediaUrls,
            )
            webV1Me(MeUseCases(getMeUc, updateMyProfileUc, requestPhoneChangeUc, confirmPhoneChangeUc, get(), get()))
            webV1MyDashboard(get())
            webV1StockAlerts(StockAlertUseCases(get(), get(), get()), mediaUrls)
            webV1AdminCustomers(AdminCustomerUseCases(checkPanelAccessUc, findCustomersUc, getCustomerUc, get(), setUserRolesUc))
            webV1AdminAudit(AdminAuditUseCases(checkPanelAccessUc, listAuditLogUc))
            webV1AdminMedia(AdminMediaUseCases(checkPanelAccessUc, uploadMediaUc), mediaStorage::newUploadFile, mediaUrls)
            webV1AdminCatalog(adminCatalogUseCases, mediaUrls, config.shop.siteUrl)
            webV1AdminProducts(adminProductUseCases, config.shop.siteUrl, mediaUrls)
            webV1AdminInventory(adminInventoryUseCases)
            webV1AdminOrders(adminOrderUseCases, mediaUrls)
            webV1AdminRefunds(AdminRefundUseCases(checkPanelAccessUc, get()))
            webV1AdminReturns(AdminReturnUseCases(checkPanelAccessUc, get(), get(), get(), get(), get(), get()), mediaUrls)
            webV1AdminReviews(AdminReviewUseCases(checkPanelAccessUc, get(), get(), get(), get(), get()), mediaUrls)
            webV1AdminStockAlerts(AdminStockAlertUseCases(checkPanelAccessUc, get()), mediaUrls)
            webV1AdminMarketing(AdminMarketingUseCases(checkPanelAccessUc, get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()), mediaUrls)
            webV1AdminSettings(AdminSettingsUseCases(checkPanelAccessUc, get(), get()))
            webV1AdminDiscountCodes(AdminDiscountCodeUseCases(checkPanelAccessUc, get(), get(), get(), get(), get()))
            webV1AdminReports(AdminReportUseCases(checkPanelAccessUc, get()), mediaUrls)
            webV1AdminContent(AdminContentUseCases(checkPanelAccessUc, get(), get(), get(), get(), get(), get(), get()), config.shop.siteUrl)
            webV1Catalog(listColorsUc, listMaterialsUc, listCategoriesUc, getCategoryUc, mediaUrls, config.shop.siteUrl)
            webV1Products(findProductsUc, getProductFacetsUc, getProductUc, findRelatedProductsUc, config.shop.siteUrl, mediaUrls)
            webV1ProductInquiry(get())
            webV1Home(HomeUseCases(get(), get(), get(), get(), get()), mediaUrls, config.shop.siteUrl)
            webV1Search(SearchUseCases(get(), get()), mediaUrls)
            webV1Geo(listProvincesUc, listCitiesOfProvinceUc)
            webV1Content(ContentUseCases(get(), get(), get()), config.shop.siteUrl)
            webV1Addresses(AddressUseCases(listMyAddressesUc, getMyAddressUc, addAddressUc, updateAddressUc, deleteAddressUc, setDefaultAddressUc))
            webV1Cart(
                CartUseCases(
                    openCartUc, getCartUc, addCartItemUc, changeCartItemUc, removeCartItemUc, clearCartUc, chooseShippingMethodUc,
                    setCartReservationUc, mergeGuestCartUc, applyDiscountCodeUc, removeDiscountCodeUc, get(),
                ),
                mediaUrls,
            )
            webV1Checkout(CheckoutUseCases(openCartUc, getCheckoutContextUc, placeOrderUc, startPaymentUc, getOrderSummaryUc, getPaymentInitUc), mediaUrls)
            webV1Payments(
                PaymentUseCases(handleGatewayCallbackUc, getPaymentResultUc, startPaymentAttemptUc, startPaymentUc, getPaymentInitUc),
                config.payment.resultUrl,
            )
            webV1MyOrders(MyOrderUseCases(listMyOrdersUc, getMyOrderUc, reorderUc, getCartUc, getMyReservationUc, get(), get(), get(), get(), get()), mediaUrls)
            webV1MyUploads(uploadMediaUc, mediaStorage::newUploadFile, mediaUrls)
            webV1MyReturns(MyReturnUseCases(get(), get(), get()), mediaUrls)
            webV1Reviews(ReviewUseCases(get(), get(), get(), get(), get()), mediaUrls)
            webV1MyReviews(MyReviewUseCases(get(), get(), get(), get(), get()), mediaUrls)
            webV1Wishlist(WishlistUseCases(get(), get(), get(), get(), get(), get(), getCartUc, get(), get(), get()), mediaUrls, config.shop.siteUrl)
            webV1SharedWishlist(get(), mediaUrls)
            webV1Tracking(TrackingUseCases(get(), get(), get()), mediaUrls)
            webV1Contact(ContactUseCases(get(), get(), get(), get()))
            webV1AdminNewsletter(AdminNewsletterUseCases(checkPanelAccessUc, get()))
            webV1AdminBlog(AdminBlogUseCases(checkPanelAccessUc, get(), get(), get(), get(), get()), config.shop.siteUrl, mediaUrls)
            webV1AdminSupport(AdminSupportUseCases(checkPanelAccessUc, get(), get()))
        }
    }
}
