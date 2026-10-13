package com.kiva.domain.aggregate.address.repository

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.entity.AddressBook

interface AddressBookRepository {
    /** The customer's book as it is; empty when they have no address. For reading only: nothing is locked. */
    fun find(userId: UserId): AddressBook

    /**
     * Locks the customer's book until the transaction ends, then reads it. Every change goes through this, so two changes
     * to one book (two tabs adding an address) run one after the other, and the book's rules hold.
     */
    fun lockAndFind(userId: UserId): AddressBook

    /** Writes what changed since [lockAndFind]: new addresses, changed ones and the ones removed. */
    fun save(book: AddressBook)
}
