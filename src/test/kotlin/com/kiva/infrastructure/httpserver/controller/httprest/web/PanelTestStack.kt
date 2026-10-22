package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.stockalert.StockAlertCounts
import com.kiva.infrastructure.persistence.exposed.tbl.StockAlertTbl
import com.kiva.infrastructure.persistence.exposed.StockAlertIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.StockAlertRepositoryImpl
import com.kiva.domain.aggregate.stockalert.StockAlertDomainService
import com.kiva.application.usecase.stockalert.usecase.query.ListMyStockAlertsUc
import com.kiva.application.usecase.stockalert.usecase.command.CancelMyStockAlertUc
import com.kiva.application.usecase.stockalert.usecase.command.CreateStockAlertUc
import com.kiva.application.usecase.stockalert.StockAlerts
import com.kiva.application.usecase.stockalert.StockAlertFlags
import com.kiva.application.usecase.account.usecase.query.GetMyDashboardUc
import com.kiva.application.usecase.wishlist.WishlistHearts
import com.kiva.application.usecase.review.ReviewsAwaiting
import com.kiva.infrastructure.persistence.exposed.RandomWishlistShareTokens
import com.kiva.application.usecase.wishlist.usecase.query.GetSharedWishlistUc
import com.kiva.application.usecase.wishlist.usecase.command.RevokeWishlistShareUc
import com.kiva.application.usecase.wishlist.usecase.command.ShareMyWishlistUc
import com.kiva.application.usecase.wishlist.usecase.command.AddWishlistToCartUc
import com.kiva.application.usecase.wishlist.WishlistCards
import com.kiva.application.usecase.wishlist.usecase.command.AddToWishlistUc
import com.kiva.application.usecase.wishlist.usecase.command.ClearMyWishlistUc
import com.kiva.application.usecase.wishlist.usecase.command.RemoveFromWishlistUc
import com.kiva.application.usecase.wishlist.usecase.query.GetMyWishlistIdsUc
import com.kiva.application.usecase.wishlist.usecase.query.GetMyWishlistUc
import com.kiva.infrastructure.persistence.exposed.WishlistShareRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.WishlistItemTbl
import com.kiva.infrastructure.persistence.exposed.tbl.WishlistShareTbl
import com.kiva.application.usecase.product.usecase.query.FindProductsUc
import com.kiva.application.usecase.product.usecase.query.FindRelatedProductsUc
import com.kiva.application.usecase.product.usecase.query.GetProductFacetsUc
import com.kiva.application.usecase.product.usecase.query.GetProductUc
import com.kiva.application.port.MediaProcessor
import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.admin.usecase.command.SetUserRolesUc
import com.kiva.application.usecase.admin.usecase.query.FindCustomersUc
import com.kiva.application.usecase.admin.usecase.query.GetCustomerDetailUc
import com.kiva.application.usecase.admin.usecase.query.GetCustomerUc
import com.kiva.application.usecase.audit.usecase.query.ListAuditLogUc
import com.kiva.application.usecase.catalog.usecase.command.CreateCategoryUc
import com.kiva.application.usecase.catalog.usecase.command.CreateColorUc
import com.kiva.application.usecase.catalog.usecase.command.CreateMaterialUc
import com.kiva.application.usecase.catalog.usecase.command.DeleteCategoryUc
import com.kiva.application.usecase.catalog.usecase.command.DeleteColorUc
import com.kiva.application.usecase.catalog.usecase.command.DeleteMaterialUc
import com.kiva.application.usecase.catalog.usecase.command.UpdateCategoryUc
import com.kiva.application.usecase.catalog.usecase.command.UpdateColorUc
import com.kiva.application.usecase.catalog.usecase.command.UpdateMaterialUc
import com.kiva.application.usecase.catalog.usecase.query.GetAdminCategoryUc
import com.kiva.application.usecase.catalog.usecase.query.GetAdminColorUc
import com.kiva.application.usecase.catalog.usecase.query.GetAdminMaterialUc
import com.kiva.application.usecase.catalog.usecase.query.ListAdminCategoriesUc
import com.kiva.application.usecase.catalog.usecase.query.ListAdminMaterialsUc
import com.kiva.application.usecase.content.usecase.command.SaveAnnouncementsUc
import com.kiva.application.usecase.content.usecase.command.SaveFaqUc
import com.kiva.application.usecase.content.usecase.command.SavePageUc
import com.kiva.application.usecase.content.usecase.query.GetFaqUc
import com.kiva.application.usecase.content.usecase.query.GetPageUc
import com.kiva.application.usecase.content.usecase.query.GetSiteConfigUc
import com.kiva.application.usecase.content.usecase.query.ListAnnouncementsUc
import com.kiva.application.usecase.content.usecase.query.ListFaqForEditUc
import com.kiva.application.usecase.content.usecase.query.ListPagesUc
import com.kiva.application.usecase.discount.DiscountCodeReferences
import com.kiva.application.usecase.discount.usecase.command.CreateDiscountCodeUc
import com.kiva.application.usecase.discount.usecase.command.DeactivateDiscountCodeUc
import com.kiva.application.usecase.discount.usecase.command.UpdateDiscountCodeUc
import com.kiva.application.usecase.discount.usecase.query.GetDiscountCodeUc
import com.kiva.application.usecase.discount.usecase.query.ListDiscountCodesUc
import com.kiva.application.usecase.inventory.usecase.command.AdjustInventoryUc
import com.kiva.application.usecase.inventory.usecase.query.ListInventoryAdjustmentsUc
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.application.usecase.media.usecase.command.UploadMediaUc
import com.kiva.application.usecase.order.usecase.command.AddOrderNoteUc
import com.kiva.application.usecase.order.usecase.command.ApproveSilentPhotosUc
import com.kiva.application.usecase.order.usecase.command.ChangeOrderStatusUc
import com.kiva.application.usecase.order.usecase.command.ResolveChangeRequestUc
import com.kiva.application.usecase.order.usecase.command.SendPreShipmentMediaUc
import com.kiva.application.usecase.order.usecase.command.SetPreShipmentMediaUc
import com.kiva.application.usecase.order.usecase.command.ShipOrderUc
import com.kiva.application.usecase.order.usecase.query.FindAdminOrdersUc
import com.kiva.application.usecase.order.usecase.query.FindSilentPhotoOrdersUc
import com.kiva.application.usecase.order.usecase.query.GetAdminOrderUc
import com.kiva.application.usecase.product.ProductInput
import com.kiva.application.usecase.product.usecase.command.AddVariantUc
import com.kiva.application.usecase.product.usecase.command.ArchiveProductUc
import com.kiva.application.usecase.product.usecase.command.ChangeProductStatusUc
import com.kiva.application.usecase.product.usecase.command.CreateProductUc
import com.kiva.application.usecase.product.usecase.command.DeactivateVariantUc
import com.kiva.application.usecase.product.usecase.command.PublishScheduledProductUc
import com.kiva.application.usecase.product.usecase.command.UpdateProductUc
import com.kiva.application.usecase.product.usecase.command.UpdateVariantUc
import com.kiva.application.usecase.product.usecase.query.FindAdminProductsUc
import com.kiva.application.usecase.product.usecase.query.FindDueScheduledProductsUc
import com.kiva.application.usecase.product.usecase.query.GetAdminProductUc
import com.kiva.application.usecase.refund.usecase.command.CreateRefundUc
import com.kiva.application.usecase.refund.usecase.command.UpdateRefundUc
import com.kiva.application.usecase.report.usecase.query.GetReportOverviewUc
import com.kiva.application.usecase.returnrequest.AdminReturnRequests
import com.kiva.application.usecase.returnrequest.ReturnByIdForPanel
import com.kiva.application.usecase.returnrequest.ReturnNotices
import com.kiva.application.usecase.returnrequest.usecase.command.CloseReturnUc
import com.kiva.application.usecase.returnrequest.usecase.command.CreateReturnUc
import com.kiva.application.usecase.returnrequest.usecase.command.DecideReturnUc
import com.kiva.application.usecase.returnrequest.usecase.command.ReceiveReturnUc
import com.kiva.application.usecase.returnrequest.usecase.command.ScheduleReturnPickupUc
import com.kiva.application.usecase.returnrequest.usecase.query.FindAdminReturnsUc
import com.kiva.application.usecase.returnrequest.usecase.query.GetAdminReturnUc
import com.kiva.application.usecase.returnrequest.usecase.query.GetMyReturnUc
import com.kiva.application.usecase.returnrequest.usecase.query.ListMyReturnsUc
import com.kiva.application.usecase.review.RatingBook
import com.kiva.application.usecase.review.ReviewPurchases
import com.kiva.application.usecase.review.Reviews
import com.kiva.application.usecase.review.usecase.command.CreateReviewUc
import com.kiva.application.usecase.review.usecase.command.DeleteMyReviewUc
import com.kiva.application.usecase.review.usecase.command.DeleteReviewReplyUc
import com.kiva.application.usecase.review.usecase.command.ModerateReviewUc
import com.kiva.application.usecase.review.usecase.command.ReplyToReviewUc
import com.kiva.application.usecase.review.usecase.command.UpdateMyReviewUc
import com.kiva.application.usecase.review.usecase.command.VoteReviewHelpfulUc
import com.kiva.application.usecase.review.usecase.query.FindAdminReviewsUc
import com.kiva.application.usecase.review.usecase.query.GetAdminReviewUc
import com.kiva.application.usecase.review.usecase.query.GetReviewSummaryUc
import com.kiva.application.usecase.review.usecase.query.ListProductReviewsUc
import com.kiva.application.usecase.review.usecase.query.GetMyReviewUc
import com.kiva.application.usecase.review.usecase.query.ListMyReviewsUc
import com.kiva.application.usecase.review.usecase.query.ListProductsAwaitingMyReviewUc
import com.kiva.application.usecase.settings.usecase.command.UpdateSettingsUc
import com.kiva.application.usecase.settings.usecase.query.GetAdminSettingsUc
import com.kiva.common.patch.Patch
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.product.ProductDomainService
import com.kiva.domain.aggregate.refund.RefundDomainService
import com.kiva.domain.aggregate.returnrequest.ReturnDomainService
import com.kiva.domain.aggregate.review.ReviewDomainService
import com.kiva.domain.aggregate.session.entity.AuthSession
import com.kiva.domain.aggregate.session.model.valueobject.SessionDevice
import com.kiva.domain.aggregate.user.entity.User
import com.kiva.domain.aggregate.user.model.ProfilePatch
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.domain.aggregate.user.model.valueobject.Roles
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.auth.JwtAccessTokens
import com.kiva.infrastructure.auth.RandomSessionTokens
import com.kiva.infrastructure.common.html.JsoupHtmlSanitizer
import com.kiva.infrastructure.common.html.JsoupHtmlText
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.media.DiskMediaStorage
import com.kiva.infrastructure.media.FakeMediaProcessor
import com.kiva.infrastructure.media.MediaUrls
import com.kiva.infrastructure.media.RandomMediaIds
import com.kiva.infrastructure.payment.PaymentConfig
import com.kiva.infrastructure.persistence.exposed.AddressBookRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.AdminCustomerRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.AnnouncementBarRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.AuditLogQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.AuditTrailImpl
import com.kiva.infrastructure.persistence.exposed.AuthSessionRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.CategoryIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.CategoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ColorRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.DiscountCodeRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.FaqRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.GeoRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.InventoryAdjustmentQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.InventoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.MaterialIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.MaterialRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.MediaRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OfferQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OrderNoteRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OrderQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.ProductQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.RefundIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.ReportQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ReturnIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.ProductRatingRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ReviewIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.ReviewRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ReviewVoteRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.WishlistRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.SecurityEventLogImpl
import com.kiva.infrastructure.persistence.exposed.ShopSettingsRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.SiteContentRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StaticPageRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.AuthSessionTbl
import com.kiva.infrastructure.persistence.exposed.tbl.MediaTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductRatingTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ReviewTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ReviewVoteTbl
import com.kiva.infrastructure.persistence.exposed.tbl.OtpChallengeTbl
import com.kiva.infrastructure.persistence.exposed.tbl.RefreshTokenTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * The panel's routes over kiva_test, wired by hand as the app wires them (with a test clock and fixed secrets), and a
 * staff member for every role whose session is ready, as a login would leave it: [callerWith] gives the token.
 *
 * Every test user's phone starts with 09900; [deleteWhatWasStored] removes them with their sessions and uploads, and
 * removes the media root ([mediaRoot], a temporary folder of each stack).
 * A route of a later step is added to [app] (and its use cases built here) the day it exists.
 */
