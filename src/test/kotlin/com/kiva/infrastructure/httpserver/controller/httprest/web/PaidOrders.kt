package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.RequestHash
import com.kiva.application.usecase.address.AddressFields
import com.kiva.application.usecase.address.usecase.command.AddAddressUc
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.cart.usecase.command.ApplyDiscountCodeUc
import com.kiva.application.usecase.checkout.usecase.command.HandleGatewayCallbackUc
import com.kiva.application.usecase.checkout.usecase.command.PlaceOrderUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentUc
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentTbl
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.util.UUID

/**
 * Orders placed and paid the way the shop does it, through [purchase]'s real use cases (the cart, checkout, the
 * pretend gateway's callback), for the panel's order tests. Each bag goes into the cart a second after the one before,
 * so the order's lines are numbered in the order given.
 */
class PaidOrders(private val purchase: PurchaseTestStack, private val clock: MutableClock) {

    /** Where an order goes: Tehran by default, or Isfahan. */
    data class Destination(val provinceId: Int, val cityId: Int, val recipientName: String, val recipientPhone: String) {
        companion object {
            val TEHRAN = Destination(8, 394, "سارا محمدی", "09123456789")
            val ISFAHAN = Destination(4, 122, "رضا کریمی", "09134567890")
        }
    }

    /** An order of [userId] of [variants] (one each), sent to the gateway; its code and payment's public id. */
    fun place(
        userId: UserId,
        vararg variants: Long,
        to: Destination = Destination.TEHRAN,
        shippingMethod: String = "POST",
        reserve: Boolean? = null,
        quantity: Int = 1,
        addressId: Long? = null,
        discountCode: String? = null,
    ): Pair<OrderCode, String> {
        variants.forEach {
            clock.advance(Duration.ofSeconds(1))
            runBlocking { purchase.addCartItem.execute(AddCartItemUc.Cmd(CartCaller.Customer(userId), it, quantity)) }.getOrNull() ?: error("not added")
        }
        if (discountCode != null) {
            runBlocking { purchase.cartUseCases.applyDiscountCode.execute(ApplyDiscountCodeUc.Cmd(CartCaller.Customer(userId), discountCode)) }.getOrNull()
                ?: error("code $discountCode refused")
        }
        val key = UUID.randomUUID().toString()
        val command = PlaceOrderUc.Cmd(
            userId = userId, idempotencyKey = key, requestHash = RequestHash.of(key), addressId = addressId,
            newAddress = fieldsOf(to).takeIf { addressId == null },
            newAddressAsDefault = null, saveNewAddress = false,
            messenger = PlaceOrderUc.MessengerFields("BALE", "09121112233", "@sara_m", "از داخل کیف هم عکس بگیرید", saveAsDefault = false),
            shippingMethod = shippingMethod, reserve = reserve, paymentGateway = "ZARINPAL", customerNote = null, gift = null, expectedPayable = null,
            acceptTerms = true,
        )
        val order = runBlocking { purchase.placeOrder.execute(command) }.getOrNull() ?: error("not placed")
        val paymentId = order.paymentId ?: return order.orderCode to ""
        runBlocking { purchase.startPayment.execute(StartPaymentUc.Cmd(userId, paymentId)) }
        return order.orderCode to paymentId.value
    }

    private fun fieldsOf(to: Destination) =
        AddressFields(null, to.provinceId, to.cityId, "خیابان ولیعصر، کوچه نسترن", "12", "4", "1965843117", to.recipientName, to.recipientPhone, false, null, null)

    /** An address of [userId]'s book, saved now: what a reservation is held for and joined by. */
    fun savedAddress(userId: UserId, to: Destination = Destination.TEHRAN): Long =
        runBlocking { purchase.addAddress.execute(AddAddressUc.Cmd(userId, fieldsOf(to), setAsDefault = null)) }.getOrNull()!!.value

    /** Comes back from the gateway for [paymentId]: paid, or cancelled at the bank. */
    fun back(paymentId: String, paid: Boolean = true) {
        val authority = transaction(purchase.db) { PaymentTbl.select(PaymentTbl.authority).where { PaymentTbl.publicId eq paymentId }.single()[PaymentTbl.authority]!! }
        if (paid) purchase.gateway.pay(authority) else purchase.gateway.cancel(authority)
        runBlocking { purchase.handleCallback.execute(HandleGatewayCallbackUc.Cmd("zarinpal", mapOf("Authority" to authority, "Status" to if (paid) "OK" else "NOK"))) }
    }

    /** An order of [userId] of [variants], placed and paid. */
    fun paid(
        userId: UserId,
        vararg variants: Long,
        to: Destination = Destination.TEHRAN,
        shippingMethod: String = "POST",
        reserve: Boolean? = null,
        addressId: Long? = null,
        quantity: Int = 1,
        discountCode: String? = null,
    ): OrderCode {
        val (code, paymentId) =
            place(userId, *variants, to = to, shippingMethod = shippingMethod, reserve = reserve, quantity = quantity, addressId = addressId, discountCode = discountCode)
        if (paymentId.isNotEmpty()) back(paymentId)
        return code
    }
}
