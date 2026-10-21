package com.kiva.application.usecase.returnrequest.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.product.MomentField
import com.kiva.application.usecase.returnrequest.ReturnByIdForPanel
import com.kiva.application.usecase.returnrequest.ReturnNotices
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.returnrequest.model.policy.ReturnPolicy
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnCode
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnPickup
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnText
import com.kiva.domain.aggregate.returnrequest.repository.ReturnRequestRepository
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock
import java.time.Instant

/**
 * The shop collects a return's parcel (`PUT /admin/returns/{returnId}/pickup`; ADMIN, OPERATOR; roadmap section 12.2,
 * decision 9): PICKUP_SCHEDULED with the carrier, maybe when and the carrier's tracking code, and a note for the
 * customer. From APPROVED or PICKUP_SCHEDULED (422 INVALID_STATUS_TRANSITION otherwise); again replaces the whole pickup.
 *
 * The shape first, every problem at once: the carrier TIPAX or POST (none: FIELD_REQUIRED; not one: CARRIER_INVALID;
 * COURIER: SHIPPING_METHOD_NOT_SUPPORTED), the time from an hour ago to 30 days on (PICKUP_TIME_INVALID; not a moment:
 * DATE_TIME_INVALID), the tracking code as a shipment's (TRACKING_CODE_INVALID; blank is none), a note of at most 500.
 * Then the return's order and the return are locked (ReturnByIdForPanel: locks 9, 10). The order doesn't change. One
 * RETURN_PICKUP_SCHEDULED row in the change log, unless the same pickup was asked again (nothing changed: no row, no
 * SMS); after the commit «جمع‌آوری مرجوعی» with the carrier's name, only with [Cmd.notifyCustomer] (by default). A panel
 * form: never tried again.
 */
