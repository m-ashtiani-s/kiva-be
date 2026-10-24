package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.repository.AdminCustomerRepository
import com.kiva.application.repository.AdminCustomerRepository.CustomerSearch
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.user.entity.User
import com.kiva.infrastructure.common.persistence.SortOrders
import com.kiva.infrastructure.common.persistence.paginate
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Reads user_tbl for the panel's customer list. Runs inside the use case's transaction; it never opens one itself. */
class AdminCustomerRepositoryImpl : AdminCustomerRepository {

    override fun find(search: CustomerSearch, pageable: Pageable): Page<User> =
        UserTbl.selectAll()
            .where { conditionOf(search) }
            .paginate(pageable, SORT_ORDERS, tiebreaker = UserTbl.id) { it.toUser() }

    override fun findById(id: UserId): User? =
        UserTbl.selectAll().where { UserTbl.id eq id.value }.singleOrNull()?.toUser()

    override fun findAll(ids: Collection<UserId>): Map<UserId, User> {
        if (ids.isEmpty()) return emptyMap()
        return UserTbl.selectAll().where { UserTbl.id inList ids.map { it.value } }.map { it.toUser() }.associateBy { it.id }
    }

    /**
     * The phone exactly, or every word somewhere in the phone or a name. A `%` or `_` typed is taken as itself. Names are
     * stored normalized, and the words come normalized the same way, so «علي» finds «علی». Never a deleted account.
     */
    private fun conditionOf(search: CustomerSearch): Op<Boolean> {
        val conditions = buildList<Op<Boolean>> {
            add(UserTbl.notDeleted())
            search.phone?.let { add(UserTbl.phone eq it.value) }
            search.role?.let { add(UserTbl.hasRole(it)) }
            search.words.forEach { word ->
                val pattern = containing(word)
                add((UserTbl.phone like pattern) or (UserTbl.firstName.lowerCase() like pattern) or (UserTbl.lastName.lowerCase() like pattern))
            }
        }
        return conditions.reduce { all, next -> all and next }
    }

    private fun containing(word: String): LikePattern = LikePattern("%", ESCAPE) + LikePattern.ofLiteral(word, ESCAPE) + "%"

    private companion object {
        const val ESCAPE = '\\'

        /** Newest account first; the id breaks ties between accounts made in the same instant. */
        val SORT_ORDERS: SortOrders = mapOf(
            AdminCustomerRepository.NEWEST to listOf(UserTbl.createdOn to SortOrder.DESC, UserTbl.id to SortOrder.DESC),
        )
    }
}