class PanelTestStack(val clock: MutableClock, val mediaProcessor: MediaProcessor = FakeMediaProcessor()) {

    val db = TestDatabase.database
    val sms = RecordingSmsSender()
    val accessTokens = JwtAccessTokens(AuthTestStack.JWT_SECRET, clock)

    val transactions = ExposedTransactionManager(db)
    val users = UserRepositoryImpl()
    val sessions = AuthSessionRepositoryImpl()
    private val events = SecurityEventLogImpl()
    val auditTrail = AuditTrailImpl()

    val checkPanelAccess = CheckPanelAccessUc(transactions, sessions, users, events, clock)

    // --- the customers page (login phase), the first panel routes ---
    val findCustomers = FindCustomersUc(transactions, AdminCustomerRepositoryImpl(), OrderQmRepositoryImpl())
    val getCustomer = GetCustomerUc(transactions, AdminCustomerRepositoryImpl(), OrderQmRepositoryImpl())
    val getCustomerDetail = GetCustomerDetailUc(transactions, AdminCustomerRepositoryImpl(), OrderQmRepositoryImpl(), AddressBookRepositoryImpl(), GeoRepositoryImpl())
    val setUserRoles = SetUserRolesUc(transactions, users, sessions, events, auditTrail, clock)

    // --- the change log (step 1.1) ---
    val listAuditLog = ListAuditLogUc(transactions, AuditLogQmRepositoryImpl())

