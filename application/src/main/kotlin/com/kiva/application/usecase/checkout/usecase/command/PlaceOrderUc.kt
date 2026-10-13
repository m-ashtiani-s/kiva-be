package com.kiva.application.usecase.checkout.usecase.command

import com.kiva.application.dto.PlacedOrder
import com.kiva.application.port.IdempotencyScope
import com.kiva.application.port.IdempotentRequest
import com.kiva.application.port.PaymentGateways
import com.kiva.application.port.RequestHash
import com.kiva.application.port.SmsSender
import com.kiva.application.repository.OfferQm
import com.kiva.application.repository.OfferQmRepository
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.usecase.address.AddressFields
import com.kiva.application.usecase.idempotency.IdempotencyGuard
import com.kiva.application.usecase.reservation.Reservations
import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.bind
import com.kiva.common.railway.forField
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Quantity
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.AddressBookDomainService
import com.kiva.domain.aggregate.address.entity.AddressBook
import com.kiva.domain.aggregate.address.model.AddressInput
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.domain.aggregate.address.repository.AddressBookRepository
import com.kiva.domain.aggregate.cart.entity.CartLine
import com.kiva.domain.aggregate.cart.repository.CartRepository
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.geo.repository.GeoRepository
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.order.OrderDomainService
import com.kiva.domain.aggregate.order.model.OrderPlacement
import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderItemSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.PreShipmentMessenger
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.payment.entity.Payment
import com.kiva.domain.aggregate.payment.repository.PaymentIdGenerator
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * Placing an order (`POST /orders`, roadmap step 13): the customer's cart becomes an order whose stock is held until
 * its time to pay ends, the cart is emptied, and an attempt to pay is made ready. Opening the gateway's session is
 * StartPaymentUc's, which the route calls after this commits: a gateway is never called inside a transaction.
 *
 * 1. The shape of the request, every problem at once (400), before any transaction: the `Idempotency-Key`, exactly one
 *    of `addressId` and `newAddress` (its problems under `newAddress.`), the values of the lists, the texts, and
 *    `expectedPayable`.
 * 2. Then one transaction, refusing in the contract's order, and saving nothing when it refuses: the key (a repeat
 *    answers what the first request made; another request with it is IDEMPOTENCY_KEY_REUSED), the cart (CART_EMPTY),
 *    the address (ADDRESS_NOT_FOUND; a new address's province and city), the messenger (MESSENGER_REQUIRED,
 *    MESSENGER_PHONE_INVALID), and OrderDomainService's rules from the shipping method to the price. Then the order
 *    gets its code, its stock is held, the order and its attempt to pay are saved, a new address is kept in the book
 *    (when there is room) and the messenger as the customer's default (when asked), the cart is emptied, and the key
 *    is recorded.
 * 3. When someone else changed the same stock at the same moment (ConcurrentChangeException), the whole transaction
 *    runs again from a fresh read, up to [MAX_ATTEMPTS] times in all: the rules are checked again on what is left, so
 *    the last bag goes to one customer and the next is told OUT_OF_STOCK.
 * 4. After the commit, an order with nothing to pay is paid already: «پرداخت موفق» is sent.
 *
 * Locks, only in section 11's order, so two orders never wait on each other in a circle: the key, the cart, the address
 * book (only to keep a new address), the products and colors of the read model (InventoryRepository, in lock order),
 * the order's stream.
 */
