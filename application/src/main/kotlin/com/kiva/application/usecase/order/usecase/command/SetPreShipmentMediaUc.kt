package com.kiva.application.usecase.order.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.application.usecase.order.OrderByCode
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.policy.PreShipmentPolicy
import com.kiva.domain.aggregate.order.model.valueobject.OrderPhoto
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import java.time.Clock

/**
 * The operator's photos of the packed bag (`PUT /admin/orders/{orderCode}/pre-shipment-media`; ADMIN, OPERATOR): they
 * replace the order's draft as a whole; the customer sees nothing until they are sent (SendPreShipmentMediaUc).
 *
 * Order of checks: the shape of every item at once (1 to [PreShipmentPolicy.MAX_PHOTOS], each a well-formed upload once,
 * a view of the contract, a sort order not negative); then, in the transaction, the order (404), the uploads (there, and
 * uploaded as PRE_SHIPMENT; 400 on their field), and the order's own rules (PROCESSING with its photos WAITING, 422; each
 * line one of its own, 400). One ORDER_MEDIA_SET row in the change log with how many photos each line has, before and
 * after. A panel form: a change made by someone else in between is 409, never tried again.
 */
class SetPreShipmentMediaUc(
    private val transactionManager: TransactionManager,
    private val orderByCode: OrderByCode,
    private val orderRepository: OrderRepository,
    private val mediaAttachments: MediaAttachments,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<SetPreShipmentMediaUc.Cmd, Unit, SetPreShipmentMediaUc.Failure> {

    private val logger = getLogger()

    /** One item of the body, as it came. */
    data class ItemFields(val mediaId: String?, val orderItemId: Int?, val view: String?, val sortOrder: Int?)

    /** @property items null when the body had none. */
    data class Cmd(val orderCode: String, val items: List<ItemFields>?, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        val code = orderByCode.codeOf(command.orderCode)
        logger.info("SetPreShipmentMediaUc.execute orderCode=${code?.value ?: "(not a code)"} items=${command.items?.size} actor=${command.actor.userId.value}")
        val items = photosOf(command.items).unwrapOr { return Result.failure(it) }
        if (code == null) return Result.failure(Failure.OrderNotFound)

        return transactionManager.inTransaction {
            val order = orderByCode.loadForPanel(command.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
            val wanted = items.mapIndexed { i, photo -> MediaAttachments.Wanted(photo.mediaId, "items.$i.mediaId") }
            mediaAttachments.check(wanted, MediaPurpose.PRE_SHIPMENT, setOf(MediaType.IMAGE, MediaType.VIDEO))
                .getOrElse { return@inTransaction Result.failure(Failure.InvalidInput(it.causes)) }

            val before = order.photoDraft
            val now = clock.instant()
            when (val attached = order.attachPhotos(items, command.actor.userId, now)) {
                is Result.Success -> {}
                is Result.Failure -> return@inTransaction Result.failure(failureOf(attached.failure))
            }
            orderRepository.save(order)
            mediaAttachments.attach(items.map { it.mediaId }, now)
            auditTrail.record(
                AuditEntry(
                    now, command.actor, AuditAction.ORDER_MEDIA_SET, AuditEntityType.ORDER, order.code.value, order.code.value,
                    AuditDiff.build { json("media", countsOf(before), countsOf(items)) },
                ),
            )
            Result.success(Unit)
        }
    }

    /** The body's items, every problem at once under its own field. */
    private fun photosOf(raw: List<ItemFields>?): Result<List<OrderPhoto>, Failure> {
        val items = raw.orEmpty()
        if (items.isEmpty()) return Result.failure(Failure.InvalidInput(listOf(FieldFailure("items", Problem.Required))))
        if (items.size > PreShipmentPolicy.MAX_PHOTOS) return Result.failure(Failure.InvalidInput(listOf(FieldFailure("items", Problem.TooMany))))

        val problems = mutableListOf<ResultFailure>()
        val seen = mutableSetOf<MediaId>()
        val photos = items.mapIndexedNotNull { i, item ->
            val mediaId = (item.mediaId?.let { MediaId.makeNew(it) } ?: Result.failure(MediaId.Failure.Invalid)).forField("items.$i.mediaId")
                .getOrElse { problems += it; null }
            if (mediaId != null && !seen.add(mediaId)) problems += FieldFailure("items.$i.mediaId", Problem.Duplicate)
            val lineId = item.orderItemId?.takeIf { it < 1 }?.let { problems += FieldFailure("items.$i.orderItemId", Problem.UnknownLine); null } ?: item.orderItemId
            val view = item.view?.trim()?.ifEmpty { null }?.let { raw -> MediaView.makeNew(raw).forField("items.$i.view").getOrElse { problems += it; null } }
            val sortOrder = item.sortOrder ?: i
            if (sortOrder < 0) problems += FieldFailure("items.$i.sortOrder", Problem.SortOrderInvalid)
            if (mediaId == null || sortOrder < 0) null else OrderPhoto(mediaId, lineId, view, sortOrder)
        }
        return if (problems.isEmpty()) Result.success(photos) else Result.failure(Failure.InvalidInput(problems))
    }

    /** How many photos each line has (`order` for a photo of the whole order), as the change log keeps it; none is null. */
    private fun countsOf(photos: List<OrderPhoto>): Map<String, Any?>? =
        photos.takeIf { it.isNotEmpty() }?.groupingBy { it.lineId?.toString() ?: "order" }?.eachCount()

    private fun failureOf(failure: Order.AttachPhotosFailure): Failure = when (failure) {
        is Order.AttachPhotosFailure.NotInPhotoStep -> Failure.NotInPhotoStep(failure.meta)
        is Order.AttachPhotosFailure.UnknownLine -> Failure.InvalidInput(listOf(FieldFailure(checkNotNull(failure.field), Problem.UnknownLine)))
    }

    private inline fun <T, F : ResultFailure> Result<T, F>.getOrElse(onFailure: (F) -> T): T = when (this) {
        is Result.Success -> value
        is Result.Failure -> onFailure(failure)
    }

    /** What can be wrong with an item; the field is added where it is found. */
    sealed class Problem(code: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, meta = meta) {
        data object Required : Problem("PHOTOS_REQUIRED")
        data object TooMany : Problem("PHOTOS_TOO_MANY", mapOf("maxItems" to PreShipmentPolicy.MAX_PHOTOS))
        data object Duplicate : Problem("MEDIA_DUPLICATE")
        data object UnknownLine : Problem("ORDER_ITEM_NOT_FOUND")
        data object SortOrderInvalid : Problem("SORT_ORDER_INVALID")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        /** Items that are wrong, each under its field (400). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)

        /** Not being prepared with its photos waiting (422). */
        data class NotInPhotoStep(val details: Map<String, Any>) : Failure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, meta = details)
    }
}
