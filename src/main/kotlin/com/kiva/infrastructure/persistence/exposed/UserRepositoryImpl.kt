package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.user.entity.User
import com.kiva.domain.aggregate.user.model.valueobject.BirthDate
import com.kiva.domain.aggregate.user.model.valueobject.Email
import com.kiva.domain.aggregate.user.model.valueobject.PersonName
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.domain.aggregate.user.model.valueobject.Roles
import com.kiva.domain.aggregate.user.repository.UserRepository
import com.kiva.domain.aggregate.user.repository.UserRepository.FoundOrRegistered
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ComparisonOp
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class UserRepositoryImpl : UserRepository {

    override fun findById(id: UserId): User? =
        UserTbl.selectAll()
            .where { UserTbl.id eq id.value }
            .singleOrNull()
            ?.toUser()

    override fun findByPhone(phone: IranianMobileNumber): User? =
        UserTbl.selectAll()
            .where { UserTbl.phone eq phone.value }
            .singleOrNull()
            ?.toUser()

    /**
     * Most logins are of a phone that has an account, so it is looked up first. Otherwise the new account takes its id
     * from the table's sequence and is inserted with `ON CONFLICT DO NOTHING`: if another login of the same phone
     * inserted its account in the meantime, this insert waits for that one to commit, then does nothing, and that
     * account is the one returned. So two logins never make two accounts, and neither of them fails. The id this one
     * took is skipped (ids may have gaps, never repeats).
     */
    override fun findOrRegister(phone: IranianMobileNumber, now: Instant): FoundOrRegistered {
        findByPhone(phone)?.let { return FoundOrRegistered(it, isNew = false) }

        val user = User.register(nextId(), phone, now)
        val inserted = UserTbl.insertIgnore {
            it[UserTbl.id] = user.id.value
            it[createdOn] = user.createdOn.atOffset(ZoneOffset.UTC)
            it[version] = user.version
            it.writeChangeable(user)
        }.insertedCount == 1
        if (inserted) return FoundOrRegistered(user, isNew = true)

        val registeredMeanwhile = findByPhone(phone) ?: error("Registering ${phone.masked()} was refused, yet it has no account")
        return FoundOrRegistered(registeredMeanwhile, isNew = false)
    }

    /** Writes the row only if it still has the version [user] was read with, and raises that version. */
    override fun save(user: User) {
        val updated = UserTbl.update({ (UserTbl.id eq user.id.value) and (UserTbl.version eq user.version) }) {
            it[version] = user.version + 1
            it.writeChangeable(user)
        }
        if (updated == 0) throw ConcurrentChangeException(AGGREGATE_NAME, EventAggregateId.of(user.id.value))
    }

    override fun hasAnyAdmin(): Boolean =
        !UserTbl.select(UserTbl.id)
            .where { Contains(UserTbl.roles, listOf(Role.ADMIN.name)) }
            .limit(1)
            .empty()

    /** Everything a user can change after registering; the id and createdOn never change. */
    private fun UpdateBuilder<*>.writeChangeable(user: User) {
        this[UserTbl.phone] = user.phone.value
        this[UserTbl.firstName] = user.firstName?.value
        this[UserTbl.lastName] = user.lastName?.value
        this[UserTbl.email] = user.email?.value
        this[UserTbl.birthDate] = user.birthDate?.value
        this[UserTbl.gender] = user.gender
        this[UserTbl.defaultMessenger] = user.defaultMessenger
        this[UserTbl.defaultMessengerPhone] = user.defaultMessengerPhone?.value
        this[UserTbl.marketingSmsOptIn] = user.marketingSmsOptIn
        this[UserTbl.roles] = user.roles.values.map { it.name }
    }

    private fun nextId(): UserId =
        TransactionManager.current().exec("SELECT nextval(${UserTbl.ID_SEQUENCE_OF})") { rows ->
            rows.next()
            UserId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval of user_tbl's id sequence returned nothing")

    /** Postgres `@>`: the array column holds every one of [values]. */
    private class Contains(column: Column<List<String>>, values: List<String>) :
        ComparisonOp(column, QueryParameter(values, column.columnType), "@>")

    private companion object {
        /** How a refused save names the user; not an event stream, but the same 409 for the client. */
        val AGGREGATE_NAME = EventAggregateName("USER")
    }
}

/** A row of user_tbl as the User it stores; every repository that reads users reads them through this. */
internal fun ResultRow.toUser(): User =
    User.constructFromPersisted(
        id = UserId.constructFromPersisted(this[UserTbl.id].value),
        phone = IranianMobileNumber.constructFromPersisted(this[UserTbl.phone]),
        firstName = this[UserTbl.firstName]?.let(PersonName::constructFromPersisted),
        lastName = this[UserTbl.lastName]?.let(PersonName::constructFromPersisted),
        email = this[UserTbl.email]?.let(Email::constructFromPersisted),
        birthDate = this[UserTbl.birthDate]?.let(BirthDate::constructFromPersisted),
        gender = this[UserTbl.gender],
        defaultMessenger = this[UserTbl.defaultMessenger],
        defaultMessengerPhone = this[UserTbl.defaultMessengerPhone]?.let(IranianMobileNumber::constructFromPersisted),
        marketingSmsOptIn = this[UserTbl.marketingSmsOptIn],
        roles = Roles.constructFromPersisted(this[UserTbl.roles].map(Role::valueOf)),
        createdOn = this[UserTbl.createdOn].toInstant(),
        version = this[UserTbl.version],
    )
