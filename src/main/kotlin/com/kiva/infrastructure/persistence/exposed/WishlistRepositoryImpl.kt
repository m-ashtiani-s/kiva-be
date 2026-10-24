package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.wishlist.entity.Wishlist
import com.kiva.domain.aggregate.wishlist.entity.WishlistEntry
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.WishlistItemTbl
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class WishlistRepositoryImpl : WishlistRepository {

    /**
     * A transaction-level advisory lock keyed by the customer (lock 13): released by the commit or the rollback, and it
     * works for a list with no row yet.
     */
    override fun lockFor(userId: UserId): Wishlist {
        TransactionManager.current().exec("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", listOf(TextColumnType() to "wishlist:${userId.value}")) { }
        return find(userId)
    }

    override fun find(userId: UserId): Wishlist =
        Wishlist.constructFromPersisted(userId, WishlistItemTbl.selectAll().where { WishlistItemTbl.userId eq userId.value }.map { it.toEntry() })

    /** Compares the list with its rows and writes only the difference. */
    override fun save(wishlist: Wishlist) {
        val user = wishlist.userId.value
        val stored = WishlistItemTbl.selectAll().where { WishlistItemTbl.userId eq user }.associate { it[WishlistItemTbl.productId] to it.toEntry() }
        val wanted = wishlist.entries.associateBy { it.productId.value }

        val removed = stored.keys - wanted.keys
        if (removed.isNotEmpty()) WishlistItemTbl.deleteWhere { (userId eq user) and (productId inList removed) }
        wanted.forEach { (product, entry) ->
            val before = stored[product]
            when {
                before == null -> WishlistItemTbl.insert {
                    it[userId] = user
                    it[productId] = product
                    it[preferredColorKey] = entry.preferredColorKey?.value
                    it[priceAtAdd] = entry.priceAtAdd.toman
                    it[addedOn] = entry.addedOn.atOffset(ZoneOffset.UTC)
                }
                before != entry -> WishlistItemTbl.update({ (WishlistItemTbl.userId eq user) and (WishlistItemTbl.productId eq product) }) {
                    it[preferredColorKey] = entry.preferredColorKey?.value
                }
            }
        }
    }

    override fun visibleIdsOf(userId: UserId): List<ProductId> =
        visible(userId).orderBy(WishlistItemTbl.addedOn to SortOrder.DESC, WishlistItemTbl.productId to SortOrder.ASC)
            .map { ProductId.constructFromPersisted(it[WishlistItemTbl.productId]) }

    override fun countVisible(userId: UserId): Int = visible(userId).count().toInt()

    override fun idsAmong(userId: UserId, productIds: Collection<ProductId>): Set<ProductId> {
        if (productIds.isEmpty()) return emptySet()
        return WishlistItemTbl.select(WishlistItemTbl.productId)
            .where { (WishlistItemTbl.userId eq userId.value) and (WishlistItemTbl.productId inList productIds.map { it.value }) }
            .map { ProductId.constructFromPersisted(it[WishlistItemTbl.productId]) }.toSet()
    }

    override fun deleteAllOf(userId: UserId): Int = WishlistItemTbl.deleteWhere { WishlistItemTbl.userId eq userId.value }

    override fun deleteProduct(productId: ProductId) {
        WishlistItemTbl.deleteWhere { WishlistItemTbl.productId eq productId.value }
    }

    /** The rows of [userId] whose product is in the shop now. */
    private fun visible(userId: UserId) =
        WishlistItemTbl.join(ProductQmTbl, JoinType.INNER, WishlistItemTbl.productId, ProductQmTbl.productId)
            .select(WishlistItemTbl.productId)
            .where { (WishlistItemTbl.userId eq userId.value) and (ProductQmTbl.status eq ProductStatus.ACTIVE) }

    private fun ResultRow.toEntry(): WishlistEntry = WishlistEntry(
        productId = ProductId.constructFromPersisted(this[WishlistItemTbl.productId]),
        preferredColorKey = this[WishlistItemTbl.preferredColorKey]?.let { ColorKey.constructFromPersisted(it) },
        priceAtAdd = Money.constructFromPersisted(this[WishlistItemTbl.priceAtAdd]),
        addedOn = this[WishlistItemTbl.addedOn].toInstant(),
    )
}