    // --- uploads (step 1.2): a media root of its own, removed by deleteWhatWasStored ---
    val mediaRoot: Path = Files.createTempDirectory("kiva-media-")
    val mediaStorage = DiskMediaStorage(mediaRoot)
    val mediaRepository = MediaRepositoryImpl()
    val mediaUrls = MediaUrls(MEDIA_BASE_URL)
    val uploadMedia = UploadMediaUc(transactions, mediaProcessor, mediaStorage, mediaRepository, RandomMediaIds(), clock)

    // --- categories, colors and materials (step 1.3) ---
    val categories = CategoryRepositoryImpl()
    val colors = ColorRepositoryImpl()
    val materials = MaterialRepositoryImpl()
    val productQm = ProductQmRepositoryImpl()
    val mediaAttachments = MediaAttachments(mediaRepository)
    val adminCatalog = AdminCatalogUseCases(
        checkPanelAccess = checkPanelAccess,
        listCategories = ListAdminCategoriesUc(transactions, categories, productQm, mediaRepository),
        getCategory = GetAdminCategoryUc(transactions, categories, productQm, mediaRepository),
        createCategory = CreateCategoryUc(transactions, categories, CategoryIdGeneratorImpl(), colors, mediaAttachments, auditTrail, clock),
        updateCategory = UpdateCategoryUc(transactions, categories, colors, productQm, mediaAttachments, auditTrail, clock),
        deleteCategory = DeleteCategoryUc(transactions, categories, productQm, auditTrail, clock),
        getColor = GetAdminColorUc(transactions, colors),
        createColor = CreateColorUc(transactions, colors, auditTrail, clock),
        updateColor = UpdateColorUc(transactions, colors, productQm, auditTrail, clock),
        deleteColor = DeleteColorUc(transactions, colors, categories, productQm, auditTrail, clock),
        listMaterials = ListAdminMaterialsUc(transactions, materials, productQm),
        getMaterial = GetAdminMaterialUc(transactions, materials, productQm),
        createMaterial = CreateMaterialUc(transactions, materials, MaterialIdGeneratorImpl(), auditTrail, clock),
        updateMaterial = UpdateMaterialUc(transactions, materials, productQm, auditTrail, clock),
        deleteMaterial = DeleteMaterialUc(transactions, materials, productQm, auditTrail, clock),
    )

