package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.entity.Cart
import com.kiva.domain.aggregate.cart.entity.CartLine
import com.kiva.domain.aggregate.cart.model.valueobject.CartItemId
import com.kiva.domain.aggregate.cart.model.valueobject.CartOwner
import com.kiva.domain.aggregate.cart.model.valueobject.CartPublicId
import com.kiva.domain.aggregate.cart.model.valueobject.CartQuantity
import com.kiva.domain.aggregate.cart.repository.CartRepository
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.session.model.valueobject.TokenHash
import com.kiva.infrastructure.persistence.exposed.tbl.CartItemTbl
import com.kiva.infrastructure.persistence.exposed.tbl.CartTbl
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class CartRepositoryImpl : CartRepository {

    override fun lockForUser(userId: UserId): Cart? = lockAndRead(CartTbl.userId eq userId.value)

    // The token reaches only a guest's cart: a customer's row has no hash, and the CHECK keeps it that way.
    override fun lockForGuest(tokenHash: TokenHash): Cart? =
        lockAndRead((CartTbl.guestTokenHash eq tokenHash.toByteArray()) and CartTbl.userId.isNull())

    /**
     * `INSERT ... ON CONFLICT DO NOTHING` on the customer's unique key: when another request inserted the customer's
     * cart in the meantime, this one waits for it to commit and then inserts nothing. Neither fails.
     */
    override fun insertForUserIfAbsent(cart: Cart) {
        val owner = cart.owner as? CartOwner.User ?: error("Not a customer's cart: ${cart.publicId.value}")
        val inserted = CartTbl.insertIgnore {
            it[userId] = owner.userId.value
            it[guestTokenHash] = null
            it[createdOn] = cart.createdOn.utc()
            it.writeCart(cart)
        }.insertedCount == 1
        if (inserted) insertLines(cartIdOf(cart.publicId), cart.lines)
    }

    override fun insertGuest(cart: Cart) {
        val owner = cart.owner as? CartOwner.Guest ?: error("Not a guest's cart: ${cart.publicId.value}")
        val cartId = CartTbl.insertAndGetId {
            it[userId] = null
            it[guestTokenHash] = owner.tokenHash.toByteArray()
            it[createdOn] = cart.createdOn.utc()
            it.writeCart(cart)
        }.value
        insertLines(cartId, cart.lines)
    }

    override fun find(publicId: CartPublicId): Cart? =
        CartTbl.selectAll().where { CartTbl.publicId eq publicId.value }.singleOrNull()?.let { it.toCart(linesOf(it[CartTbl.id].value)) }

    /**
     * Compares the lines with their rows and writes only the difference. Removed rows go first: a line merged into
     * another color's frees its color, and one cart holds each color once.
     */
    override fun save(cart: Cart) {
        val cartId = cartIdOf(cart.publicId)
        CartTbl.update({ CartTbl.id eq cartId }) { it.writeCart(cart) }

        val stored = linesOf(cartId).associateBy { it.itemId }
        val wanted = cart.lines.associateBy { it.itemId }

        val removed = stored.keys - wanted.keys
        if (removed.isNotEmpty()) CartItemTbl.deleteWhere { publicId inList removed.map { it.value } }

        wanted.values
            .filter { it.itemId in stored && !sameValues(it, stored.getValue(it.itemId)) }
            .forEach { line -> CartItemTbl.update({ CartItemTbl.publicId eq line.itemId.value }) { it.writeLine(line) } }

        insertLines(cartId, wanted.values.filter { it.itemId !in stored })
    }

    private fun lockAndRead(which: Op<Boolean>): Cart? {
        val row = CartTbl.selectAll().where { which }.forUpdate().singleOrNull() ?: return null
        return row.toCart(linesOf(row[CartTbl.id].value))
    }

    private fun cartIdOf(publicId: CartPublicId): Long =
        CartTbl.select(CartTbl.id).where { CartTbl.publicId eq publicId.value }.singleOrNull()?.get(CartTbl.id)?.value
            ?: error("Cart ${publicId.value} is not stored")

    private fun linesOf(cartId: Long): List<CartLine> =
        CartItemTbl.selectAll()
            .where { CartItemTbl.cartId eq cartId }
            .orderBy(CartItemTbl.addedOn to SortOrder.ASC, CartItemTbl.publicId to SortOrder.ASC)
            .map { it.toLine() }

    private fun insertLines(cartId: Long, lines: Collection<CartLine>) {
        lines.forEach { line ->
            CartItemTbl.insert {
                it[publicId] = line.itemId.value
                it[CartItemTbl.cartId] = cartId
                it[addedOn] = line.addedOn.utc()
                it.writeLine(line)
            }
        }
    }

    /** CartLine has identity equality (by id), so a change is found by comparing every value that can change. */
    private fun sameValues(a: CartLine, b: CartLine): Boolean =
        a.variantId == b.variantId && a.quantity == b.quantity && a.seenUnitPrice == b.seenUnitPrice && a.seenPurchasable == b.seenPurchasable

    /** Every column of the cart a change can touch; the owner, the id and the creation time never change. */
    private fun UpdateBuilder<*>.writeCart(cart: Cart) {
        this[CartTbl.publicId] = cart.publicId.value
        this[CartTbl.shippingMethod] = cart.shippingMethod
        this[CartTbl.reserve] = cart.reserve
        this[CartTbl.updatedOn] = cart.updatedOn.utc()
    }

    /** Every column of a line a change can touch; its id, its cart and when it was added never change. */
    private fun UpdateBuilder<*>.writeLine(line: CartLine) {
        this[CartItemTbl.variantId] = line.variantId.value
        this[CartItemTbl.quantity] = line.quantity.value
        this[CartItemTbl.seenUnitPrice] = line.seenUnitPrice.toman
        this[CartItemTbl.seenPurchasable] = line.seenPurchasable
    }

    private fun ResultRow.toCart(lines: List<CartLine>): Cart {
        val owner = this[CartTbl.userId]?.let { CartOwner.User(UserId.constructFromPersisted(it)) }
            ?: CartOwner.Guest(TokenHash(this[CartTbl.guestTokenHash] ?: error("Cart ${this[CartTbl.publicId]} has no owner")))
        return Cart.constructFromPersisted(
            publicId = CartPublicId.constructFromPersisted(this[CartTbl.publicId]),
            owner = owner,
            lines = lines,
            shippingMethod = this[CartTbl.shippingMethod],
            reserve = this[CartTbl.reserve],
            createdOn = this[CartTbl.createdOn].toInstant(),
            updatedOn = this[CartTbl.updatedOn].toInstant(),
        )
    }

    private fun ResultRow.toLine(): CartLine =
        CartLine.constructFromPersisted(
            itemId = CartItemId.constructFromPersisted(this[CartItemTbl.publicId]),
            variantId = VariantId.constructFromPersisted(this[CartItemTbl.variantId]),
            quantity = CartQuantity.constructFromPersisted(this[CartItemTbl.quantity]),
            seenUnitPrice = Money.constructFromPersisted(this[CartItemTbl.seenUnitPrice]),
            seenPurchasable = this[CartItemTbl.seenPurchasable],
            addedOn = this[CartItemTbl.addedOn].toInstant(),
        )

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
