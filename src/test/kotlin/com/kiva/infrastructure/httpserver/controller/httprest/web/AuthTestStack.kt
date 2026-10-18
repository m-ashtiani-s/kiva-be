package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.OtpCodes
import com.kiva.application.port.SmsSender
import com.kiva.application.usecase.account.usecase.command.ConfirmPhoneChangeUc
import com.kiva.application.usecase.account.usecase.command.RequestPhoneChangeUc
import com.kiva.application.usecase.account.usecase.command.UpdateMyProfileUc
import com.kiva.application.usecase.account.usecase.query.GetMeUc
import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.admin.usecase.command.SetUserRolesUc
import com.kiva.application.usecase.admin.usecase.query.FindCustomersUc
import com.kiva.application.usecase.admin.usecase.query.GetCustomerUc
import com.kiva.application.usecase.auth.OtpIssuer
import com.kiva.application.usecase.auth.usecase.command.BootstrapAdminUc
import com.kiva.application.usecase.auth.usecase.command.LogoutUc
import com.kiva.application.usecase.auth.usecase.command.RefreshSessionUc
import com.kiva.application.usecase.auth.usecase.command.SendLoginCodeUc
import com.kiva.application.usecase.auth.usecase.command.VerifyLoginCodeUc
import com.kiva.application.usecase.cart.CartCommands
import com.kiva.application.usecase.cart.usecase.command.MergeGuestCartUc
import com.kiva.application.usecase.cart.usecase.query.GetCartUc
import com.kiva.application.usecase.discount.Discounts
import com.kiva.application.usecase.reservation.Reservations
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.cart.CartDomainService
import com.kiva.domain.aggregate.discount.DiscountDomainService
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.reservation.ReservationDomainService
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.auth.HmacOtpCodes
import com.kiva.infrastructure.auth.JwtAccessTokens
import com.kiva.infrastructure.auth.RandomSessionTokens
import com.kiva.infrastructure.cart.RandomCartIds
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.persistence.exposed.AddressBookRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.AdminCustomerRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.AuditTrailImpl
import com.kiva.infrastructure.persistence.exposed.AuthSessionRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.CartRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.DiscountCodeRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.DiscountRedemptionRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.GeoRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OfferQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OrderQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OtpChallengeRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ReservationGroupIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.ReservationGroupRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.SecurityEventLogImpl
import com.kiva.infrastructure.persistence.exposed.ShopSettingsRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.AuthSessionTbl
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
import java.util.Collections

/**
 * The login use cases over kiva_test, wired as the app wires them, with a test clock, an SMS sender that remembers
 * what it sent, and fixed secrets. Every test phone starts with 0990; [deleteTestUsers] removes their sessions, codes,
 * accounts and carts. [otpCodes] are the real ones unless a test needs to know the codes in advance. A login's answer
 * has the customer's cart, so the cart's merge is wired too.
 */
class AuthTestStack(val clock: MutableClock, maxSmsPerHour: Int = 300, val otpCodes: OtpCodes = HmacOtpCodes(OTP_PEPPER)) {

    val db = TestDatabase.database
    val sms = RecordingSmsSender()
    val accessTokens = JwtAccessTokens(JWT_SECRET, clock)
    val sessionTokens = RandomSessionTokens()

    private val transactions = ExposedTransactionManager(db)
    private val codes = OtpChallengeRepositoryImpl()
    private val users = UserRepositoryImpl()
    private val events = SecurityEventLogImpl()
    private val sessions = AuthSessionRepositoryImpl()