    // --- products (step 1.4 on) ---
    val products = ProductRepositoryImpl()
    val inventories = InventoryRepositoryImpl()
    val productDomainService = ProductDomainService(products, ProductIdGeneratorImpl(), categories, materials, colors)
    val productInput = ProductInput(JsoupHtmlSanitizer)
    val adminProducts = AdminProductUseCases(
        checkPanelAccess = checkPanelAccess,
        findProducts = FindAdminProductsUc(transactions, productQm, categories, ShopSettingsRepositoryImpl()),
        getProduct = GetAdminProductUc(transactions, productQm, categories, materials, colors, users, ShopSettingsRepositoryImpl(), clock, StockAlertCounts.of(StockAlertRepositoryImpl())),
        createProduct = CreateProductUc(
            transactions, productDomainService, products, productQm, productInput, mediaAttachments, InventoryDomainService(inventories), inventories,
            auditTrail, clock,
        ),
        updateProduct = UpdateProductUc(transactions, productDomainService, products, productInput, auditTrail, clock),
        changeStatus = ChangeProductStatusUc(transactions, products, auditTrail, clock),
        archiveProduct = ArchiveProductUc(transactions, products, WishlistRepositoryImpl(), auditTrail, clock),
        addVariant = AddVariantUc(
            transactions, productDomainService, products, productQm, colors, productInput, mediaAttachments, InventoryDomainService(inventories), inventories,
            auditTrail, clock,
        ),
        updateVariant = UpdateVariantUc(transactions, products, productInput, mediaAttachments, auditTrail, clock),
        deactivateVariant = DeactivateVariantUc(transactions, products, auditTrail, clock),
    )

    // --- stock (step 1.8 on) ---
    val adminInventory = AdminInventoryUseCases(
        checkPanelAccess = checkPanelAccess,
        adjustInventory = AdjustInventoryUc(
            transactions, InventoryDomainService(inventories), inventories, productQm, InventoryAdjustmentQmRepositoryImpl(), users, auditTrail, clock,
        ),
        listAdjustments = ListInventoryAdjustmentsUc(transactions, InventoryAdjustmentQmRepositoryImpl(), users),
    )

    // The publish job's two use cases, run by the tests the way PanelJobs runs them.
    val findDueScheduled = FindDueScheduledProductsUc(transactions, productQm, clock)
    val publishScheduled = PublishScheduledProductUc(transactions, products, clock)

