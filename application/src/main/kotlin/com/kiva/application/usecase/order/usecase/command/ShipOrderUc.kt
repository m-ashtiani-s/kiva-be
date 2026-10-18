package com.kiva.application.usecase.order.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.order.OrderByCode
import com.kiva.application.usecase.order.OrderNotices
import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.mapOnSuccess
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupStatus
import com.kiva.domain.aggregate.reservation.repository.ReservationGroupRepository
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The parcel left with the courier (`PUT /admin/orders/{orderCode}/shipment`; ADMIN, OPERATOR): SHIPPED under the
 * tracking code, its stock leaves the shelf (on hand and reserved both go down), and «ارسال شد» goes to the customer
 * after the commit with the code and the carrier's name.
 *
 * An order of a reservation group ships with **all** the group's orders not shipped yet that still stand, under the one
 * code: the group must be let go already (422 INVALID_STATUS_TRANSITION while it holds), and if any of them isn't ready
 * (photos not approved yet, a status that can't ship) nothing ships: 422 GROUP_NOT_READY with those orders' codes. A
 * cancelled order of the group is left out. One SMS for the parcel.
 *
 * On a SHIPPED order the same request corrects the parcel (the code, when it was handed over, whether it is listed
 * daily), for every order of its group shipped under the old code: no stock, no SMS. On DELIVERED it is 422.
 *
 * Locks in section 4.11's order: the customer's reservations (4), each order's row by orderId (9), the stock of all the
 * parcel's colors in one order (7), each order's stream by orderId (8). One ORDER_SHIPPED row per order in the change
 * log. A change of an order at the same moment makes the whole transaction run again, up to [MAX_ATTEMPTS] times.
 */
class ShipOrderUc(
    private val transactionManager: TransactionManager,
    private val orderByCode: OrderByCode,
    private val orderQmRepository: OrderQmRepository,
    private val orderRepository: OrderRepository,
    private val reservationGroupRepository: ReservationGroupRepository,
    private val inventoryDomainService: InventoryDomainService,
    private val inventoryRepository: InventoryRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val userRepository: UserRepository,
    private val auditTrail: AuditTrail,
    private val orderNotices: OrderNotices,
    private val clock: Clock,
) : CommandUseCase<ShipOrderUc.Cmd, Unit, ShipOrderUc.Failure> {

    private val logger = getLogger()

    /** The body as it came: every field nullable. */
    data class Cmd(
        val orderCode: String,
        val carrier: String?,
        val trackingCode: String?,
        val shippedAt: String?,
        val showInDailyList: Boolean?,
        val actor: PanelActor,
    ) {
        override fun toString(): String = "Cmd(orderCode=$orderCode, carrier=$carrier, shippedAt=$shippedAt, showInDailyList=$showInDailyList, actor=${actor.userId.value})"
    }

    private data class Input(val carrier: ShippingMethodCode, val trackingCode: TrackingCode, val shippedOn: Instant?, val showInDailyList: Boolean)

    /** What to tell the customer after the commit; null for a correction. */
    private data class Shipped(val code: OrderCode, val to: IranianMobileNumber?, val carrierName: String, val trackingCode: TrackingCode)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        val code = orderByCode.codeOf(command.orderCode)
        logger.info("ShipOrderUc.execute orderCode=${code?.value ?: "(not a code)"} carrier=${command.carrier} actor=${command.actor.userId.value}")
        val input = inputOf(command)
        if (input is Result.Failure) return Result.failure(input.failure)
        if (code == null) return Result.failure(Failure.OrderNotFound)
        val checked = (input as Result.Success).value

        repeat(MAX_ATTEMPTS - 1) { attempt ->
            try {
                return shipOnce(command, checked)
            } catch (e: ConcurrentChangeException) {
                logger.info("ShipOrderUc: order ${code.value} changed meanwhile, attempt ${attempt + 1} of $MAX_ATTEMPTS refused; trying again")
            }
        }
        return shipOnce(command, checked)
    }

    private suspend fun shipOnce(command: Cmd, input: Input): Result<Unit, Failure> {
        val done = transactionManager.inTransaction {
            val read = orderByCode.findForPanel(command.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
            val now = clock.instant()
            val shippedOn = input.shippedOn ?: now
            if (read.status == OrderStatus.SHIPPED) {
                correct(read, input, shippedOn, command.actor, now).mapOnSuccess { null }
            } else {
                ship(read, input, shippedOn, command.actor, now)
            }
        }
        return when (done) {
            is Result.Success -> {
                done.value?.let { orderNotices.shipped(it.to, it.code, it.carrierName, it.trackingCode.value) }
                Result.success(Unit)
            }
            is Result.Failure -> Result.failure(done.failure)
        }
    }

    private fun ship(read: OrderQm, input: Input, shippedOn: Instant, actor: PanelActor, now: Instant): Result<Shipped?, Failure> {
        val groupId = read.reservationGroupId
        val members = if (groupId == null || read.shipment != null || !read.status.countsAsPurchase) {
            listOf(read)
        } else {
            reservationGroupRepository.lockFor(read.userId)
            val group = reservationGroupRepository.find(groupId) ?: error("Order ${read.code.value} is in group ${groupId.value}, which is gone")
            if (group.status == ReservationGroupStatus.HOLDING) return Result.failure(Failure.GroupHolding(read.status))
            orderQmRepository.findOfGroup(groupId).filter { it.status.countsAsPurchase && it.shipment == null }
        }.sortedBy { it.orderId.value }

        members.forEach { orderQmRepository.lockOrder(it.orderId) ?: error("Order ${it.orderId.value} is not in the read model") }
        val orders = members.map { orderRepository.get(it.orderId) ?: error("Order ${it.orderId.value} of the read model has no events") }
        val refused = orders.mapNotNull { order ->
            val shipped = order.ship(input.carrier, input.trackingCode, shippedOn, input.showInDailyList, actor.userId, now, groupId)
            (shipped as? Result.Failure)?.let { order to it.failure }
        }
        refused.firstOrNull { it.second.kind == FailureKind.INVALID }?.let { return Result.failure(Failure.of(it.second)) }
        if (refused.isNotEmpty()) {
            return if (orders.size == 1) Result.failure(Failure.of(refused.single().second)) else Result.failure(Failure.GroupNotReady(refused.map { it.first.code }))
        }

        inventoryDomainService.shipForOrders(orders.associate { it.code to it.lines }, actor.userId, now).forEach(inventoryRepository::save)
        orders.forEach { order ->
            orderRepository.save(order)
            auditTrail.record(
                AuditEntry(
                    now, actor, AuditAction.ORDER_SHIPPED, AuditEntityType.ORDER, order.code.value, order.code.value,
                    AuditDiff.build {
                        field("carrier", null, input.carrier.name)
                        field("trackingCode", null, input.trackingCode.value)
                    },
                ),
            )
        }
        val carrierName = shopSettingsRepository.get().shippingMethods.firstOrNull { it.code == input.carrier }?.name ?: input.carrier.name
        logger.info("ShipOrderUc: shipped ${orders.map { it.code.value }} with ${input.carrier}")
        return Result.success(Shipped(read.code, userRepository.findById(read.userId)?.phone, carrierName, input.trackingCode))
    }

    private fun correct(read: OrderQm, input: Input, shippedOn: Instant, actor: PanelActor, now: Instant): Result<Unit, Failure> {
        if (input.carrier != read.shippingMethod) return Result.failure(Failure.of(Order.ShipFailure.CarrierMismatch(read.shippingMethod)))
        val old = checkNotNull(read.shipment) { "Order ${read.code.value} is SHIPPED without its parcel" }
        val groupId = read.reservationGroupId
        val members = if (groupId == null) {
            listOf(read)
        } else {
            orderQmRepository.findOfGroup(groupId).filter { it.status == OrderStatus.SHIPPED && it.shipment?.trackingCode == old.trackingCode }
        }.sortedBy { it.orderId.value }

        members.forEach { orderQmRepository.lockOrder(it.orderId) ?: error("Order ${it.orderId.value} is not in the read model") }
        val orders = members.map { orderRepository.get(it.orderId) ?: error("Order ${it.orderId.value} of the read model has no events") }
        orders.forEach { order ->
            when (val corrected = order.correctShipment(input.trackingCode, shippedOn, input.showInDailyList, actor.userId, now)) {
                is Result.Success -> {}
                is Result.Failure -> return Result.failure(Failure.of(corrected.failure))
            }
        }
        orders.filter { it.getRecordedEvents().isNotEmpty() }.forEach { order ->
            orderRepository.save(order)
            auditTrail.record(
                AuditEntry(
                    now, actor, AuditAction.ORDER_SHIPPED, AuditEntityType.ORDER, order.code.value, order.code.value,
                    AuditDiff.build {
                        field("trackingCode", old.trackingCode.value, input.trackingCode.value)
                        field("shippedAt", old.shippedOn, shippedOn)
                        field("showInDailyList", old.showInDailyList, input.showInDailyList)
                    },
                ),
            )
        }
        logger.info("ShipOrderUc: parcel of ${orders.map { it.code.value }} corrected")
        return Result.success(Unit)
    }

    private fun inputOf(command: Cmd): Result<Input, Failure> {
        val problems = mutableListOf<ResultFailure>()
        val carrier = ShippingMethodCode.entries.firstOrNull { it.name == command.carrier?.trim() }
            ?: null.also { problems += FieldFailure("carrier", Problem.CarrierInvalid) }
        val trackingCode = when (val parsed = TrackingCode.makeNew(command.trackingCode)) {
            is Result.Success -> parsed.value
            is Result.Failure -> null.also { problems += parsed.failure }
        }
        val shippedOn = command.shippedAt?.trim()?.ifEmpty { null }?.let { raw ->
            try {
                Instant.parse(raw)
            } catch (e: DateTimeParseException) {
                null.also { problems += FieldFailure("shippedAt", Problem.ShippedAtInvalid) }
            }
        }
        return if (problems.isEmpty()) {
            Result.success(Input(carrier!!, trackingCode!!, shippedOn, command.showInDailyList ?: true))
        } else {
            Result.failure(Failure.InvalidInput(problems))
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }

    /** What can be wrong with the body; the field is added where it is found. */
    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object CarrierInvalid : Problem("CARRIER_INVALID")

        data object ShippedAtInvalid : Problem("SHIPPED_AT_INVALID")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)

        /** The order's own rule refused it (422): AWAITING_CUSTOMER_FEEDBACK or INVALID_STATUS_TRANSITION. */
        data class Refused(val reason: String, val details: Map<String, Any>) : Failure(reason, FailureKind.BUSINESS_RULE, meta = details)

        /** The order's reservation group still holds its orders (422). */
        data class GroupHolding(val status: OrderStatus) : Failure(
            "INVALID_STATUS_TRANSITION",
            FailureKind.BUSINESS_RULE,
            meta = mapOf("from" to status.name, "to" to OrderStatus.SHIPPED.name, "allowed" to emptyList<String>()),
        )

        /** Some orders of the group can't ship yet (422), named by code; none shipped. */
        data class GroupNotReady(val orderCodes: List<OrderCode>) :
            Failure("GROUP_NOT_READY", FailureKind.BUSINESS_RULE, meta = mapOf("orderCodes" to orderCodes.map { it.value }))

        companion object {
            /** A refusal of the order's: on its field (400 CARRIER_MISMATCH, SHIPPED_AT_INVALID) or 422. */
            fun of(failure: ResultFailure): Failure =
                if (failure.kind == FailureKind.INVALID) InvalidInput(listOf(failure)) else Refused(failure.code, failure.meta)
        }
    }
}
