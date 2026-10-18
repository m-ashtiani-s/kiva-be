package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.MediaProcessor
import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.admin.usecase.command.SetUserRolesUc
import com.kiva.application.usecase.admin.usecase.query.FindCustomersUc
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
import com.kiva.application.usecase.inventory.usecase.command.AdjustInventoryUc
import com.kiva.application.usecase.inventory.usecase.query.ListInventoryAdjustmentsUc
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.application.usecase.media.usecase.command.UploadMediaUc
import com.kiva.application.usecase.order.usecase.command.AddOrderNoteUc
import com.kiva.application.usecase.order.usecase.command.ApproveSilentPhotosUc
import com.kiva.application.usecase.order.usecase.command.ChangeOrderStatusUc
import com.kiva.application.usecase.order.usecase.command.SendPreShipmentMediaUc
import com.kiva.application.usecase.order.usecase.command.SetPreShipmentMediaUc
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
import com.kiva.common.patch.Patch
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.product.ProductDomainService
import com.kiva.domain.aggregate.refund.RefundDomainService
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
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.media.DiskMediaStorage
import com.kiva.infrastructure.media.FakeMediaProcessor
import com.kiva.infrastructure.media.MediaUrls
import com.kiva.infrastructure.media.RandomMediaIds
import com.kiva.infrastructure.persistence.exposed.AdminCustomerRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.AuditLogQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.AuditTrailImpl
import com.kiva.infrastructure.persistence.exposed.AuthSessionRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.CategoryIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.CategoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ColorRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.InventoryAdjustmentQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.InventoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.MaterialIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.MaterialRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.MediaRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OrderNoteRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OrderQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.ProductQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.RefundIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.SecurityEventLogImpl
import com.kiva.infrastructure.persistence.exposed.ShopSettingsRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.AuthSessionTbl
import com.kiva.infrastructure.persistence.exposed.tbl.MediaTbl
import com.kiva.infrastructure.persistence.exposed.tbl.OtpChallengeTbl
import com.kiva.infrastructure.persistence.exposed.tbl.RefreshTokenTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

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
        findProducts = FindAdminProductsUc(transactions, productQm, categories),
        getProduct = GetAdminProductUc(transactions, productQm, categories, materials, colors, users, ShopSettingsRepositoryImpl(), clock),
        createProduct = CreateProductUc(
            transactions, productDomainService, products, productQm, productInput, mediaAttachments, InventoryDomainService(inventories), inventories,
            auditTrail, clock,
        ),
        updateProduct = UpdateProductUc(transactions, productDomainService, products, productInput, auditTrail, clock),
        changeStatus = ChangeProductStatusUc(transactions, products, auditTrail, clock),
        archiveProduct = ArchiveProductUc(transactions, products, auditTrail, clock),
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
        sendPhotos = SendPreShipmentMediaUc(transactions, purchase.orderByCode, purchase.orders, users, auditTrail, purchase.orderNotices, clock),
        createRefund = CreateRefundUc(
            transactions, purchase.orderByCode, purchase.orderQm, purchase.orders, purchase.payments, purchase.refunds, RefundDomainService(RefundIdGeneratorImpl()),
            auditTrail, clock,
        ),
        changeStatus = ChangeOrderStatusUc(transactions, purchase.orderByCode, purchase.orderCancellation, users, auditTrail, purchase.orderNotices, clock),
    )
    val adminRefunds = AdminRefundUseCases(
        checkPanelAccess,
        UpdateRefundUc(transactions, purchase.refunds, purchase.orderQm, purchase.orders, users, auditTrail, purchase.orderNotices, clock),
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
                    webV1AdminCustomers(AdminCustomerUseCases(checkPanelAccess, findCustomers, getCustomer, setUserRoles))
                    webV1AdminAudit(AdminAuditUseCases(checkPanelAccess, listAuditLog))
                    webV1AdminMedia(AdminMediaUseCases(checkPanelAccess, uploadMedia), mediaStorage::newUploadFile, mediaUrls)
                    webV1AdminCatalog(adminCatalog, mediaUrls, SITE_URL)
                    webV1AdminProducts(adminProducts, SITE_URL, mediaUrls)
                    webV1AdminInventory(adminInventory)
                    webV1AdminOrders(adminOrders, mediaUrls)
                    webV1AdminRefunds(adminRefunds)
                    // The customer's side of an order, for the tests that follow it from the panel to «سفارش‌های من».
                    webV1MyOrders(purchase.myOrderUseCases, mediaUrls)
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
