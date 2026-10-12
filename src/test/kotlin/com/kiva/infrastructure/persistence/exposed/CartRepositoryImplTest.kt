package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.entity.Cart
import com.kiva.domain.aggregate.cart.entity.CartLine
import com.kiva.domain.aggregate.cart.model.PurchaseOffer
import com.kiva.domain.aggregate.cart.model.valueobject.CartOwner
import com.kiva.domain.aggregate.cart.model.valueobject.CartQuantity
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.session.model.valueobject.TokenHash
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.cart.RandomCartIds
import com.kiva.infrastructure.persistence.exposed.tbl.CartItemTbl
import com.kiva.infrastructure.persistence.exposed.tbl.CartTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The carts' tables on kiva_test: V14's rules, and the repository writing exactly what changed. A customer's cart goes
 * with the customer (ON DELETE CASCADE); guest carts are deleted after each test.
 */
class CartRepositoryImplTest {

    private val db = TestDatabase.database
    private val repository = CartRepositoryImpl()
    private val ids = RandomCartIds()
    private val now = Instant.parse("2036-06-01T09:00:00Z")

    private val user: UserId = transaction(db) {
        UserRepositoryImpl().findOrRegister(IranianMobileNumber.constructFromPersisted("09909200001"), now).user.id
    }

    @AfterEach
    fun deleteWhatWasStored() {
        transaction(db) {
            CartTbl.deleteWhere { userId.isNull() }
            UserTbl.deleteWhere { phone like "0990920%" }
        }
    }

    private fun line(variantId: Long, quantity: Int, at: Instant = now): CartLine = CartLine.constructFromPersisted(
        ids.nextCartItemId(), VariantId.constructFromPersisted(variantId), CartQuantity.constructFromPersisted(quantity),
        Money.constructFromPersisted(1_450_000), true, at,
    )

    private fun userCart(vararg lines: CartLine): Cart = Cart.constructFromPersisted(
        ids.nextCartPublicId(), CartOwner.User(user), lines.toList(), ShippingMethodCode.TIPAX, reserve = true, createdOn = now, updatedOn = now,
    )

    private fun guestCart(hash: TokenHash = TokenHash(ByteArray(32) { 3 }), vararg lines: CartLine): Cart = Cart.constructFromPersisted(
        ids.nextCartPublicId(), CartOwner.Guest(hash), lines.toList(), ShippingMethodCode.POST, reserve = false, createdOn = now, updatedOn = now,
    )

    private fun Cart.values(): List<Any?> = listOf(publicId, owner, shippingMethod, reserve, createdOn, updatedOn)

    private fun Cart.lineValues(): List<List<Any?>> = lines.map { listOf(it.itemId, it.variantId, it.quantity, it.seenUnitPrice, it.seenPurchasable, it.addedOn) }

    @Test
    fun `a customer's cart reads back with every value and its lines oldest first`() {
        val cart = userCart(line(205, 2, now.plusSeconds(60)), line(301, 1))

        val stored = transaction(db) {
            repository.insertForUserIfAbsent(cart)
            repository.lockForUser(user)
        }

        assertEquals(cart.values(), stored?.values())
        assertEquals(cart.lineValues(), stored?.lineValues())
        assertEquals(listOf(301L, 205L), stored?.lines?.map { it.variantId.value })
    }

    @Test
    fun `a second first cart of the same customer is dropped without an error`() {
        val first = userCart()
        transaction(db) {
            repository.insertForUserIfAbsent(first)
            repository.insertForUserIfAbsent(userCart(line(205, 1)))
        }

        val stored = transaction(db) { repository.lockForUser(user) }

        assertEquals(first.publicId, stored?.publicId)
        assertEquals(emptyList(), stored?.lines)
    }

    @Test
    fun `a guest cart is found by its token's hash only, and only the hash is stored`() {
        val hash = TokenHash(ByteArray(32) { 9 })
        val cart = guestCart(hash)
        transaction(db) { repository.insertGuest(cart) }

        assertEquals(cart.publicId, transaction(db) { repository.lockForGuest(hash) }?.publicId)
        assertNull(transaction(db) { repository.lockForGuest(TokenHash(ByteArray(32) { 8 })) })
        val storedHash = transaction(db) { CartTbl.selectAll().where { CartTbl.publicId eq cart.publicId.value }.single()[CartTbl.guestTokenHash] }
        assertContentEquals(hash.toByteArray(), storedHash)
    }

    @Test
    fun `save writes what changed - a new line, a changed one, a removed one and the cart's own values`() {
        val keep = line(205, 1)
        val drop = line(301, 1)
        transaction(db) { repository.insertForUserIfAbsent(userCart(keep, drop)) }

        transaction(db) {
            val cart = repository.lockForUser(user)!!
            cart.changeQuantity(keep.itemId, CartQuantity.constructFromPersisted(3), offers(205 to 2, 301 to 3), now.plusSeconds(5))
            cart.remove(drop.itemId, now.plusSeconds(5))
            cart.add(ids.nextCartItemId(), VariantId.constructFromPersisted(302), CartQuantity.constructFromPersisted(2), offers(205 to 2, 302 to 3), now.plusSeconds(5))
            cart.chooseShippingMethod(ShippingMethodCode.POST, ShopSettingsRepositoryImpl().get(), now.plusSeconds(5))
            repository.save(cart)
        }

        val stored = transaction(db) { repository.lockForUser(user) }!!
        assertEquals(mapOf(205L to 3, 302L to 2), stored.lines.associate { it.variantId.value to it.quantity.value })
        assertEquals(keep.itemId, stored.lines.first { it.variantId.value == 205L }.itemId)
        assertEquals(ShippingMethodCode.POST, stored.shippingMethod)
        assertEquals(now.plusSeconds(5), stored.updatedOn)
    }

