package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.otp.entity.OtpChallenge
import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy
import com.kiva.domain.aggregate.otp.model.valueobject.CodeHash
import com.kiva.domain.aggregate.otp.model.valueobject.OtpChallengeId
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.otp.model.valueobject.Salt
import com.kiva.domain.aggregate.otp.repository.OtpChallengeRepository
import com.kiva.domain.aggregate.otp.repository.OtpChallengeRepository.SentToAnyone
import com.kiva.infrastructure.persistence.exposed.tbl.OtpChallengeTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class OtpChallengeRepositoryImpl : OtpChallengeRepository {

    /**
     * A transaction-level advisory lock, keyed by the phone: released by the commit or rollback, so it can't be left
     * behind. Two phones may share a key (it is a 32-bit hash); that only makes them wait for each other now and then.
     */
    override fun lockPhone(phone: IranianMobileNumber) {
        TransactionManager.current().exec(
            "SELECT pg_advisory_xact_lock(hashtext(?))",
            listOf(TextColumnType() to "otp:${phone.value}"),
        ) { }
    }

    override fun nextId(): OtpChallengeId =
        TransactionManager.current().exec("SELECT nextval(${OtpChallengeTbl.ID_SEQUENCE_OF})") { rows ->
            rows.next()
            OtpChallengeId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval of otpchallenge_tbl's id sequence returned nothing")

    override fun findOpen(phone: IranianMobileNumber, purpose: OtpPurpose): OtpChallenge? =
        OtpChallengeTbl.selectAll()
            .where {
                (OtpChallengeTbl.phone eq phone.value) and (OtpChallengeTbl.purpose eq purpose) and
                    OtpChallengeTbl.consumedOn.isNull() and OtpChallengeTbl.replacedOn.isNull()
            }
            .singleOrNull()
            ?.toChallenge()

    override fun sentTo(phone: IranianMobileNumber, since: Instant): List<OtpPolicy.SentCode> =
        OtpChallengeTbl.select(OtpChallengeTbl.purpose, OtpChallengeTbl.createdOn, OtpChallengeTbl.failedAttempts)
            .where { (OtpChallengeTbl.phone eq phone.value) and (OtpChallengeTbl.createdOn greater since.utc()) }
            .map {
                OtpPolicy.SentCode(it[OtpChallengeTbl.purpose], it[OtpChallengeTbl.createdOn].toInstant(), it[OtpChallengeTbl.failedAttempts].toInt())
            }

    override fun sentToAnyone(since: Instant): SentToAnyone {
        val count = OtpChallengeTbl.id.count()
        val earliest = OtpChallengeTbl.createdOn.min()
        val row = OtpChallengeTbl.select(count, earliest)
            .where { OtpChallengeTbl.createdOn greater since.utc() }
            .single()
        return SentToAnyone(row[count].toInt(), row[earliest]?.toInstant())
    }

    override fun add(challenge: OtpChallenge, requestIp: String?) {
        OtpChallengeTbl.insert {
            it[id] = challenge.id.value
            it[purpose] = challenge.purpose
            it[phone] = challenge.phone.value
            it[userId] = challenge.userId?.value
            it[authorizesPhone] = challenge.authorizesPhone?.value
            it[salt] = challenge.salt.toByteArray()
            it[codeHash] = challenge.codeHash.toByteArray()
            it[failedAttempts] = challenge.failedAttempts.toShort()
            it[createdOn] = challenge.createdOn.utc()
            it[expiresOn] = challenge.expiresOn.utc()
            it[consumedOn] = challenge.consumedOn?.utc()
            it[replacedOn] = challenge.replacedOn?.utc()
            it[OtpChallengeTbl.requestIp] = requestIp
        }
    }

    /** Only what a stored code can change: its tries, its acceptance, its replacement. */
    override fun save(challenge: OtpChallenge) {
        val updated = OtpChallengeTbl.update({ OtpChallengeTbl.id eq challenge.id.value }) {
            it[failedAttempts] = challenge.failedAttempts.toShort()
            it[consumedOn] = challenge.consumedOn?.utc()
            it[replacedOn] = challenge.replacedOn?.utc()
        }
        check(updated == 1) { "Code ${challenge.id.value} is not stored, so it can't be saved" }
    }

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private fun ResultRow.toChallenge(): OtpChallenge =
        OtpChallenge.constructFromPersisted(
            id = OtpChallengeId.constructFromPersisted(this[OtpChallengeTbl.id].value),
            purpose = this[OtpChallengeTbl.purpose],
            phone = IranianMobileNumber.constructFromPersisted(this[OtpChallengeTbl.phone]),
            userId = this[OtpChallengeTbl.userId]?.let(UserId::constructFromPersisted),
            authorizesPhone = this[OtpChallengeTbl.authorizesPhone]?.let(IranianMobileNumber::constructFromPersisted),
            salt = Salt(this[OtpChallengeTbl.salt]),
            codeHash = CodeHash(this[OtpChallengeTbl.codeHash]),
            failedAttempts = this[OtpChallengeTbl.failedAttempts].toInt(),
            createdOn = this[OtpChallengeTbl.createdOn].toInstant(),
            expiresOn = this[OtpChallengeTbl.expiresOn].toInstant(),
            consumedOn = this[OtpChallengeTbl.consumedOn]?.toInstant(),
            replacedOn = this[OtpChallengeTbl.replacedOn]?.toInstant(),
        )
}
