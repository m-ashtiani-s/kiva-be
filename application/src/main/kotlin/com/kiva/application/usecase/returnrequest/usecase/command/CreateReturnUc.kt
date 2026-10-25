package com.kiva.application.usecase.returnrequest.usecase.command

import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.usecase.order.OrderByCode
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.ListItemDuplicate
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TooManyItems
import com.kiva.common.railway.forField
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.repository.MediaRepository
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.refund.model.valueobject.RefundMethod
import com.kiva.domain.aggregate.returnrequest.ReturnDomainService
import com.kiva.domain.aggregate.returnrequest.model.policy.ReturnPolicy
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnItem
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnReason
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnText
import com.kiva.domain.aggregate.returnrequest.repository.ReturnRequestRepository
import java.time.Clock

/**
 * The customer asks to send lines of a delivered order back (`POST /me/orders/{orderCode}/returns`; roadmap section 12.7),
 * in the contract's order of rules:
 *
 * 1. the shape, every problem at once (400): 1 to 20 lines, each a line number and 1 to 20 of it, once; a reason of the
 *    contract's; a description of at most 1000; at most 6 well-formed uploads, each once; ORIGINAL_PAYMENT or BANK_TRANSFER
 *    (ORIGINAL_PAYMENT when it didn't come);
 * 2. in one transaction: the customer's own order (someone else's, none, or a code that can't be one: 404
 *    ORDER_NOT_FOUND), its row locked (lock 9), then ReturnDomainService.open: the uploads (the customer's own return
 *    evidence, 400 MEDIA_NOT_FOUND otherwise), whether it may be returned now (422), its lines, the evidence and the
 *    description (400); then the return stored, the order's OrderReturnRequested, the uploads marked used.
 *
 * Two requests at once: the order's lock lets the first go, and the second finds it RETURN_REQUESTED (422); the partial
 * unique index is the wall behind it. No text message: the customer just asked. Nothing in the change log: it is the
 * customer's, not the panel's. Returns the new return's id; the route reads the return with GetMyReturnUc.
 */