    // --- orders (phase 2): paid the way the shop pays them, through the purchase's own stack ---
    val purchase = PurchaseTestStack(clock)
    val paidOrders = PaidOrders(purchase, clock)
    val adminOrders = AdminOrderUseCases(
        checkPanelAccess = checkPanelAccess,
        findOrders = FindAdminOrdersUc(transactions, purchase.orderQm, AdminCustomerRepositoryImpl()),
        getOrder = GetAdminOrderUc(
            transactions, purchase.orderByCode, purchase.orderDetails, purchase.orderQm, purchase.payments, purchase.groups, AdminCustomerRepositoryImpl(),
            OrderNoteRepositoryImpl(), users, purchase.settings, clock,
        ),
        addNote = AddOrderNoteUc(transactions, purchase.orderByCode, OrderNoteRepositoryImpl(), users, auditTrail, clock),
        setPhotos = SetPreShipmentMediaUc(transactions, purchase.orderByCode, purchase.orders, mediaAttachments, auditTrail, clock),
        sendPhotos = SendPreShipmentMediaUc(transactions, purchase.orderByCode, purchase.orders, users, purchase.settings, auditTrail, purchase.orderNotices, clock),
        createRefund = CreateRefundUc(
            transactions, purchase.orderByCode, purchase.orderQm, purchase.orders, purchase.payments, purchase.refunds, purchase.returns,
            RefundDomainService(RefundIdGeneratorImpl()), auditTrail, clock,
        ),
        changeStatus = ChangeOrderStatusUc(
            transactions, purchase.orderByCode, purchase.orderCancellation, purchase.orderQm, purchase.orders, purchase.settings, users, auditTrail,
            purchase.orderNotices, clock,
        ),
        ship = ShipOrderUc(
            transactions, purchase.orderByCode, purchase.orderQm, purchase.orders, purchase.groups, purchase.stock, purchase.inventories, purchase.settings,
            users, auditTrail, purchase.orderNotices, clock,
        ),
        resolveChange = ResolveChangeRequestUc(
            transactions, purchase.orderByCode, purchase.orderCancellation, purchase.orderQm, purchase.orders, purchase.payments, OfferQmRepositoryImpl(),
            purchase.stock, purchase.inventories, purchase.refunds, RefundIdGeneratorImpl(), RefundDomainService(RefundIdGeneratorImpl()), users, auditTrail,
            purchase.orderNotices, clock,
        ),
    )
    // --- returns (phase 7) ---
    val returnDomain = ReturnDomainService(ReturnIdGeneratorImpl())
    val myReturns = MyReturnUseCases(
        CreateReturnUc(transactions, purchase.orderByCode, purchase.orderQm, purchase.orders, mediaRepository, returnDomain, purchase.returns, clock),
        ListMyReturnsUc(transactions, purchase.returns, purchase.returnRequests, purchase.settings),
        GetMyReturnUc(transactions, purchase.returns, purchase.returnRequests, purchase.settings),
    )
    val returnNotices = ReturnNotices(purchase.auth.sms)
    val returnByIdForPanel = ReturnByIdForPanel(purchase.returns, purchase.orderQm, purchase.orders)
    val adminReturnRequests = AdminReturnRequests(
        purchase.returnRequests, AdminCustomerRepositoryImpl(), purchase.refunds, purchase.payments, RefundDomainService(RefundIdGeneratorImpl()),
    )
    val adminReturns = AdminReturnUseCases(
        checkPanelAccess,
        FindAdminReturnsUc(transactions, purchase.returns, adminReturnRequests, purchase.settings),
        GetAdminReturnUc(transactions, purchase.returns, adminReturnRequests, purchase.settings),
        DecideReturnUc(transactions, returnByIdForPanel, purchase.returns, purchase.orders, users, auditTrail, returnNotices, clock),
        ScheduleReturnPickupUc(transactions, returnByIdForPanel, purchase.returns, purchase.settings, users, auditTrail, returnNotices, clock),
        ReceiveReturnUc(transactions, returnByIdForPanel, purchase.returns, purchase.orders, purchase.stock, purchase.inventories, auditTrail, clock),
        CloseReturnUc(transactions, returnByIdForPanel, purchase.returns, purchase.refunds, purchase.orders, users, auditTrail, returnNotices, clock),
    )