    @Test
    fun `merging a line into another color frees its color before the change is written`() {
        val black = line(201, 1)
        val olive = line(205, 2, now.plusSeconds(1))
        transaction(db) { repository.insertForUserIfAbsent(userCart(black, olive)) }

        transaction(db) {
            val cart = repository.lockForUser(user)!!
            cart.changeColor(black.itemId, VariantId.constructFromPersisted(205), offers(201 to 2, 205 to 2), now)
            repository.save(cart)
        }

        val stored = transaction(db) { repository.lockForUser(user) }!!
        assertEquals(listOf(olive.itemId to 3), stored.lines.map { it.itemId to it.quantity.value })
    }

    @Test
    fun `deleting a guest cart takes its lines with it, and its token reaches nothing any more`() {
        val hash = TokenHash(ByteArray(32) { 4 })
        val cart = guestCart(hash, line(205, 2), line(301, 1))
        transaction(db) { repository.insertGuest(cart) }

        transaction(db) { repository.delete(repository.lockForGuest(hash)!!) }

        assertNull(transaction(db) { repository.lockForGuest(hash) })
        assertNull(transaction(db) { repository.find(cart.publicId) })
        val lineIds = cart.lines.map { it.itemId.value }
        assertEquals(0, transaction(db) { CartItemTbl.selectAll().where { CartItemTbl.publicId inList lineIds }.count() })
    }

    @Test
    fun `a guest's lines move into the customer's cart with their ids, once the guest cart is gone and not before`() {
        val hash = TokenHash(ByteArray(32) { 5 })
        val guestLine = line(205, 2)
        transaction(db) {
            repository.insertForUserIfAbsent(userCart(line(301, 1)))
            repository.insertGuest(guestCart(hash, guestLine))
        }
        fun merge(deleteFirst: Boolean) = transaction(db) {
            val cart = repository.lockForUser(user)!!
            val guest = repository.lockForGuest(hash)!!
            cart.absorb(guest, offers(205 to 2, 301 to 3), ShopSettingsRepositoryImpl().get(), now)
            if (deleteFirst) repository.delete(guest)
            repository.save(cart)
        }

        // A line id is unique in every cart, so the moved line can't be written while the guest's still has it.
        assertFailsWith<ExposedSQLException> { merge(deleteFirst = false) }
        merge(deleteFirst = true)

        val stored = transaction(db) { repository.lockForUser(user) }!!
        assertEquals(mapOf(301L to 1, 205L to 2), stored.lines.associate { it.variantId.value to it.quantity.value })
        assertEquals(guestLine.itemId, stored.lines.single { it.variantId.value == 205L }.itemId)
        assertNull(transaction(db) { repository.lockForGuest(hash) })
    }

    // ============ the database's own walls ============

    private fun insertCartRow(userId: Long?, hash: ByteArray?, publicId: String = ids.nextCartPublicId().value): Long = transaction(db) {
        CartTbl.insert {
            it[CartTbl.publicId] = publicId
            it[CartTbl.userId] = userId
            it[guestTokenHash] = hash
            it[shippingMethod] = ShippingMethodCode.POST
            it[reserve] = false
            it[createdOn] = now.atOffset(ZoneOffset.UTC)
            it[updatedOn] = now.atOffset(ZoneOffset.UTC)
        }[CartTbl.id].value
    }

    private fun insertLineRow(cartId: Long, variantId: Long, quantity: Int) = transaction(db) {
        CartItemTbl.insert {
            it[publicId] = ids.nextCartItemId().value
            it[CartItemTbl.cartId] = cartId
            it[CartItemTbl.variantId] = variantId
            it[CartItemTbl.quantity] = quantity
            it[seenUnitPrice] = 1_450_000
            it[seenPurchasable] = true
            it[addedOn] = now.atOffset(ZoneOffset.UTC)
        }
    }

    @Test
    fun `a cart belongs to a customer or a guest, never both and never neither`() {
        assertFailsWith<ExposedSQLException> { insertCartRow(userId = null, hash = null) }
        assertFailsWith<ExposedSQLException> { insertCartRow(userId = user.value, hash = ByteArray(32)) }
    }

    @Test
    fun `a token hash is 32 bytes, and a cart id has its form`() {
        assertFailsWith<ExposedSQLException> { insertCartRow(userId = null, hash = ByteArray(31)) }
        assertFailsWith<ExposedSQLException> { insertCartRow(userId = null, hash = ByteArray(32) { 1 }, publicId = "cart_123") }
    }

    @Test
    fun `a line holds 1 to 20, and a cart holds each color once`() {
        val cartId = insertCartRow(userId = null, hash = ByteArray(32) { 2 })

        assertFailsWith<ExposedSQLException> { insertLineRow(cartId, 205, 21) }
        assertFailsWith<ExposedSQLException> { insertLineRow(cartId, 205, 0) }
        insertLineRow(cartId, 205, 20)
        assertFailsWith<ExposedSQLException> { insertLineRow(cartId, 205, 1) }
    }

    companion object {
        /** Offers in the shop for these colors (variant to product), 10 of each left, limit 5. */
        private fun offers(vararg colors: Pair<Int, Int>) = colors.associate { (variant, product) ->
            VariantId.constructFromPersisted(variant.toLong()) to PurchaseOffer(
                variantId = VariantId.constructFromPersisted(variant.toLong()),
                productId = ProductId.constructFromPersisted(product.toLong()),
                categoryId = CategoryId.constructFromPersisted(1),
                priceTag = StoredCatalog.priceTag(1_450_000),
                available = 10,
                maxPerOrder = MaxPerOrder.DEFAULT,
                inShop = true,
                reservable = true,
            )
        }
    }
}