    val otpIssuer = OtpIssuer(codes, otpCodes, events, maxSmsPerHour)
    val send = SendLoginCodeUc(transactions, otpIssuer, sms, clock)
    val verify = VerifyLoginCodeUc(transactions, codes, users, sessions, otpCodes, sessionTokens, accessTokens, events, clock)
    val getMe = GetMeUc(transactions, users)
    val refresh = RefreshSessionUc(transactions, sessions, users, sessionTokens, accessTokens, events, clock)
    val logout = LogoutUc(transactions, sessions, sessionTokens, events, clock)
    val updateProfile = UpdateMyProfileUc(transactions, users, clock)
    val checkPanelAccess = CheckPanelAccessUc(transactions, sessions, users, events, clock)
    val findCustomers = FindCustomersUc(transactions, AdminCustomerRepositoryImpl(), OrderQmRepositoryImpl())
    val getCustomer = GetCustomerUc(transactions, AdminCustomerRepositoryImpl(), OrderQmRepositoryImpl())
    val setUserRoles = SetUserRolesUc(transactions, users, sessions, events, AuditTrailImpl(), clock)
    val bootstrapAdmin = BootstrapAdminUc(transactions, users, sessions, events, clock)
    val requestPhoneChange = RequestPhoneChangeUc(transactions, users, sessions, otpIssuer, sms, clock)
    val confirmPhoneChange = ConfirmPhoneChangeUc(transactions, codes, users, sessions, otpCodes, sessionTokens, accessTokens, sms, events, clock)

    private val carts = CartRepositoryImpl()
    val cartDomainService = CartDomainService(RandomCartIds())
    val reservations = Reservations(
        ReservationGroupRepositoryImpl(), AddressBookRepositoryImpl(), GeoRepositoryImpl(), OrderQmRepositoryImpl(),
        ReservationDomainService(ReservationGroupIdGeneratorImpl()),
    )
    val discounts = Discounts(DiscountCodeRepositoryImpl(), DiscountRedemptionRepositoryImpl(), OrderQmRepositoryImpl(), DiscountDomainService())
    val cartCommands =
        CartCommands(transactions, carts, cartDomainService, OfferQmRepositoryImpl(), ShopSettingsRepositoryImpl(), reservations, discounts, clock)
    val mergeGuestCart = MergeGuestCartUc(cartCommands, cartDomainService)
    val getCart = GetCartUc(transactions, carts, OfferQmRepositoryImpl(), ShopSettingsRepositoryImpl(), reservations, discounts, clock)

    val authUseCases = AuthUseCases(send, verify, getMe, refresh, logout, mergeGuestCart, getCart)

    fun app(trustedProxyHops: Int = 0, allowedOrigins: List<String> = listOf(ORIGIN), block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                configureHttp(allowedOrigins, trustedProxyHops)
                configureAuthentication(accessTokens)
                routing {
                    route(API_V1) {
                        webV1Auth(authUseCases, allowedOrigins, testMediaUrls)
                        webV1Me(MeUseCases(getMe, updateProfile, requestPhoneChange, confirmPhoneChange))
                        webV1AdminCustomers(AdminCustomerUseCases(checkPanelAccess, findCustomers, getCustomer, setUserRoles))
                    }
                }
            }
            block()
        }

    /** The code the last SMS to [phone] carried. */
    fun lastCodeSentTo(phone: String): OtpCode = sms.sent.last { it.first.value == phone }.second

    // Their carts go with the accounts: cart_tbl's foreign key is ON DELETE CASCADE.
    fun deleteTestUsers() {
        transaction(db) {
            val userIds = UserTbl.select(UserTbl.id).where { UserTbl.phone like TEST_PHONES }.map { it[UserTbl.id].value }
            val sessionIds = AuthSessionTbl.select(AuthSessionTbl.id).where { AuthSessionTbl.userId inList userIds }.map { it[AuthSessionTbl.id].value }
            RefreshTokenTbl.deleteWhere { RefreshTokenTbl.sessionId inList sessionIds }
            AuthSessionTbl.deleteWhere { AuthSessionTbl.userId inList userIds }
            OtpChallengeTbl.deleteWhere { OtpChallengeTbl.phone like TEST_PHONES }
            UserTbl.deleteWhere { UserTbl.phone like TEST_PHONES }
        }
    }

    companion object {
        val OTP_PEPPER = ByteArray(32) { 7 }

        /** The frontend the test apps allow; refresh and logout answer only it. */
        const val ORIGIN = "https://kiva.ir"
        val JWT_SECRET = ByteArray(32) { 8 }
        private const val TEST_PHONES = "0990%"

        fun phone(n: Int) = "0990" + n.toString().padStart(7, '0')
    }
}