    // --- reviews (phase 8) ---
    val reviewRepository = ReviewRepositoryImpl()
    val productRatings = ProductRatingRepositoryImpl()
    val ratingBook = RatingBook(productRatings)
    val reviewDomain = ReviewDomainService(ReviewIdGeneratorImpl())
    val reviews = Reviews(mediaRepository, ReviewVoteRepositoryImpl(), productQm, colors)
    val reviewPurchases = ReviewPurchases(purchase.orderQm)
    val getMyReview = GetMyReviewUc(transactions, reviewRepository, reviews)
    val reviewsAwaiting = ReviewsAwaiting(reviewPurchases, reviewRepository, productQm)
    val reviewUseCases = ReviewUseCases(
        CreateReviewUc(transactions, productQm, reviewRepository, reviewDomain, reviewPurchases, purchase.orderByCode, mediaRepository, purchase.settings, clock),
        getMyReview,
        ListProductReviewsUc(transactions, productQm, reviewRepository, productRatings, reviews),
        GetReviewSummaryUc(transactions, productQm, productRatings),
        VoteReviewHelpfulUc(transactions, reviewRepository, ReviewVoteRepositoryImpl(), clock),
    )
    val myReviews = MyReviewUseCases(
        ListMyReviewsUc(transactions, reviewRepository, reviews),
        getMyReview,
        UpdateMyReviewUc(transactions, reviewRepository, clock),
        DeleteMyReviewUc(transactions, reviewRepository, ratingBook, clock),
        ListProductsAwaitingMyReviewUc(transactions, reviewsAwaiting, colors),
    )
    // --- stock alerts (phase 10) ---
    val stockAlertRepository = StockAlertRepositoryImpl()
    val stockAlertsView = StockAlerts(productQm, colors, users)
    val stockAlertUseCases = StockAlertUseCases(
        CreateStockAlertUc(transactions, productQm, stockAlertRepository, StockAlertDomainService(StockAlertIdGeneratorImpl()), stockAlertsView, clock),
        ListMyStockAlertsUc(transactions, stockAlertRepository, stockAlertsView, clock),
        CancelMyStockAlertUc(transactions, stockAlertRepository, clock),
    )

    // --- wishlist (phase 9) ---
    val wishlists = WishlistRepositoryImpl()
    val wishlistShares = WishlistShareRepositoryImpl()
    val wishlistCards = WishlistCards(productQm, categories, colors, purchase.settings)
    val wishlist = WishlistUseCases(
        GetMyWishlistUc(transactions, wishlists, wishlistShares, wishlistCards, purchase.settings, clock),
        GetMyWishlistIdsUc(transactions, wishlists),
        ClearMyWishlistUc(transactions, wishlists),
        AddToWishlistUc(transactions, wishlists, wishlistCards, clock),
        RemoveFromWishlistUc(transactions, wishlists, productQm),
        AddWishlistToCartUc(transactions, wishlists, wishlistCards, purchase.cartBulkAdd, clock),
        purchase.getCart,
        ShareMyWishlistUc(transactions, wishlists, wishlistShares, RandomWishlistShareTokens(), purchase.settings, clock),
        RevokeWishlistShareUc(transactions, wishlists, wishlistShares),
        GetSharedWishlistUc(transactions, wishlistShares, wishlists, users, wishlistCards, purchase.settings, clock),
    )

    val adminReviews = AdminReviewUseCases(
        checkPanelAccess,
        FindAdminReviewsUc(transactions, reviewRepository, reviews, AdminCustomerRepositoryImpl()),
        GetAdminReviewUc(transactions, reviewRepository, reviews),
        ModerateReviewUc(transactions, reviewRepository, ratingBook, productQm, auditTrail, clock),
        ReplyToReviewUc(transactions, reviewRepository, productQm, auditTrail, clock),
        DeleteReviewReplyUc(transactions, reviewRepository, productQm, auditTrail, clock),
    )

    val dashboard = GetMyDashboardUc(
        transactions, users, purchase.orderQm, wishlists, AddressBookRepositoryImpl(), reviewRepository, reviewsAwaiting, purchase.reservations, clock,
    )

    /** The shop's own product use cases, as the app wires them. */
    inner class ShopProducts {
        val find = FindProductsUc(transactions, productQm, categories, colors, materials, clock, purchase.settings, WishlistHearts.of(wishlists))
        val facets = GetProductFacetsUc(transactions, productQm, categories, colors, materials, clock)
        val related = FindRelatedProductsUc(transactions, productQm, categories, colors, clock, purchase.settings, WishlistHearts.of(wishlists))
        val get = GetProductUc(transactions, productQm, categories, materials, colors, clock, purchase.settings, WishlistHearts.of(wishlists), StockAlertFlags.of(stockAlertRepository))
    }
    val shopProducts = ShopProducts()

    val adminRefunds = AdminRefundUseCases(
        checkPanelAccess,
        UpdateRefundUc(transactions, purchase.refunds, purchase.orderQm, purchase.orders, purchase.returns, users, auditTrail, purchase.orderNotices, clock),
    )

    // --- the shop's settings (phase 4) ---
    val adminSettings = AdminSettingsUseCases(
        checkPanelAccess,
        GetAdminSettingsUc(transactions, purchase.settings, purchase.gateways),
        UpdateSettingsUc(transactions, purchase.settings, auditTrail, clock),
    )

