package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.entity.Address
import com.kiva.domain.aggregate.address.entity.AddressBook
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.domain.aggregate.address.model.valueobject.AddressLine
import com.kiva.domain.aggregate.address.model.valueobject.AddressTitle
import com.kiva.domain.aggregate.address.model.valueobject.GeoPoint
import com.kiva.domain.aggregate.address.model.valueobject.Plaque
import com.kiva.domain.aggregate.address.model.valueobject.PostalCode
import com.kiva.domain.aggregate.address.model.valueobject.RecipientName
import com.kiva.domain.aggregate.address.model.valueobject.UnitNumber
import com.kiva.domain.aggregate.address.repository.AddressBookRepository
import com.kiva.domain.aggregate.geo.model.valueobject.CityId
import com.kiva.domain.aggregate.geo.model.valueobject.ProvinceId
import com.kiva.infrastructure.persistence.exposed.tbl.AddressTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class AddressBookRepositoryImpl : AddressBookRepository {

    override fun find(userId: UserId): AddressBook = AddressBook.constructFromPersisted(userId, rowsOf(userId).map { it.toAddress() })

    /**
     * A transaction-level advisory lock, keyed by the customer: released by the commit or rollback, so it can't be left
     * behind, and it works for an empty book, which has no row to lock. Two customers may share a key (it is a 32-bit
     * hash); that only makes them wait for each other now and then.
     */
    override fun lockAndFind(userId: UserId): AddressBook {
        TransactionManager.current().exec(
            "SELECT pg_advisory_xact_lock(hashtext(?))",
            listOf(TextColumnType() to "addressbook:${userId.value}"),
        ) { }
        return find(userId)
    }

    /**
     * Compares the book with its rows and writes only the difference. The order matters because the database allows
     * one default per customer and checks it statement by statement: removed rows first, then the changed rows that stop
     * being the default, then the other changes, and the new rows last.
     */
    override fun deleteAllOf(userId: UserId): Int = AddressTbl.deleteWhere { AddressTbl.userId eq userId.value }

    override fun save(book: AddressBook) {
        val stored = rowsOf(book.userId).associate { it[AddressTbl.addressId] to it.toAddress() }
        val wanted = book.addresses.associateBy { it.id.value }

        val removed = stored.keys - wanted.keys
        if (removed.isNotEmpty()) AddressTbl.deleteWhere { addressId inList removed }

        wanted.values
            .filter { it.id.value in stored && !sameValues(it, stored.getValue(it.id.value)) }
            .sortedBy { it.isDefault }
            .forEach { address -> AddressTbl.update({ AddressTbl.addressId eq address.id.value }) { it.write(address) } }

        wanted.values.filter { it.id.value !in stored }.forEach { address ->
            AddressTbl.insert {
                it[addressId] = address.id.value
                it[userId] = book.userId.value
                it[createdOn] = address.createdOn.utc()
                it.write(address)
            }
        }
    }

    private fun rowsOf(userId: UserId): List<ResultRow> = AddressTbl.selectAll().where { AddressTbl.userId eq userId.value }.toList()

    /** Address has identity equality (by id), so a change is found by comparing every stored value. */
    private fun sameValues(a: Address, b: Address): Boolean =
        a.title == b.title && a.provinceId == b.provinceId && a.cityId == b.cityId && a.line == b.line && a.plaque == b.plaque &&
            a.unit == b.unit && a.postalCode == b.postalCode && a.recipientName == b.recipientName && a.recipientPhone == b.recipientPhone &&
            a.isSelfRecipient == b.isSelfRecipient && a.location == b.location && a.isDefault == b.isDefault && a.updatedOn == b.updatedOn

    /** Every column a change can touch; the id, the customer and the creation time never change. */
    private fun UpdateBuilder<*>.write(address: Address) {
        this[AddressTbl.title] = address.title?.value
        this[AddressTbl.provinceId] = address.provinceId.value
        this[AddressTbl.cityId] = address.cityId.value
        this[AddressTbl.addressLine] = address.line.value
        this[AddressTbl.plaque] = address.plaque?.value
        this[AddressTbl.unit] = address.unit?.value
        this[AddressTbl.postalCode] = address.postalCode.value
        this[AddressTbl.recipientName] = address.recipientName.value
        this[AddressTbl.recipientPhone] = address.recipientPhone.value
        this[AddressTbl.isSelfRecipient] = address.isSelfRecipient
        this[AddressTbl.lat] = address.location?.lat
        this[AddressTbl.lng] = address.location?.lng
        this[AddressTbl.isDefault] = address.isDefault
        this[AddressTbl.updatedOn] = address.updatedOn.utc()
    }

    private fun ResultRow.toAddress(): Address =
        Address.constructFromPersisted(
            id = AddressId.constructFromPersisted(this[AddressTbl.addressId]),
            title = this[AddressTbl.title]?.let { AddressTitle.constructFromPersisted(it) },
            provinceId = ProvinceId.constructFromPersisted(this[AddressTbl.provinceId]),
            cityId = CityId.constructFromPersisted(this[AddressTbl.cityId]),
            line = AddressLine.constructFromPersisted(this[AddressTbl.addressLine]),
            plaque = this[AddressTbl.plaque]?.let { Plaque.constructFromPersisted(it) },
            unit = this[AddressTbl.unit]?.let { UnitNumber.constructFromPersisted(it) },
            postalCode = PostalCode.constructFromPersisted(this[AddressTbl.postalCode]),
            recipientName = RecipientName.constructFromPersisted(this[AddressTbl.recipientName]),
            recipientPhone = IranianMobileNumber.constructFromPersisted(this[AddressTbl.recipientPhone]),
            isSelfRecipient = this[AddressTbl.isSelfRecipient],
            location = this[AddressTbl.lat]?.let { lat -> GeoPoint.constructFromPersisted(lat, this[AddressTbl.lng]!!) },
            isDefault = this[AddressTbl.isDefault],
            createdOn = this[AddressTbl.createdOn].toInstant(),
            updatedOn = this[AddressTbl.updatedOn].toInstant(),
        )

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