class ScheduleReturnPickupUc(
    private val transactionManager: TransactionManager,
    private val returnByIdForPanel: ReturnByIdForPanel,
    private val returnRequestRepository: ReturnRequestRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val userRepository: UserRepository,
    private val auditTrail: AuditTrail,
    private val returnNotices: ReturnNotices,
    private val clock: Clock,
) : CommandUseCase<ScheduleReturnPickupUc.Cmd, Unit, ScheduleReturnPickupUc.Failure> {

    private val logger = getLogger()

    /** The body as it came; the note never goes into a log ([toString]). */
    data class Cmd(
        val returnId: String,
        val carrier: String?,
        val scheduledFor: String?,
        val trackingCode: String?,
        val note: String?,
        val notifyCustomer: Boolean?,
        val actor: PanelActor,
    ) {
        override fun toString(): String =
            "Cmd(returnId=$returnId, carrier=$carrier, scheduledFor=$scheduledFor, notifyCustomer=$notifyCustomer, actor=${actor.userId.value})"
    }

    /** What to tell the customer after the commit; null when nothing changed or they shouldn't be told. */
    private data class Scheduled(val code: ReturnCode, val to: IranianMobileNumber?, val carrierName: String)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        val id = ReturnId.makeNew(command.returnId).getOrNull()
        logger.info("ScheduleReturnPickupUc.execute returnId=${id?.value ?: "(not an id)"} carrier=${command.carrier} actor=${command.actor.userId.value}")
        val now = clock.instant()
        val pickup = when (val checked = pickupOf(command, now)) {
            is Result.Success -> checked.value
            is Result.Failure -> return Result.failure(checked.failure)
        }
        if (id == null) return Result.failure(Failure.ReturnNotFound)

        val done = transactionManager.inTransaction {
            val ret = returnByIdForPanel.lock(id)?.ret ?: return@inTransaction Result.failure(Failure.ReturnNotFound)
            val before = ret.pickup
            val statusBefore = ret.status
            when (val moved = ret.schedulePickup(pickup)) {
                is Result.Success -> {}
                is Result.Failure -> return@inTransaction Result.failure(Failure.InvalidTransition(moved.failure.meta))
            }
            if (before == pickup && statusBefore == ret.status) return@inTransaction Result.success(null)

            returnRequestRepository.save(ret)
            auditTrail.record(
                AuditEntry(
                    now, command.actor, AuditAction.RETURN_PICKUP_SCHEDULED, AuditEntityType.RETURN, ret.id.value.toString(), ret.orderCode.value,
                    AuditDiff.build {
                        field("status", statusBefore, ret.status)
                        field("carrier", before?.carrier, pickup.carrier)
                        field("scheduledFor", before?.scheduledFor, pickup.scheduledFor)
                        field("trackingCode", before?.trackingCode?.value, pickup.trackingCode?.value)
                    },
                ),
            )
            logger.info("ScheduleReturnPickupUc: the parcel of return ${ret.id.value} is collected by ${pickup.carrier}")
            if (command.notifyCustomer == false) return@inTransaction Result.success(null)
            val carrierName = shopSettingsRepository.get().shippingMethods.firstOrNull { it.code == pickup.carrier }?.name ?: pickup.carrier.name
            Result.success(Scheduled(ret.code, userRepository.findById(ret.userId)?.phone, carrierName))
        }
        return when (done) {
            is Result.Success -> {
                done.value?.let { returnNotices.pickup(it.to, it.code, it.carrierName) }
                Result.success(Unit)
            }
            is Result.Failure -> Result.failure(done.failure)
        }
    }

    /** The body's shape, every problem at once, each under its field; the time against [now]. */
    private fun pickupOf(command: Cmd, now: Instant): Result<ReturnPickup, Failure> {
        val problems = mutableListOf<ResultFailure>()
        val carrier = when (val raw = command.carrier?.trim()?.ifEmpty { null }) {
            null -> null.also { problems += FieldFailure("carrier", FieldRequired) }
            else -> {
                val code = ShippingMethodCode.entries.firstOrNull { it.name == raw }
                when {
                    code == null -> null.also { problems += FieldFailure("carrier", Problem.CarrierInvalid) }
                    code !in ReturnPolicy.PICKUP_CARRIERS -> null.also { problems += FieldFailure("carrier", Problem.CarrierNotSupported) }
                    else -> code
                }
            }
        }
        val scheduledFor = when (val parsed = MomentField.makeNew(command.scheduledFor).forField("scheduledFor")) {
            is Result.Success -> parsed.value?.also { if (!ReturnPolicy.pickupTimeFits(it, now)) problems += FieldFailure("scheduledFor", Problem.PickupTimeInvalid) }
            is Result.Failure -> null.also { problems += parsed.failure }
        }
        val trackingCode = command.trackingCode?.trim()?.ifEmpty { null }?.let { raw ->
            when (val parsed = TrackingCode.makeNew(raw)) {
                is Result.Success -> parsed.value
                is Result.Failure -> null.also { problems += parsed.failure }
            }
        }
        val note = when (val parsed = ReturnText.makeNew(command.note, ReturnText.MAX_NOTE_LENGTH).forField("note")) {
            is Result.Success -> parsed.value
            is Result.Failure -> null.also { problems += parsed.failure }
        }
        if (problems.isNotEmpty()) return Result.failure(Failure.InvalidInput(problems))
        return Result.success(ReturnPickup.constructFromPersisted(checkNotNull(carrier), scheduledFor, trackingCode, note))
    }

    /** What can be wrong with the body; the field is added where it is found. */
    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object CarrierInvalid : Problem("CARRIER_INVALID")

        /** The courier doesn't collect parcels yet: only TIPAX and POST. */
        data object CarrierNotSupported : Problem("SHIPPING_METHOD_NOT_SUPPORTED")

        /** Earlier than an hour ago, or later than 30 days on. */
        data object PickupTimeInvalid : Problem("PICKUP_TIME_INVALID")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        /** Every problem of the body, each under its field (400). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object ReturnNotFound : Failure("RETURN_NOT_FOUND", FailureKind.NOT_FOUND)

        /** Not APPROVED nor PICKUP_SCHEDULED (422), with `meta.from`, `to` and `allowed`. */
        data class InvalidTransition(val details: Map<String, Any>) : Failure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, meta = details)
    }
}
