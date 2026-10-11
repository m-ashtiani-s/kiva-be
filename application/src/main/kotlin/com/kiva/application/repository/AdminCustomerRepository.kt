package com.kiva.application.repository

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.user.entity.User

/**
 * The panel's list of customers (support finds a customer by phone or name). A read of the users' table; the order
 * numbers of a customer join it in the orders phase.
 */
interface AdminCustomerRepository {

    /** The users that match [search], newest account first. */
    fun find(search: CustomerSearch, pageable: Pageable): Page<User>

    fun findById(id: UserId): User?

    /**
     * What to look for. Every one of [words] must be in the phone, the first name or the last name; [phone], when the
     * whole search was one mobile number, must be the phone exactly.
     */
    data class CustomerSearch(val words: List<String> = emptyList(), val phone: IranianMobileNumber? = null) {
        companion object {
            val EVERYONE = CustomerSearch()
        }
    }

    companion object {
        /** The only order: newest account first. */
        const val NEWEST = "newest"
    }
}
