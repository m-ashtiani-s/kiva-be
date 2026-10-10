package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.SmsSender
import com.kiva.application.usecase.account.usecase.command.UpdateMyProfileUc
import com.kiva.application.usecase.account.usecase.query.GetMeUc
import com.kiva.application.usecase.auth.usecase.command.LogoutUc
import com.kiva.application.usecase.auth.usecase.command.RefreshSessionUc
import com.kiva.application.usecase.auth.usecase.command.SendLoginCodeUc
import com.kiva.application.usecase.auth.usecase.command.VerifyLoginCodeUc
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.auth.HmacOtpCodes
import com.kiva.infrastructure.auth.JwtAccessTokens
import com.kiva.infrastructure.auth.RandomSessionTokens
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.persistence.exposed.AuthSessionRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OtpChallengeRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.SecurityEventLogImpl
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
 * what it sent, and fixed secrets. Every test phone starts with 0990; [deleteTestUsers] removes their sessions, codes
 * and accounts.
 */
class AuthTestStack(val clock: MutableClock, maxSmsPerHour: Int = 300) {

    val db = TestDatabase.database
    val sms = RecordingSmsSender()
    val otpCodes = HmacOtpCodes(OTP_PEPPER)
    val accessTokens = JwtAccessTokens(JWT_SECRET, clock)
    val sessionTokens = RandomSessionTokens()

    private val transactions = ExposedTransactionManager(db)
    private val codes = OtpChallengeRepositoryImpl()
    private val users = UserRepositoryImpl()
    private val events = SecurityEventLogImpl()
    private val sessions = AuthSessionRepositoryImpl()

    val send = SendLoginCodeUc(transactions, codes, otpCodes, sms, events, clock, maxSmsPerHour)
    val verify = VerifyLoginCodeUc(transactions, codes, users, sessions, otpCodes, sessionTokens, accessTokens, events, clock)
    val getMe = GetMeUc(transactions, users)
    val refresh = RefreshSessionUc(transactions, sessions, users, sessionTokens, accessTokens, events, clock)
    val logout = LogoutUc(transactions, sessions, sessionTokens, events, clock)
    val updateProfile = UpdateMyProfileUc(transactions, users, clock)

    fun app(trustedProxyHops: Int = 0, allowedOrigins: List<String> = listOf(ORIGIN), block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                configureHttp(allowedOrigins, trustedProxyHops)
                configureAuthentication(accessTokens)
                routing {
                    route(API_V1) {
                        webV1Auth(AuthUseCases(send, verify, getMe, refresh, logout), allowedOrigins)
                        webV1Me(getMe, updateProfile)
                    }
                }
            }
            block()
        }

    /** The code the last SMS to [phone] carried. */
    fun lastCodeSentTo(phone: String): OtpCode = sms.sent.last { it.first.value == phone }.second

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

    @Volatile
    var down = false

    override suspend fun sendCode(to: IranianMobileNumber, code: OtpCode, purpose: OtpPurpose) {
        check(!down) { "the SMS provider is down" }
        sent += to to code
    }
}