/** Remembers what it sent; fails when told to, like a provider that is down. */
class RecordingSmsSender : SmsSender {
    val sent: MutableList<Pair<IranianMobileNumber, OtpCode>> = Collections.synchronizedList(mutableListOf())

    /** Warnings that an account moved, as (old phone, end of the new number). */
    val notices: MutableList<Pair<IranianMobileNumber, String>> = Collections.synchronizedList(mutableListOf())

    /** «پرداخت موفق» messages, as (phone, order). */
    val paid: MutableList<Pair<IranianMobileNumber, OrderCode>> = Collections.synchronizedList(mutableListOf())

    /** «عکس کیفت فرستاده شد» messages, as (phone, order). */
    val photosSent: MutableList<Pair<IranianMobileNumber, OrderCode>> = Collections.synchronizedList(mutableListOf())

    /** «پولت برگشت» messages, as (phone, order). */
    val refundsCompleted: MutableList<Pair<IranianMobileNumber, OrderCode>> = Collections.synchronizedList(mutableListOf())

    /** «لغو شد» messages, as (phone, order). */
    val cancelled: MutableList<Pair<IranianMobileNumber, OrderCode>> = Collections.synchronizedList(mutableListOf())

    /** «ارسال شد» messages. */
    val shipped: MutableList<Shipped> = Collections.synchronizedList(mutableListOf())

    /** «تحویل شد» messages, as (phone, order). */
    val delivered: MutableList<Pair<IranianMobileNumber, OrderCode>> = Collections.synchronizedList(mutableListOf())

    /** «درخواستت بررسی شد» messages, as (phone, order). */
    val changeResolved: MutableList<Pair<IranianMobileNumber, OrderCode>> = Collections.synchronizedList(mutableListOf())

    data class Shipped(val to: IranianMobileNumber, val orderCode: OrderCode, val carrierName: String, val trackingCode: String)

    @Volatile
    var down = false

    override suspend fun sendCode(to: IranianMobileNumber, code: OtpCode, purpose: OtpPurpose) {
        check(!down) { "the SMS provider is down" }
        sent += to to code
    }

    override suspend fun sendPhoneChangedNotice(oldPhone: IranianMobileNumber, newPhoneEnding: String) {
        check(!down) { "the SMS provider is down" }
        notices += oldPhone to newPhoneEnding
    }

    override suspend fun sendOrderPaid(to: IranianMobileNumber, orderCode: OrderCode) {
        check(!down) { "the SMS provider is down" }
        paid += to to orderCode
    }

    override suspend fun sendPhotosSent(to: IranianMobileNumber, orderCode: OrderCode) {
        check(!down) { "the SMS provider is down" }
        photosSent += to to orderCode
    }

    override suspend fun sendRefundCompleted(to: IranianMobileNumber, orderCode: OrderCode) {
        check(!down) { "the SMS provider is down" }
        refundsCompleted += to to orderCode
    }

    override suspend fun sendOrderCancelled(to: IranianMobileNumber, orderCode: OrderCode) {
        check(!down) { "the SMS provider is down" }
        cancelled += to to orderCode
    }

    override suspend fun sendOrderShipped(to: IranianMobileNumber, orderCode: OrderCode, carrierName: String, trackingCode: String) {
        check(!down) { "the SMS provider is down" }
        shipped += Shipped(to, orderCode, carrierName, trackingCode)
    }

    override suspend fun sendOrderDelivered(to: IranianMobileNumber, orderCode: OrderCode) {
        check(!down) { "the SMS provider is down" }
        delivered += to to orderCode
    }

    override suspend fun sendChangeResolved(to: IranianMobileNumber, orderCode: OrderCode) {
        check(!down) { "the SMS provider is down" }
        changeResolved += to to orderCode
    }
}