class CreateReturnUc(
    private val transactionManager: TransactionManager,
    private val orderByCode: OrderByCode,
    private val orderQmRepository: OrderQmRepository,
    private val orderRepository: OrderRepository,
    private val mediaRepository: MediaRepository,
    private val returnDomainService: ReturnDomainService,
    private val returnRequestRepository: ReturnRequestRepository,
    private val clock: Clock,
) : CommandUseCase<CreateReturnUc.Cmd, ReturnId, CreateReturnUc.Failure> {

    private val logger = getLogger()

    /** One line of the body, as it came. */
    data class ItemFields(val orderItemId: Int?, val quantity: Int?)

    /** The body as it came; [description] never goes into a log ([toString]). */
    data class Cmd(
        val userId: UserId,
        val orderCode: String,
        val items: List<ItemFields>?,
        val reason: String?,
        val description: String?,
        val mediaIds: List<String>?,
        val refundMethod: String?,
    ) {
        override fun toString(): String = "Cmd(userId=${userId.value}, orderCode=$orderCode, items=${items?.size}, reason=$reason, media=${mediaIds?.size})"
    }

    private data class Input(
        val items: List<ReturnItem>,
        val reason: ReturnReason,
        val description: ReturnText?,
        val mediaIds: List<MediaId>,
        val refundMethod: RefundMethod,
    )

    override suspend fun execute(command: Cmd): Result<ReturnId, Failure> {
        val code: OrderCode? = orderByCode.codeOf(command.orderCode)
        logger.info("CreateReturnUc.execute userId=${command.userId.value} orderCode=${code?.value ?: "(not a code)"} items=${command.items?.size} reason=${command.reason}")
        val input = when (val checked = inputOf(command)) {
            is Result.Success -> checked.value
            is Result.Failure -> return Result.failure(checked.failure)
        }
        if (code == null) return Result.failure(Failure.OrderNotFound)

        return transactionManager.inTransaction {
            val read = orderByCode.findOfCustomer(command.userId, command.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
            orderQmRepository.lockOrder(read.orderId) ?: error("Order ${read.orderId.value} is not in the read model")
            val order = orderRepository.get(read.orderId) ?: error("Order ${read.orderId.value} of the read model has no events")
            val found = if (input.mediaIds.isEmpty()) emptyList() else mediaRepository.findAll(input.mediaIds)
            val now = clock.instant()

            val ret = when (
                val opened = returnDomainService.open(
                    order, command.userId, input.items, input.reason, input.description, input.mediaIds, found, input.refundMethod, now,
                )
            ) {
                is Result.Success -> opened.value
                is Result.Failure -> return@inTransaction Result.failure(failureOf(opened.failure))
            }
            returnRequestRepository.add(ret)
            orderRepository.save(order)
            if (input.mediaIds.isNotEmpty() && mediaRepository.markAttached(input.mediaIds, now) < input.mediaIds.size) {
                // An upload removed between reading and marking it (the 48-hour clean-up): as if it had never been there.
                return@inTransaction Result.failure(Failure.InvalidInput(listOf(FieldFailure("mediaIds", ReturnPolicy.Problem.MediaNotFound))))
            }
            logger.info("CreateReturnUc: return ${ret.id.value} (${ret.code.value}) of order ${order.code.value} asked")
            Result.success(ret.id)
        }
    }

    /** The body's shape, every problem at once, each under its field. */
    private fun inputOf(command: Cmd): Result<Input, Failure> {
        val problems = mutableListOf<ResultFailure>()

        val rawItems = command.items.orEmpty()
        val items = when {
            rawItems.isEmpty() -> null.also { problems += FieldFailure("items", FieldRequired) }
            rawItems.size > ReturnPolicy.MAX_ITEMS -> null.also { problems += FieldFailure("items", TooManyItems(ReturnPolicy.MAX_ITEMS)) }
            else -> {
                val seen = mutableSetOf<Int>()
                rawItems.mapIndexedNotNull { i, item ->
                    val lineId = when {
                        item.orderItemId == null -> null.also { problems += FieldFailure("items.$i.orderItemId", FieldRequired) }
                        item.orderItemId < 1 -> null.also { problems += FieldFailure("items.$i.orderItemId", ReturnPolicy.Problem.UnknownLine) }
                        !seen.add(item.orderItemId) -> null.also { problems += FieldFailure("items.$i.orderItemId", ListItemDuplicate) }
                        else -> item.orderItemId
                    }
                    val quantity = when {
                        item.quantity == null -> null.also { problems += FieldFailure("items.$i.quantity", FieldRequired) }
                        item.quantity !in 1..ReturnPolicy.MAX_QUANTITY -> null.also { problems += FieldFailure("items.$i.quantity", Problem.QuantityInvalid) }
                        else -> item.quantity
                    }
                    if (lineId != null && quantity != null) ReturnItem.constructFromPersisted(lineId, quantity) else null
                }
            }
        }

        val reason = when (val raw = command.reason?.trim()?.ifEmpty { null }) {
            null -> null.also { problems += FieldFailure("reason", FieldRequired) }
            else -> when (val parsed = ReturnReason.makeNew(raw)) {
                is Result.Success -> parsed.value
                is Result.Failure -> null.also { problems += parsed.failure }
            }
        }

        val description = when (val parsed = ReturnText.makeNew(command.description).forField("description")) {
            is Result.Success -> parsed.value
            is Result.Failure -> null.also { problems += parsed.failure }
        }

        val rawMedia = command.mediaIds.orEmpty()
        val mediaIds = if (rawMedia.size > ReturnPolicy.MAX_MEDIA) {
            problems += FieldFailure("mediaIds", TooManyItems(ReturnPolicy.MAX_MEDIA))
            emptyList()
        } else {
            val seen = mutableSetOf<MediaId>()
            rawMedia.mapIndexedNotNull { i, raw ->
                when (val parsed = MediaId.makeNew(raw)) {
                    is Result.Failure -> null.also { problems += FieldFailure("mediaIds.$i", parsed.failure) }
                    is Result.Success -> if (seen.add(parsed.value)) parsed.value else null.also { problems += FieldFailure("mediaIds.$i", ListItemDuplicate) }
                }
            }
        }

        val refundMethod = when (val raw = command.refundMethod?.trim()?.ifEmpty { null }) {
            null -> RefundMethod.ORIGINAL_PAYMENT
            else -> when (val parsed = RefundMethod.makeNew(raw).forField("refundMethod")) {
                is Result.Success -> parsed.value
                is Result.Failure -> null.also { problems += parsed.failure }
            }
        }

        if (problems.isNotEmpty()) return Result.failure(Failure.InvalidInput(problems))
        return Result.success(Input(checkNotNull(items), checkNotNull(reason), description, mediaIds, checkNotNull(refundMethod)))
    }

    private fun failureOf(failure: ReturnDomainService.OpenFailure): Failure = when (failure) {
        is ReturnDomainService.OpenFailure.InvalidInput -> Failure.InvalidInput(failure.problems)
        is ReturnDomainService.OpenFailure.NotReturnable -> Failure.Refused(failure.code, failure.meta)
        is ReturnDomainService.OpenFailure.WindowExpired -> Failure.Refused(failure.code, failure.meta)
    }

    /** What can be wrong with a line of the body; the field is added where it is found. */
    sealed class Problem(code: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, meta = meta) {
        data object QuantityInvalid : Problem("QUANTITY_INVALID", mapOf("max" to ReturnPolicy.MAX_QUANTITY))
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        /** Every problem of the body or of the request, each under its field (400). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)

        /** ORDER_NOT_RETURNABLE, or RETURN_WINDOW_EXPIRED with `meta.returnableUntil` (422). */
        data class Refused(val reason: String, val details: Map<String, Any>) : Failure(reason, FailureKind.BUSINESS_RULE, meta = details)
    }
}
