package com.kiva.application.usecase.order.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.order.OrderByCode
import com.kiva.application.usecase.order.OrderNotices
import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.PhotoMessage
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock

/**
 * The operator sent the draft to the customer, by hand on their messenger, and records it here
 * (`POST /admin/orders/{orderCode}/pre-shipment-media/send`; ADMIN, OPERATOR): the order is PHOTO_SENT, the customer
 * sees the batch, and has the shop's `preShipmentFeedbackHours` of this moment to answer (a later change of the setting
 * never moves the deadline it was given). There is no messenger bot: `manual` is always
 * true (false is 400 MANUAL_ONLY), `channelOverride` is the messenger actually used, and `message` is only kept.
 *
 * One ORDER_PHOTO_SENT row in the change log; after the commit, «عکس کیفت فرستاده شد» to the buyer's phone. A change
 * of the order at the same moment (the customer cancelling) makes the whole transaction run again, up to [MAX_ATTEMPTS]
 * times, deciding again on what is there then.
 */
class SendPreShipmentMediaUc(
    private val transactionManager: TransactionManager,
    private val orderByCode: OrderByCode,
    private val orderRepository: OrderRepository,
    private val userRepository: UserRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val auditTrail: AuditTrail,
    private val orderNotices: OrderNotices,
    private val clock: Clock,
) : CommandUseCase<SendPreShipmentMediaUc.Cmd, Unit, SendPreShipmentMediaUc.Failure> {

    private val logger = getLogger()

    /** The body as it came: every field optional. */
    data class Cmd(val orderCode: String, val manual: Boolean?, val channelOverride: String?, val message: String?, val actor: PanelActor) {
        override fun toString(): String = "Cmd(orderCode=$orderCode, manual=$manual, channelOverride=$channelOverride, actor=${actor.userId.value})"
    }

    private data class Input(val channel: MessengerChannel?, val message: PhotoMessage?)

    private data class Sent(val code: OrderCode, val to: IranianMobileNumber?)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        val code = orderByCode.codeOf(command.orderCode)
        logger.info("SendPreShipmentMediaUc.execute orderCode=${code?.value ?: "(not a code)"} channelOverride=${command.channelOverride} actor=${command.actor.userId.value}")
        val input = inputOf(command)
        if (input is Result.Failure) return Result.failure(input.failure)
        if (code == null) return Result.failure(Failure.OrderNotFound)
        val checked = (input as Result.Success).value

        repeat(MAX_ATTEMPTS - 1) { attempt ->
            try {
                return sendOnce(command, checked)
            } catch (e: ConcurrentChangeException) {
                logger.info("SendPreShipmentMediaUc: order ${code.value} changed meanwhile, attempt ${attempt + 1} of $MAX_ATTEMPTS refused; trying again")
            }
        }
        return sendOnce(command, checked)
    }

    private suspend fun sendOnce(command: Cmd, input: Input): Result<Unit, Failure> {
        val sent = transactionManager.inTransaction {
            val order = orderByCode.loadForPanel(command.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
            val now = clock.instant()
            val channel = input.channel ?: order.messenger.channel
            when (val result = order.sendPhotos(channel, input.message, shopSettingsRepository.get().preShipmentFeedbackHours, command.actor.userId, now)) {
                is Result.Success -> {}
                is Result.Failure -> return@inTransaction Result.failure(failureOf(result.failure))
            }
            orderRepository.save(order)
            auditTrail.record(AuditEntry(now, command.actor, AuditAction.ORDER_PHOTO_SENT, AuditEntityType.ORDER, order.code.value, order.code.value, null))
            Result.success(Sent(order.code, userRepository.findById(order.userId)?.phone))
        }
        return when (sent) {
            is Result.Success -> {
                orderNotices.photosSent(sent.value.to, sent.value.code)
                Result.success(Unit)
            }
            is Result.Failure -> sent
        }
    }

    private fun inputOf(command: Cmd): Result<Input, Failure> {
        val problems = mutableListOf<ResultFailure>()
        if (command.manual == false) problems += FieldFailure("manual", Problem.ManualOnly)
        val channel = command.channelOverride?.trim()?.ifEmpty { null }?.let { raw ->
            when (val parsed = MessengerChannel.makeNew(raw).forField("channelOverride")) {
                is Result.Success -> parsed.value
                is Result.Failure -> null.also { problems += parsed.failure }
            }
        }
        val message = when (val parsed = PhotoMessage.makeNew(command.message)) {
            is Result.Success -> parsed.value
            is Result.Failure -> null.also { problems += parsed.failure }
        }
        return if (problems.isEmpty()) Result.success(Input(channel, message)) else Result.failure(Failure.InvalidInput(problems))
    }

    private fun failureOf(failure: Order.SendPhotosFailure): Failure = when (failure) {
        is Order.SendPhotosFailure.NotInPhotoStep -> Failure.Refused(failure.code, failure.meta)
        is Order.SendPhotosFailure.PhotosIncomplete -> Failure.Refused(failure.code, failure.meta)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }

    /** What can be wrong with the body; the field is added where it is found. */
    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object ManualOnly : Problem("MANUAL_ONLY")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)

        /** The order's rule refused it (422): INVALID_STATUS_TRANSITION, or PHOTOS_INCOMPLETE with `missingItemIds`. */
        data class Refused(val reason: String, val details: Map<String, Any>) : Failure(reason, FailureKind.BUSINESS_RULE, meta = details)
    }
}