    // --- discount codes (phase 4) ---
    val discountCodes = DiscountCodeRepositoryImpl()
    val discountReferences = DiscountCodeReferences(categories, productQm)
    val adminDiscountCodes = AdminDiscountCodeUseCases(
        checkPanelAccess,
        ListDiscountCodesUc(transactions, discountCodes),
        GetDiscountCodeUc(transactions, discountCodes),
        CreateDiscountCodeUc(transactions, discountCodes, discountReferences, auditTrail, clock),
        UpdateDiscountCodeUc(transactions, discountCodes, discountReferences, auditTrail, clock),
        DeactivateDiscountCodeUc(transactions, discountCodes, auditTrail, clock),
    )

    // --- the dashboard (phase 4) ---
    val adminReports = AdminReportUseCases(checkPanelAccess, GetReportOverviewUc(transactions, ReportQmRepositoryImpl()))

    // --- content (phase 5) ---
    val pages = StaticPageRepositoryImpl()
    val getPage = GetPageUc(transactions, pages)
    val faq = FaqRepositoryImpl()
    val announcements = AnnouncementBarRepositoryImpl()
    val adminContent = AdminContentUseCases(
        checkPanelAccess, ListPagesUc(transactions, pages), getPage, SavePageUc(transactions, pages, JsoupHtmlSanitizer, auditTrail, clock),
        ListFaqForEditUc(transactions, faq), SaveFaqUc(transactions, faq, JsoupHtmlSanitizer, auditTrail, clock),
        ListAnnouncementsUc(transactions, announcements), SaveAnnouncementsUc(transactions, announcements, auditTrail, clock),
    )
    val content = ContentUseCases(
        getPage, GetFaqUc(transactions, faq, JsoupHtmlText),
        GetSiteConfigUc(transactions, SiteContentRepositoryImpl(), purchase.settings, purchase.gateways, announcements, clock),
    )

    // The approve-silent-photos job's two use cases, run by the tests the way PanelJobs runs them.
    val findSilentPhotos = FindSilentPhotoOrdersUc(transactions, purchase.orderQm, clock)
    val approveSilentPhotos = ApproveSilentPhotosUc(transactions, purchase.orders, clock)

    /** A logged-in user of the panel: who they are and the access token of their session. */
    class Caller(val user: User, val token: String) {
        val userId: Long get() = user.id.value
        val bearer: String get() = "Bearer $token"
    }

    private val callers = ConcurrentHashMap<Role, Caller>()

    /**
     * The staff member with exactly [role] (and CUSTOMER, which everyone has), made on first use. [Role.CUSTOMER] gives
     * a plain customer, whose session isn't a panel session.
     */
    fun callerWith(role: Role): Caller = callers.computeIfAbsent(role) {
        val n = Role.entries.indexOf(role) + 1
        if (role == Role.CUSTOMER) caller(phone(n)) else caller(phone(n), role)
    }

    /** A user with [roles] and a session started now; [name] as "first last" if given. */
    fun caller(phone: String, vararg roles: Role, name: String? = null): Caller = transaction(db) {
        var user = users.findOrRegister(IranianMobileNumber.constructFromPersisted(phone), clock.now).user
        if (roles.isNotEmpty()) user = user.setPanelRoles(roles.toList(), by = UserId.SYSTEM).getOrNull() ?: error("refused")
        if (name != null) {
            val (first, last) = name.split(' ')
            val patch = ProfilePatch(firstName = Patch.Set(first), lastName = Patch.Set(last))
            user = user.updateProfile(patch, clock.now.atZone(ZoneOffset.UTC).toLocalDate()).getOrNull() ?: error("refused")
        }
        if (roles.isNotEmpty() || name != null) users.save(user)
        val stored = users.findById(user.id) ?: error("not stored")
        val session = AuthSession.start(sessions.nextId(), stored.id, Roles.of(roles.toList()), clock.now, SessionDevice.UNKNOWN)
        sessions.add(session, RandomSessionTokens().newRefreshToken().hash)
        Caller(stored, accessTokens.issue(stored, session, clock.now).value)
    }

