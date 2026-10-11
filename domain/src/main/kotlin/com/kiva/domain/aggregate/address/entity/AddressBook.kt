package com.kiva.domain.aggregate.address.entity

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.model.AddressInput
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import java.time.Instant

/**
 * All the saved addresses of one customer: the aggregate is the book, not each address, because the rules that matter
 * are between addresses. At most [MAX_ADDRESSES]; exactly one default whenever there is any; and when the default goes,
 * the oldest one left takes its place. With 20 rows at most, reading the whole book is cheap, and one lock on it keeps
 * these rules under concurrent changes.
 *
 * The addresses are kept oldest first (creation time, then id), which is also the order "the oldest one left" means.
 */
class AddressBook private constructor(val userId: UserId, addresses: List<Address>) {

    private val entries: MutableList<Address> = addresses.sortedWith(OLDEST_FIRST).toMutableList()

    // The book's rules, checked on what was stored: only this class writes addresses, so a broken book is a bug.
    init {
        require(entries.size <= MAX_ADDRESSES) { "Address book of user ${userId.value} has ${entries.size} addresses" }
        require(entries.isEmpty() || entries.count { it.isDefault } == 1) { "Address book of user ${userId.value} needs exactly one default" }
    }

    /** Oldest first. */
    val addresses: List<Address> get() = entries.toList()

    fun find(id: AddressId): Address? = entries.firstOrNull { it.id == id }

    /**
     * Adds an address. The first one is always the default; a later one becomes it only with [setAsDefault], and the
     * old default then stops being one.
     */
    fun add(id: AddressId, input: AddressInput, setAsDefault: Boolean, now: Instant): Result<Address, Failure> {
        if (entries.size >= MAX_ADDRESSES) return Result.failure(Failure.LimitReached)
        val isDefault = setAsDefault || entries.isEmpty()
        if (isDefault) clearDefault(now)
        val address = Address.new(id, input, isDefault, now)
        entries += address
        return Result.success(address)
    }

    /**
     * Replaces what the customer filled in. [setAsDefault] true makes it the default; false never takes that away,
     * because there must always be one: the default only moves when another address is made the default.
     */
    fun update(id: AddressId, input: AddressInput, setAsDefault: Boolean, now: Instant): Result<Address, Failure> {
        val index = indexOf(id) ?: return Result.failure(Failure.AddressNotFound)
        val becomesDefault = setAsDefault && !entries[index].isDefault
        if (becomesDefault) clearDefault(now)
        val updated = entries[index].with(input, isDefault = entries[index].isDefault || becomesDefault, now)
        entries[index] = updated
        return Result.success(updated)
    }

    /** Deletes an address for good (it is personal data). When it was the default, the oldest one left takes over. */
    fun remove(id: AddressId, now: Instant): Result<Unit, Failure> {
        val index = indexOf(id) ?: return Result.failure(Failure.AddressNotFound)
        val removed = entries.removeAt(index)
        if (removed.isDefault && entries.isNotEmpty()) entries[0] = entries[0].withDefault(true, now)
        return Result.success(Unit)
    }

    /** Makes an address the default; the old default stops being one. Already the default: nothing changes. */
    fun makeDefault(id: AddressId, now: Instant): Result<Unit, Failure> {
        val index = indexOf(id) ?: return Result.failure(Failure.AddressNotFound)
        if (entries[index].isDefault) return Result.success(Unit)
        clearDefault(now)
        entries[index] = entries[index].withDefault(true, now)
        return Result.success(Unit)
    }

    private fun indexOf(id: AddressId): Int? = entries.indexOfFirst { it.id == id }.takeIf { it >= 0 }

    private fun clearDefault(now: Instant) {
        val index = entries.indexOfFirst { it.isDefault }
        if (index >= 0) entries[index] = entries[index].withDefault(false, now)
    }

    override fun toString(): String = "AddressBook(userId=${userId.value}, addresses=${entries.size})"

    companion object {
        /** The contract's limit (ADDRESS_LIMIT_REACHED). */
        const val MAX_ADDRESSES = 20

        private val OLDEST_FIRST = compareBy<Address>({ it.createdOn }, { it.id.value })

        fun empty(userId: UserId): AddressBook = AddressBook(userId, emptyList())

        fun constructFromPersisted(userId: UserId, addresses: List<Address>): AddressBook = AddressBook(userId, addresses)
    }

    sealed class Failure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        /** Not in this customer's book, whether it belongs to someone else or to nobody: the same 404 either way. */
        data object AddressNotFound : Failure("ADDRESS_NOT_FOUND", FailureKind.NOT_FOUND)

        data object LimitReached : Failure("ADDRESS_LIMIT_REACHED", FailureKind.BUSINESS_RULE, mapOf("maxAddresses" to MAX_ADDRESSES))
    }
}
