package com.kiva.application.repository

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.user.entity.User
import com.kiva.domain.aggregate.user.model.valueobject.Role

/**
 * The panel's list of customers (support finds a customer by phone or name). A read of the users' table; the order
 * numbers of a customer join it in the orders phase.
 */
interface AdminCustomerRepository {

    /** The users that match [search], newest account first; a deleted account never (roadmap phase 14, decision 12). */
    fun find(search: CustomerSearch, pageable: Pageable): Page<User>

    /** The account, a deleted one too ([User.isDeleted]): an order of it still names it, as «حساب حذف‌شده». */
    fun findById(id: UserId): User?

    /**
     * The accounts of [ids] there are, deleted ones too, in one read (the panel's order queue names a whole page at
     * once).
     */
    fun findAll(ids: Collection<UserId>): Map<UserId, User>

    /**
     * What to look for. Every one of [words] must be in the phone, the first name or the last name; [phone], when the
     * whole search was one mobile number, must be the phone exactly; [role], when given, must be one of the user's.
     */
    data class CustomerSearch(val words: List<String> = emptyList(), val phone: IranianMobileNumber? = null, val role: Role? = null) {
        companion object {
            val EVERYONE = CustomerSearch()
        }
    }

    companion object {
        /** The only order: newest account first. */
        const val NEWEST = "newest"
    }
}