    /** @param trustedProxyHops 1 to read the caller's address from X-Forwarded-For, as behind our nginx. */
    fun app(trustedProxyHops: Int = 0, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp(trustedProxyHops = trustedProxyHops)
            configureAuthentication(accessTokens)
            routing {
                route(API_V1) {
                    webV1AdminCustomers(AdminCustomerUseCases(checkPanelAccess, findCustomers, getCustomer, getCustomerDetail, setUserRoles))
                    webV1AdminAudit(AdminAuditUseCases(checkPanelAccess, listAuditLog))
                    webV1AdminMedia(AdminMediaUseCases(checkPanelAccess, uploadMedia), mediaStorage::newUploadFile, mediaUrls)
                    webV1AdminCatalog(adminCatalog, mediaUrls, SITE_URL)
                    webV1AdminProducts(adminProducts, SITE_URL, mediaUrls)
                    webV1AdminInventory(adminInventory)
                    webV1AdminOrders(adminOrders, mediaUrls)
                    webV1AdminRefunds(adminRefunds)
                    webV1AdminSettings(adminSettings)
                    webV1AdminDiscountCodes(adminDiscountCodes)
                    webV1AdminReports(adminReports, mediaUrls)
                    webV1AdminContent(adminContent, SITE_URL)
                    // The site's own side of the content, as everyone reads it.
                    webV1Content(content, SITE_URL)
                    // The customer's side of an order, for the tests that follow it from the panel to «سفارش‌های من».
                    webV1MyOrders(purchase.myOrderUseCases, mediaUrls)
                    // A customer's uploads (phase 7): the same use case and media root as the panel's.
                    webV1MyUploads(uploadMedia, mediaStorage::newUploadFile, mediaUrls)
                    webV1MyReturns(myReturns, mediaUrls)
                    webV1AdminReturns(adminReturns, mediaUrls)
                    webV1AdminReviews(adminReviews, mediaUrls)
                    webV1Reviews(reviewUseCases, mediaUrls)
                    // The shop's product pages, for the ratings on cards and pages (phase 8).
                    webV1Products(shopProducts.find, shopProducts.facets, shopProducts.get, shopProducts.related, SITE_URL, mediaUrls)
                    webV1MyReviews(myReviews, mediaUrls)
                    webV1Wishlist(wishlist, mediaUrls, SITE_URL)
                    webV1MyDashboard(dashboard)
                    webV1StockAlerts(stockAlertUseCases, mediaUrls)
                    webV1Addresses(purchase.addressUseCases)
                    webV1SharedWishlist(wishlist.getShared, mediaUrls)
                    // Back from the gateway and the result page, for paying what a change left owed (phase 3).
                    webV1Payments(purchase.paymentUseCases, PaymentConfig.DEFAULT.resultUrl)
                    webV1Tracking(purchase.trackingUseCases, mediaUrls)
                    // The cart, for the tests that follow a change of the settings into the shop (phase 4).
                    webV1Cart(purchase.cartUseCases, mediaUrls)
                }
            }
        }
        block()
    }

    /** Every test user (phones 09900…) with their sessions and codes. Security events stay: that table is append-only. */
    fun deleteWhatWasStored() {
        callers.clear()
        // First the uploads and orders of the test users, found by their phones before the users go.
        transaction(db) {
            val userIds = UserTbl.select(UserTbl.id).where { UserTbl.phone like TEST_PHONES }.map { it[UserTbl.id].value }
            // Their reviews, and the ratings of the products they reviewed (votes go with their reviews).
            val reviewed = ReviewTbl.select(ReviewTbl.productId).where { ReviewTbl.userId inList userIds }.map { it[ReviewTbl.productId] }.distinct()
            ReviewVoteTbl.deleteWhere { ReviewVoteTbl.userId inList userIds }
            ReviewTbl.deleteWhere { ReviewTbl.userId inList userIds }
            WishlistItemTbl.deleteWhere { WishlistItemTbl.userId inList userIds }
            WishlistShareTbl.deleteWhere { WishlistShareTbl.userId inList userIds }
            StockAlertTbl.deleteWhere { StockAlertTbl.userId inList userIds }
            ProductRatingTbl.deleteWhere { ProductRatingTbl.productId inList reviewed }
            MediaTbl.deleteWhere { MediaTbl.uploadedBy inList userIds }
        }
        purchase.deleteWhatWasStored()
        transaction(db) {
            val userIds = UserTbl.select(UserTbl.id).where { UserTbl.phone like TEST_PHONES }.map { it[UserTbl.id].value }
            val sessionIds = AuthSessionTbl.select(AuthSessionTbl.id).where { AuthSessionTbl.userId inList userIds }.map { it[AuthSessionTbl.id].value }
            RefreshTokenTbl.deleteWhere { RefreshTokenTbl.sessionId inList sessionIds }
            AuthSessionTbl.deleteWhere { AuthSessionTbl.userId inList userIds }
            OtpChallengeTbl.deleteWhere { OtpChallengeTbl.phone like TEST_PHONES }
            MediaTbl.deleteWhere { MediaTbl.uploadedBy inList userIds }
            UserTbl.deleteWhere { UserTbl.phone like TEST_PHONES }
        }
        if (Files.exists(mediaRoot)) Files.walk(mediaRoot).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    companion object {
        private const val TEST_PHONES = "09900%"
        const val MEDIA_BASE_URL = "https://api.kivashop.test/media"
        const val SITE_URL = "https://kivashop.test"

        /** `0990000000n`: the panel's test phones. */
        fun phone(n: Int) = "09900" + n.toString().padStart(6, '0')
    }
}
