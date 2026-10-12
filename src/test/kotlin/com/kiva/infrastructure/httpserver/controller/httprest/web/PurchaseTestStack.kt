package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.PaymentGateways
import com.kiva.application.repository.OfferQmRepository
import com.kiva.application.usecase.address.usecase.command.AddAddressUc
import com.kiva.application.usecase.address.usecase.command.DeleteAddressUc
import com.kiva.application.usecase.address.usecase.command.SetDefaultAddressUc
import com.kiva.application.usecase.address.usecase.command.UpdateAddressUc
import com.kiva.application.usecase.address.usecase.query.GetMyAddressUc
import com.kiva.application.usecase.address.usecase.query.ListMyAddressesUc
import com.kiva.application.usecase.cart.CartCommands
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.cart.usecase.command.ChangeCartItemUc
import com.kiva.application.usecase.cart.usecase.command.ChooseShippingMethodUc
import com.kiva.application.usecase.cart.usecase.command.ClearCartUc
import com.kiva.application.usecase.cart.usecase.command.MergeGuestCartUc
import com.kiva.application.usecase.cart.usecase.command.OpenCartUc
import com.kiva.application.usecase.cart.usecase.command.RemoveCartItemUc
import com.kiva.application.usecase.cart.usecase.command.SetCartReservationUc
import com.kiva.application.usecase.cart.usecase.query.GetCartUc
import com.kiva.application.usecase.checkout.usecase.command.HandleGatewayCallbackUc
import com.kiva.application.usecase.checkout.usecase.command.PlaceOrderUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentUc
import com.kiva.application.usecase.checkout.usecase.query.GetCheckoutContextUc
import com.kiva.application.usecase.checkout.usecase.query.GetPaymentInitUc
import com.kiva.application.usecase.checkout.usecase.query.GetPaymentResultUc
import com.kiva.application.usecase.idempotency.IdempotencyGuard
import com.kiva.application.usecase.order.usecase.query.GetOrderSummaryUc
import com.kiva.application.usecase.payment.PaymentConfirmation
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.AddressBookDomainService
import com.kiva.domain.aggregate.cart.CartDomainService
import com.kiva.domain.aggregate.cart.repository.CartRepository
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.order.OrderDomainService
import com.kiva.domain.aggregate.payment.PaymentDomainService
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.cart.RandomCartIds
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.ORIGIN
import com.kiva.infrastructure.payment.FakePaymentGateway
import com.kiva.infrastructure.payment.PaymentConfig
import com.kiva.infrastructure.payment.RandomPaymentIds
import com.kiva.infrastructure.persistence.exposed.AddressBookRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.AddressIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.CartRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.GeoRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.IdempotencyStoreImpl
import com.kiva.infrastructure.persistence.exposed.InventoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OfferQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OrderIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.OrderQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OrderRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.PaymentRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ShopSettingsRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.CartTbl
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Everything a purchase walks through, over the real stack on kiva_test: logins, the cart, the address book, checkout,
 * placing orders and coming back from the gateway, with the pretend gateway as Zarinpal and the SMS recorded. Built as the app builds it, minus Koin.
 * [deleteWhatWasStored] removes the test customers and everything of theirs (orders included), and belongs in an
 * @AfterEach; the catalog is the test's own business.
 */
class PurchaseTestStack(val clock: MutableClock) {

    val auth = AuthTestStack(clock)
    val db = auth.db
    val transactions = ExposedTransactionManager(db)
    val gateway = FakePaymentGateway(PaymentConfig.DEFAULT)

    val carts = CartRepositoryImpl()
    private val offers: OfferQmRepository = OfferQmRepositoryImpl()
    val settings = ShopSettingsRepositoryImpl()
    private val books = AddressBookRepositoryImpl()
    private val geo = GeoRepositoryImpl()
    val users = UserRepositoryImpl()
    val orders = OrderRepositoryImpl()
    val orderQm = OrderQmRepositoryImpl()
    val payments = PaymentRepositoryImpl()
    private val inventories = InventoryRepositoryImpl()
    val gateways = PaymentGateways(listOf(gateway))
    private val cartDomain = CartDomainService(RandomCartIds())
    private val cartCommands = CartCommands(transactions, carts, cartDomain, offers, settings, clock)
    private val addressService = AddressBookDomainService(geo, AddressIdGeneratorImpl())

    val openCart = OpenCartUc(cartCommands, cartDomain)
    val addCartItem = AddCartItemUc(cartCommands, cartDomain)
    val addAddress = AddAddressUc(transactions, addressService, books, clock)

    val placeOrder = placeOrderWith(carts)