class PlaceOrderUc(
    private val transactionManager: TransactionManager,
    private val idempotencyGuard: IdempotencyGuard,
    private val cartRepository: CartRepository,
    private val offerQmRepository: OfferQmRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val addressBookRepository: AddressBookRepository,
    private val addressBookDomainService: AddressBookDomainService,
    private val geoRepository: GeoRepository,
    private val userRepository: UserRepository,
    private val orderDomainService: OrderDomainService,
    private val orderRepository: OrderRepository,
    private val orderQmRepository: OrderQmRepository,
    private val inventoryDomainService: InventoryDomainService,
    private val inventoryRepository: InventoryRepository,
    private val paymentRepository: PaymentRepository,
    private val paymentIdGenerator: PaymentIdGenerator,
    private val paymentGateways: PaymentGateways,
    private val smsSender: SmsSender,
    private val reservations: Reservations,
    private val clock: Clock,
) : CommandUseCase<PlaceOrderUc.Cmd, PlacedOrder, PlaceOrderUc.Failure> {

    private val logger = getLogger()

    /**
     * The contract's PlaceOrderRequest as it arrived, raw, and who asks.
     *
     * @property userId from the access token, never from the request.
     * @property idempotencyKey the `Idempotency-Key` header as it arrived; [requestHash] the request's fingerprint.
     * @property newAddressAsDefault the new address's `setAsDefault`, for when it is kept in the book.
     * @property acceptTerms not sent counts as not accepted.
     */
    data class Cmd(
        val userId: UserId,
        val idempotencyKey: String?,
        val requestHash: RequestHash,
        val addressId: Long?,
        val newAddress: AddressFields?,
        val newAddressAsDefault: Boolean?,
        val saveNewAddress: Boolean,
        val messenger: MessengerFields?,
        val shippingMethod: String?,
        val reserve: Boolean?,
        val paymentGateway: String?,
        val customerNote: String?,
        val gift: GiftFields?,
        val expectedPayable: Long?,
        val acceptTerms: Boolean,
    ) {
        // Addresses, numbers and notes are personal data: never in a log.
        override fun toString(): String =
            "Cmd(userId=${userId.value}, addressId=$addressId, newAddress=${newAddress != null}, saveNewAddress=$saveNewAddress, " +
                "messenger=${messenger?.channel}, shippingMethod=$shippingMethod, reserve=$reserve, paymentGateway=$paymentGateway, " +
                "expectedPayable=$expectedPayable, acceptTerms=$acceptTerms)"
    }

    /** The contract's `preShipmentMessenger`, raw. Personal data: [toString] shows the channel only. */
    data class MessengerFields(val channel: String?, val phone: String?, val username: String?, val note: String?, val saveAsDefault: Boolean) {
        override fun toString(): String = "MessengerFields(channel=$channel)"
    }

    /** The contract's `gift`, raw, without `wrap`: the shop offers no wrap yet, so it is ignored. */
    data class GiftFields(val message: String?, val hidePrices: Boolean) {
        override fun toString(): String = "GiftFields(hidePrices=$hidePrices)"
    }

    /** Where the order goes, as the request chose it. */
    private sealed interface AddressChoice {
        data class Saved(val id: AddressId) : AddressChoice

        data class New(val input: AddressInput, val setAsDefault: Boolean, val keep: Boolean) : AddressChoice
    }

    /** The request with its shape checked. The messenger's channel and number are checked by the rules, in their turn. */
    private class Input(
        val request: IdempotentRequest,
        val address: AddressChoice,
        val messenger: MessengerFields?,
        val channel: MessengerChannel?,
        val shippingMethod: ShippingMethodCode,
        val gateway: PaymentGatewayCode,
        val reserve: Boolean?,
        val customerNote: CustomerNote?,
        val gift: GiftNote?,
        val expectedPayable: Money?,
        val acceptTerms: Boolean,
    )

    /** The order's address, and the book to save when a new address is kept in it. */
    private class Destination(val snapshot: AddressSnapshot, val bookToSave: AddressBook?)

    /** What the transaction made, and the number to tell «پرداخت موفق» when the order was paid at once. */
    private class Outcome(val placed: PlacedOrder, val paidAtOnceTo: IranianMobileNumber?)

    override suspend fun execute(command: Cmd): Result<PlacedOrder, Failure> {
        logger.info("PlaceOrderUc.execute $command")
        val input = checked(command).unwrapOr { return Result.failure(it) }

        val outcome = placeWithRetries(input).unwrapOr { return Result.failure(it) }
        outcome.paidAtOnceTo?.let { sendPaid(it, outcome.placed.orderCode) }
        return Result.success(outcome.placed)
    }

    // ============ 1. the shape ============

    private fun checked(command: Cmd): Result<Input, Failure> {
        val problems = mutableListOf<ResultFailure>()
        fun <T> Result<T, ResultFailure>.orProblem(): T? = when (this) {
            is Result.Success -> value
            is Result.Failure -> null.also { problems += failure }
        }

        val request = IdempotentRequest.makeNew(command.userId, IdempotencyScope.PLACE_ORDER, command.idempotencyKey, command.requestHash).orProblem()
        val address = when {
            command.addressId == null && command.newAddress == null -> null.also { problems += InputProblem.AddressRequired }
            command.addressId != null && command.newAddress != null -> null.also { problems += InputProblem.AddressAmbiguous }
            command.addressId != null -> AddressId.makeNew(command.addressId).forField("addressId").orProblem()?.let(AddressChoice::Saved)
            else -> command.newAddress?.toInput()?.mapOnFailure { FieldFailure("newAddress", it) }?.orProblem()
                ?.let { AddressChoice.New(it, command.newAddressAsDefault ?: false, command.saveNewAddress) }
        }
        val channel = command.messenger?.channel?.let { MessengerChannel.makeNew(it).forField("preShipmentMessenger.channel").orProblem() }
        command.messenger?.let { PreShipmentMessenger.checkTexts(it.username, it.note).mapOnFailure { texts -> FieldFailure("preShipmentMessenger", texts) }.orProblem() }
        val shippingMethod = when (val raw = command.shippingMethod) {
            null -> null.also { problems += InputProblem.ShippingMethodRequired }
            else -> ShippingMethodCode.makeNew(raw).forField("shippingMethod").orProblem()
        }
        val gateway = when (val raw = command.paymentGateway) {
            null -> null.also { problems += InputProblem.PaymentGatewayRequired }
            else -> PaymentGatewayCode.makeNew(raw).forField("paymentGateway").orProblem()
        }
        val customerNote = CustomerNote.makeNew(command.customerNote).orProblem()
        val gift = command.gift?.let { GiftNote.makeNew(it.message, it.hidePrices).forField("gift.message").orProblem() }
        val expectedPayable = command.expectedPayable?.let { Money.makeNew(it).forField("expectedPayable").orProblem() }

        if (problems.isNotEmpty()) return Result.failure(Failure.InvalidInput(problems))
        return Result.success(
            Input(
                request = checkNotNull(request),
                address = checkNotNull(address),
                messenger = command.messenger,
                channel = channel,
                shippingMethod = checkNotNull(shippingMethod),
                gateway = checkNotNull(gateway),
                reserve = command.reserve,
                customerNote = customerNote,
                gift = gift,
                expectedPayable = expectedPayable,
                acceptTerms = command.acceptTerms,
            ),
        )
    }

    // ============ 2 and 3. the transaction, again when the stock changed meanwhile ============

    private suspend fun placeWithRetries(input: Input): Result<Outcome, Failure> {
        repeat(MAX_ATTEMPTS - 1) { attempt ->
            try {
                return placeOnce(input)
            } catch (e: ConcurrentChangeException) {
                logger.info("PlaceOrderUc: changed meanwhile (${e.message}), attempt ${attempt + 1} of $MAX_ATTEMPTS refused; trying again")
            }
        }
        // The last attempt: if even this one is refused, the exception goes up to the caller.
        return placeOnce(input)
    }

    private suspend fun placeOnce(input: Input): Result<Outcome, Failure> = transactionManager.inTransaction {
        idempotencyGuard.once(input.request, outcomeOf = { outcome: Outcome -> outcome.placed.outcome() }) { place(input) }
            .bind { run ->
                when (run) {
                    is IdempotencyGuard.Run.Done -> Result.success(run.value)
                    // Answered from what the first request made; it was told «پرداخت موفق» then, if it had to be.
                    is IdempotencyGuard.Run.Replayed -> Result.success(Outcome(PlacedOrder.fromOutcome(run.outcome), paidAtOnceTo = null))
                    IdempotencyGuard.Run.KeyReused -> Result.failure(Failure.KeyReused)
                }
            }
    }

    private fun place(input: Input): Result<Outcome, Failure> {
        val userId = input.request.userId
        val now = clock.instant()
        val settings = shopSettingsRepository.get()

        val cart = cartRepository.lockForUser(userId)?.takeIf { it.lines.isNotEmpty() } ?: return Result.failure(Failure.CartEmpty)
        val destination = destinationOf(input.address, userId, now).unwrapOr { return Result.failure(it) }
        val user = userRepository.findById(userId) ?: return Result.failure(Failure.AccountGone)
        val messenger = messengerOf(input).unwrapOr { return Result.failure(it) }

        val offers = offerQmRepository.findOffers(cart.lines.map { it.variantId })
        val placement = OrderPlacement(
            userId = userId,
            lines = cart.lines.map { lineOf(it, offers) },
            address = destination.snapshot,
            messenger = messenger,
            shippingMethod = input.shippingMethod,
            gatewayAvailable = paymentGateways.isAvailable(input.gateway, settings),
            acceptTerms = input.acceptTerms,
            reserve = input.reserve ?: cart.reserve,
            customerNote = input.customerNote,
            gift = input.gift,
            expectedPayable = input.expectedPayable,
            unpaidOrdersHoldingUntil = orderQmRepository.unpaidHoldingUntil(userId, now),
            settings = settings,
            // Read, not locked: a reservation started by a payment at this very moment only means this order ships alone.
            holdingGroup = reservations.holdingOf(userId),
        )
        val order = orderDomainService.placeOrder(placement, now).unwrapOr { return Result.failure(Failure.Refused(it)) }

        // Read again from the stock's own stream: another order may have taken the last bags since the read model was read.
        val held = inventoryDomainService.reserveForOrder(order.code, order.lines, userId, now).unwrapOr { failure ->
            val short = when (failure) {
                is InventoryDomainService.ReserveForOrderFailure.OutOfStock -> failure.variantIds
            }
            val itemIds = cart.lines.filter { it.variantId in short }.map { it.itemId }
            return Result.failure(Failure.Refused(OrderDomainService.PlaceOrderFailure.OutOfStock(itemIds)))
        }
        held.forEach(inventoryRepository::save)
        orderRepository.save(order)
        val payment = if (order.totals.payable > Money.ZERO) {
            Payment.start(paymentIdGenerator.nextPaymentPublicId(), order.id, userId, input.gateway, order.totals.payable, now).also(paymentRepository::add)
        } else {
            null
        }

        destination.bookToSave?.let(addressBookRepository::save)
        if (input.messenger?.saveAsDefault == true && (user.defaultMessenger != messenger.channel || user.defaultMessengerPhone != messenger.phone)) {
            userRepository.save(user.keepMessenger(messenger.channel, messenger.phone))
        }
        cart.clear(now)
        cartRepository.save(cart)

        logger.info(
            "PlaceOrderUc: order ${order.code.value} of user ${userId.value} placed with ${order.lines.size} lines, payable ${order.totals.payable.toman}, " +
                "payment ${payment?.publicId?.value ?: "none, paid at once"}",
        )
        return Result.success(Outcome(PlacedOrder(order.code, payment?.publicId), paidAtOnceTo = if (payment == null) user.phone else null))
    }

    /**
     * The order's address: a saved one of the customer's book, or a new one, whose province and city must exist and go
     * together. A new address asked to be kept is added to the book now, so the order keeps its id, and the book is
     * saved once every rule has passed; a full book keeps nothing and the order goes on (contract).
     */
    private fun destinationOf(choice: AddressChoice, userId: UserId, now: Instant): Result<Destination, Failure> = when (choice) {
        is AddressChoice.Saved -> {
            val address = addressBookRepository.find(userId).find(choice.id)
            if (address == null) {
                Result.failure(Failure.AddressNotFound)
            } else {
                // The address table's foreign key to the city list keeps both there.
                val province = geoRepository.findProvince(address.provinceId) ?: error("Address ${address.id.value} names a province that isn't there")
                val city = geoRepository.findCity(address.cityId) ?: error("Address ${address.id.value} names a city that isn't there")
                Result.success(Destination(AddressSnapshot.of(address, province.name, city.name), bookToSave = null))
            }
        }
        is AddressChoice.New -> newDestinationOf(choice, userId, now)
    }

    private fun newDestinationOf(choice: AddressChoice.New, userId: UserId, now: Instant): Result<Destination, Failure> {
        val place = addressBookDomainService.placeOf(choice.input).unwrapOr { return Result.failure(Failure.of(it)) }
        val unsaved = Destination(AddressSnapshot.of(choice.input, place.province.name, place.city.name, savedAs = null), bookToSave = null)
        if (!choice.keep) return Result.success(unsaved)

        val book = addressBookRepository.lockAndFind(userId)
        return when (val added = addressBookDomainService.addAddress(book, choice.input, choice.setAsDefault, now)) {
            is Result.Success -> Result.success(Destination(AddressSnapshot.of(choice.input, place.province.name, place.city.name, savedAs = added.value.id), book))
            is Result.Failure -> {
                // The place was checked just above, so only a full book gets here.
                check(added.failure == AddressBookDomainService.Failure.Book(AddressBook.Failure.LimitReached)) { "A new address was refused: ${added.failure.code}" }
                logger.info("PlaceOrderUc: the address book of user ${userId.value} is full; the order's new address is not kept")
                Result.success(unsaved)
            }
        }
    }

    /** Required by the rules, not by the shape: no channel is MESSENGER_REQUIRED, a missing or wrong number MESSENGER_PHONE_INVALID. */
    private fun messengerOf(input: Input): Result<PreShipmentMessenger, Failure> {
        val fields = input.messenger
        val channel = input.channel
        if (fields == null || channel == null) return Result.failure(Failure.MessengerRequired)
        val phone = fields.phone?.let { IranianMobileNumber.makeNew(it).getOrNull() } ?: return Result.failure(Failure.MessengerPhoneInvalid)
        // The texts were checked with the shape.
        return Result.success(PreShipmentMessenger.makeNew(channel, phone, fields.username, fields.note).getOrNull() ?: error("Checked messenger texts were refused"))
    }

    /** A cart line as the order takes it: how many, the offer of its color now, and what the catalog shows of it. */
    private fun lineOf(line: CartLine, offers: Map<VariantId, OfferQm>): OrderPlacement.Line {
        // The read model keeps every color ever made, so a cart line's color is always there.
        val qm = offers[line.variantId] ?: error("No offer for variant ${line.variantId.value} of cart line ${line.itemId.value}")
        return OrderPlacement.Line(
            itemId = line.itemId,
            quantity = Quantity.constructFromPersisted(line.quantity.value),
            offer = qm.offer,
            item = OrderItemSnapshot(
                name = ProductName.constructFromPersisted(qm.product.name),
                slug = Slug.constructFromPersisted(qm.product.slug),
                sku = Sku.constructFromPersisted(qm.color.sku),
                color = OrderItemSnapshot.Color(ColorKey.constructFromPersisted(qm.color.key), qm.color.name, qm.color.hex),
                // No uploaded photos yet, as on the cart's lines.
                imageUrl = null,
            ),
        )
    }

    // ============ 4. after the commit ============

    /** «پرداخت موفق». A failed SMS is only logged: the order is paid all the same. */
    private suspend fun sendPaid(to: IranianMobileNumber, orderCode: OrderCode) {
        try {
            smsSender.sendOrderPaid(to, orderCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("PlaceOrderUc: the «پرداخت موفق» SMS of order ${orderCode.value} to ${to.masked()} failed; the order is paid all the same", e)
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }

    /** The request's shape, field by field (400 VALIDATION_ERROR), besides what the value objects say themselves. */
    sealed class InputProblem(code: String, field: String) : ResultFailure(code, FailureKind.INVALID, field = field) {
        /** Neither `addressId` nor `newAddress`. */
        data object AddressRequired : InputProblem("ADDRESS_REQUIRED", "addressId")

        /** Both `addressId` and `newAddress`. */
        data object AddressAmbiguous : InputProblem("ADDRESS_AMBIGUOUS", "addressId")

        data object ShippingMethodRequired : InputProblem("SHIPPING_METHOD_REQUIRED", "shippingMethod")

        data object PaymentGatewayRequired : InputProblem("PAYMENT_GATEWAY_REQUIRED", "paymentGateway")
    }

    sealed class Failure(
        code: String,
        kind: FailureKind,
        causes: List<ResultFailure> = emptyList(),
        meta: Map<String, Any> = emptyMap(),
    ) : ResultFailure(code, kind, causes = causes, meta = meta) {
        /** One or more fields are wrong; each cause names its field (400 VALIDATION_ERROR). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        /** The key was used before for another request (409). */
        data object KeyReused : Failure("IDEMPOTENCY_KEY_REUSED", FailureKind.CONFLICT)

        /** A token of an account that no longer exists. */
        data object AccountGone : Failure("UNAUTHORIZED", FailureKind.UNAUTHORIZED)

        data object CartEmpty : Failure("CART_EMPTY", FailureKind.BUSINESS_RULE)

        /** The `addressId` is not one of the customer's addresses (contract: 422 here, unlike checkout's 400). */
        data object AddressNotFound : Failure("ADDRESS_NOT_FOUND", FailureKind.BUSINESS_RULE)

        /** The new address's city is in another province. */
        data object CityNotInProvince : Failure("CITY_NOT_IN_PROVINCE", FailureKind.BUSINESS_RULE)

        data object MessengerRequired : Failure("MESSENGER_REQUIRED", FailureKind.BUSINESS_RULE)

        data object MessengerPhoneInvalid : Failure("MESSENGER_PHONE_INVALID", FailureKind.BUSINESS_RULE)

        /** One of the order's own rules, from the shipping method to the price, or too few left when the stock was held. */
        data class Refused(val failure: OrderDomainService.PlaceOrderFailure) : Failure(failure.code, failure.kind, meta = failure.meta)

        companion object {
            /** A new address whose province or city doesn't exist is a wrong field under `newAddress.`; a city of another province is 422. */
            fun of(failure: AddressBookDomainService.Failure): Failure = when (failure) {
                AddressBookDomainService.Failure.ProvinceNotFound, AddressBookDomainService.Failure.CityNotFound ->
                    InvalidInput(listOf(FieldFailure("newAddress", InputProblems(listOf(failure)))))
                AddressBookDomainService.Failure.CityNotInProvince -> CityNotInProvince
                is AddressBookDomainService.Failure.Book -> error("Looking up a place is no book's rule: ${failure.code}")
            }
        }
    }
}