    /** Placing orders as the app does, with [cartRepository] for the carts: for a test that slows one down. */
    fun placeOrderWith(cartRepository: CartRepository) = PlaceOrderUc(
        transactions, IdempotencyGuard(IdempotencyStoreImpl(), clock), cartRepository, offers, settings, books, addressService, geo, users,
        OrderDomainService(OrderIdGeneratorImpl()), orders, orderQm, InventoryDomainService(inventories), inventories, payments,
        RandomPaymentIds(), gateways, auth.sms, clock,
    )
    val startPayment = StartPaymentUc(transactions, payments, gateways, clock)

    val paymentDomain = PaymentDomainService()
    val paymentConfirmation = PaymentConfirmation(transactions, payments, orders, users, paymentDomain, gateways, auth.sms, clock)
    val handleCallback = HandleGatewayCallbackUc(transactions, payments, orders, paymentDomain, gateways, paymentConfirmation, clock)
    val paymentUseCases = PaymentUseCases(handleCallback, GetPaymentResultUc(transactions, payments, orderQm, settings, clock))

    val cartUseCases = CartUseCases(
        openCart,
        GetCartUc(transactions, carts, offers, settings, clock),
        addCartItem,
        ChangeCartItemUc(cartCommands, cartDomain),
        RemoveCartItemUc(cartCommands, cartDomain),
        ClearCartUc(cartCommands, cartDomain),
        ChooseShippingMethodUc(cartCommands, cartDomain),
        SetCartReservationUc(cartCommands, cartDomain),
        MergeGuestCartUc(cartCommands, cartDomain),
    )

    val addressUseCases = AddressUseCases(
        ListMyAddressesUc(transactions, books, geo),
        GetMyAddressUc(transactions, books, geo),
        addAddress,
        UpdateAddressUc(transactions, addressService, books, clock),
        DeleteAddressUc(transactions, books, clock),
        SetDefaultAddressUc(transactions, books, clock),
    )

    val checkoutUseCases = CheckoutUseCases(
        openCart,
        GetCheckoutContextUc(transactions, carts, offers, settings, books, geo, users, gateways, clock),
        placeOrder,
        startPayment,
        GetOrderSummaryUc(transactions, orderQm, settings, clock),
        GetPaymentInitUc(transactions, payments, orderQm),
    )

    fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp(listOf(ORIGIN))
            configureAuthentication(auth.accessTokens)
            routing {
                route(API_V1) {
                    webV1Auth(auth.authUseCases, listOf(ORIGIN))
                    webV1Cart(cartUseCases)
                    webV1Addresses(addressUseCases)
                    webV1Checkout(checkoutUseCases)
                    webV1Payments(paymentUseCases, PaymentConfig.DEFAULT.resultUrl)
                }
            }
        }
        block()
    }

    /** A customer of [phone] (a test phone, `0990…`), registered as a login would: for calling the use cases directly. */
    fun customer(phone: String): UserId = transaction(db) {
        users.findOrRegister(IranianMobileNumber.constructFromPersisted(phone), clock.now).user.id
    }

    /** The variant id of a seeded bag's color. */
    fun variantOf(slug: String, color: String): Long = transaction(db) {
        ProductVariantQmTbl.join(ProductQmTbl, JoinType.INNER, ProductVariantQmTbl.productId, ProductQmTbl.productId)
            .selectAll()
            .where { (ProductQmTbl.slug eq slug) and (ProductVariantQmTbl.colorKey eq color) }
            .single()[ProductVariantQmTbl.variantId]
    }

    /** What the read model says is left of a color: on hand minus what orders hold. */
    fun availableOf(variantId: Long): Int = transaction(db) {
        ProductVariantQmTbl.select(ProductVariantQmTbl.onHand, ProductVariantQmTbl.reserved)
            .where { ProductVariantQmTbl.variantId eq variantId }
            .single()
            .let { it[ProductVariantQmTbl.onHand] - it[ProductVariantQmTbl.reserved] }
    }

    /** Guest carts, the test customers with their carts, addresses and keys, and every order of theirs. */
    fun deleteWhatWasStored() {
        transaction(db) {
            val userIds = UserTbl.select(UserTbl.id).where { UserTbl.phone like "0990%" }.map { it[UserTbl.id].value }
            val orderIds = OrderQmTbl.select(OrderQmTbl.orderId).where { OrderQmTbl.userId inList userIds }.map { it[OrderQmTbl.orderId] }
            EventStoreTbl.deleteWhere { (aggregateName eq "ORDER") and (aggregateId inList orderIds.map { it.toString() }) }
            OrderQmTbl.deleteWhere { orderId inList orderIds }
            CartTbl.deleteWhere { userId.isNull() }
        }
        // Payments stay: their log is append-only, and their orders' ids are never handed out again.
        auth.deleteTestUsers()
    }
}
