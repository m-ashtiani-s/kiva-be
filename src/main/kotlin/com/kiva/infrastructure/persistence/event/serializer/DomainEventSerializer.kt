package com.kiva.infrastructure.persistence.event.serializer

import com.kiva.common.event.valueobject.EventVersion
import com.kiva.domain.aggregate.inventory.entity.event.InventoryDomainEvent
import com.kiva.domain.aggregate.inventory.entity.event.ReservationReleased
import com.kiva.domain.aggregate.inventory.entity.event.StockAdjusted
import com.kiva.domain.aggregate.inventory.entity.event.StockReserved
import com.kiva.domain.aggregate.inventory.entity.event.StockShipped
import com.kiva.domain.aggregate.order.entity.event.OrderBalancePaid
import com.kiva.domain.aggregate.order.entity.event.OrderCancelled
import com.kiva.domain.aggregate.order.entity.event.OrderChangeApplied
import com.kiva.domain.aggregate.order.entity.event.OrderChangeRejected
import com.kiva.domain.aggregate.order.entity.event.OrderChangeRequested
import com.kiva.domain.aggregate.order.entity.event.OrderDelivered
import com.kiva.domain.aggregate.order.entity.event.OrderDomainEvent
import com.kiva.domain.aggregate.order.entity.event.OrderExpired
import com.kiva.domain.aggregate.order.entity.event.OrderLineRemoved
import com.kiva.domain.aggregate.order.entity.event.OrderLineReplaced
import com.kiva.domain.aggregate.order.entity.event.OrderPaid
import com.kiva.domain.aggregate.order.entity.event.OrderPaymentFailed
import com.kiva.domain.aggregate.order.entity.event.OrderPersonalDataErased
import com.kiva.domain.aggregate.order.entity.event.OrderPhotosApproved
import com.kiva.domain.aggregate.order.entity.event.OrderPhotosAttached
import com.kiva.domain.aggregate.order.entity.event.OrderPhotosSent
import com.kiva.domain.aggregate.order.entity.event.OrderPlaced
import com.kiva.domain.aggregate.order.entity.event.OrderRefunded
import com.kiva.domain.aggregate.order.entity.event.OrderReleasedFromReservation
import com.kiva.domain.aggregate.order.entity.event.OrderReturnEnded
import com.kiva.domain.aggregate.order.entity.event.OrderReturnReceived
import com.kiva.domain.aggregate.order.entity.event.OrderReturnRequested
import com.kiva.domain.aggregate.order.entity.event.OrderShipmentCorrected
import com.kiva.domain.aggregate.order.entity.event.OrderShipped
import com.kiva.domain.aggregate.product.entity.event.ProductArchived
import com.kiva.domain.aggregate.product.entity.event.ProductCreated
import com.kiva.domain.aggregate.product.entity.event.ProductDetailsChanged
import com.kiva.domain.aggregate.product.entity.event.ProductDomainEvent
import com.kiva.domain.aggregate.product.entity.event.ProductPriceChanged
import com.kiva.domain.aggregate.product.entity.event.ProductPublishScheduled
import com.kiva.domain.aggregate.product.entity.event.ProductPublishUnscheduled
import com.kiva.domain.aggregate.product.entity.event.ProductPublished
import com.kiva.domain.aggregate.product.entity.event.ProductUnpublished
import com.kiva.domain.aggregate.product.entity.event.VariantAdded
import com.kiva.domain.aggregate.product.entity.event.VariantChanged
import com.kiva.domain.aggregate.product.entity.event.VariantDeactivated
import com.kiva.domain.aggregate.product.entity.event.VariantMediaReplaced
import com.kiva.domain.aggregate.product.entity.event.VariantReactivated
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.inventory.ReservationReleasedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.inventory.StockAdjustedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.inventory.StockReservedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.inventory.StockShippedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderBalancePaidPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderCancelledPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderChangeAppliedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderChangeRejectedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderChangeRequestedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderDeliveredPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderExpiredPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderLineRemovedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderLineReplacedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderPaidPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderPaymentFailedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderPersonalDataErasedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderPhotosApprovedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderPhotosAttachedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderPhotosSentPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderPlacedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderRefundedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderReleasedFromReservationPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderReturnEndedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderReturnReceivedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderReturnRequestedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderShipmentCorrectedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderShippedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.product.ProductArchivedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.product.ProductCreatedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.product.ProductDetailsChangedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.product.ProductPriceChangedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.product.ProductPublishScheduledPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.product.ProductPublishUnscheduledPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.product.ProductPublishedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.product.ProductUnpublishedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.product.VariantAddedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.product.VariantChangedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.product.VariantDeactivatedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.product.VariantMediaReplacedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.product.VariantReactivatedPayload
import kotlinx.serialization.json.Json

/**
 * Turns a domain event into the JSON kept in the event store's `payload` column, and back.
 *
 * Writing picks the payload class by the event's class. Reading picks it by the stored `eventType`, so two event
 * classes with the same type would read back as the wrong one: every type is unique and never renamed.
 *
 * A type or version this code doesn't know is not a business failure but stored data it can't read (a bug or a
 * missing reader), so it stops loudly instead of returning a Result.
 *
 * One pair of functions per aggregate: the repository knows which kind of stream it reads, so it asks for that one.
 */
object DomainEventSerializer {

    // Strict on purpose: an unknown field means the stored shape and the payload class disagree. A missing field is
    // read only where the payload gives it a default (a field added later); every field is always written, defaults too.
    private val json = Json { encodeDefaults = true }

    // ============ product ============

    fun serialize(event: ProductDomainEvent): String = when (event) {
        is ProductCreated -> json.encodeToString(ProductCreatedPayload(event))
        is ProductDetailsChanged -> json.encodeToString(ProductDetailsChangedPayload(event))
        is ProductPriceChanged -> json.encodeToString(ProductPriceChangedPayload(event))
        is ProductPublished -> json.encodeToString(ProductPublishedPayload(event))
        is ProductUnpublished -> json.encodeToString(ProductUnpublishedPayload(event))
        is ProductPublishScheduled -> json.encodeToString(ProductPublishScheduledPayload(event))
        is ProductPublishUnscheduled -> json.encodeToString(ProductPublishUnscheduledPayload(event))
        is ProductArchived -> json.encodeToString(ProductArchivedPayload(event))
        is VariantAdded -> json.encodeToString(VariantAddedPayload(event))
        is VariantChanged -> json.encodeToString(VariantChangedPayload(event))
        is VariantDeactivated -> json.encodeToString(VariantDeactivatedPayload(event))
        is VariantReactivated -> json.encodeToString(VariantReactivatedPayload(event))
        is VariantMediaReplaced -> json.encodeToString(VariantMediaReplacedPayload(event))
    }

    fun deserializeProduct(payload: String, payloadVersion: Int, eventType: String): ProductDomainEvent {
        // Every product event is still at its first shape. When one changes, its old version gets its own reader here.
        check(payloadVersion == EventVersion.FIRST.value) { "No reader for version $payloadVersion of '$eventType'" }

        val eventPayload: EventPayload<ProductDomainEvent> = when (eventType) {
            ProductCreated.TYPE.value -> json.decodeFromString<ProductCreatedPayload>(payload)
            ProductDetailsChanged.TYPE.value -> json.decodeFromString<ProductDetailsChangedPayload>(payload)
            ProductPriceChanged.TYPE.value -> json.decodeFromString<ProductPriceChangedPayload>(payload)
            ProductPublished.TYPE.value -> json.decodeFromString<ProductPublishedPayload>(payload)
            ProductUnpublished.TYPE.value -> json.decodeFromString<ProductUnpublishedPayload>(payload)
            ProductPublishScheduled.TYPE.value -> json.decodeFromString<ProductPublishScheduledPayload>(payload)
            ProductPublishUnscheduled.TYPE.value -> json.decodeFromString<ProductPublishUnscheduledPayload>(payload)
            ProductArchived.TYPE.value -> json.decodeFromString<ProductArchivedPayload>(payload)
            VariantAdded.TYPE.value -> json.decodeFromString<VariantAddedPayload>(payload)
            VariantChanged.TYPE.value -> json.decodeFromString<VariantChangedPayload>(payload)
            VariantDeactivated.TYPE.value -> json.decodeFromString<VariantDeactivatedPayload>(payload)
            VariantReactivated.TYPE.value -> json.decodeFromString<VariantReactivatedPayload>(payload)
            VariantMediaReplaced.TYPE.value -> json.decodeFromString<VariantMediaReplacedPayload>(payload)
            else -> error("Unknown product event type '$eventType'")
        }
        return eventPayload.toDomainEvent()
    }

    // ============ inventory ============

    fun serialize(event: InventoryDomainEvent): String = when (event) {
        is StockAdjusted -> json.encodeToString(StockAdjustedPayload(event))
        is StockReserved -> json.encodeToString(StockReservedPayload(event))
        is ReservationReleased -> json.encodeToString(ReservationReleasedPayload(event))
        is StockShipped -> json.encodeToString(StockShippedPayload(event))
    }

    fun deserializeInventory(payload: String, payloadVersion: Int, eventType: String): InventoryDomainEvent {
        check(payloadVersion == EventVersion.FIRST.value) { "No reader for version $payloadVersion of '$eventType'" }

        val eventPayload: EventPayload<InventoryDomainEvent> = when (eventType) {
            StockAdjusted.TYPE.value -> json.decodeFromString<StockAdjustedPayload>(payload)
            StockReserved.TYPE.value -> json.decodeFromString<StockReservedPayload>(payload)
            ReservationReleased.TYPE.value -> json.decodeFromString<ReservationReleasedPayload>(payload)
            StockShipped.TYPE.value -> json.decodeFromString<StockShippedPayload>(payload)
            else -> error("Unknown inventory event type '$eventType'")
        }
        return eventPayload.toDomainEvent()
    }

    // ============ order ============

    fun serialize(event: OrderDomainEvent): String = when (event) {
        is OrderPlaced -> json.encodeToString(OrderPlacedPayload(event))
        is OrderPaymentFailed -> json.encodeToString(OrderPaymentFailedPayload(event))
        is OrderPaid -> json.encodeToString(OrderPaidPayload(event))
        is OrderExpired -> json.encodeToString(OrderExpiredPayload(event))
        is OrderReleasedFromReservation -> json.encodeToString(OrderReleasedFromReservationPayload(event))
        is OrderPhotosAttached -> json.encodeToString(OrderPhotosAttachedPayload(event))
        is OrderPhotosSent -> json.encodeToString(OrderPhotosSentPayload(event))
        is OrderPhotosApproved -> json.encodeToString(OrderPhotosApprovedPayload(event))
        is OrderShipped -> json.encodeToString(OrderShippedPayload(event))
        is OrderShipmentCorrected -> json.encodeToString(OrderShipmentCorrectedPayload(event))
        is OrderDelivered -> json.encodeToString(OrderDeliveredPayload(event))
        is OrderCancelled -> json.encodeToString(OrderCancelledPayload(event))
        is OrderRefunded -> json.encodeToString(OrderRefundedPayload(event))
        is OrderChangeRequested -> json.encodeToString(OrderChangeRequestedPayload(event))
        is OrderChangeRejected -> json.encodeToString(OrderChangeRejectedPayload(event))
        is OrderLineReplaced -> json.encodeToString(OrderLineReplacedPayload(event))
        is OrderLineRemoved -> json.encodeToString(OrderLineRemovedPayload(event))
        is OrderChangeApplied -> json.encodeToString(OrderChangeAppliedPayload(event))
        is OrderBalancePaid -> json.encodeToString(OrderBalancePaidPayload(event))
        is OrderReturnRequested -> json.encodeToString(OrderReturnRequestedPayload(event))
        is OrderReturnReceived -> json.encodeToString(OrderReturnReceivedPayload(event))
        is OrderReturnEnded -> json.encodeToString(OrderReturnEndedPayload(event))
        is OrderPersonalDataErased -> json.encodeToString(OrderPersonalDataErasedPayload(event))
    }

    fun deserializeOrder(payload: String, payloadVersion: Int, eventType: String): OrderDomainEvent {
        check(payloadVersion == EventVersion.FIRST.value) { "No reader for version $payloadVersion of '$eventType'" }

        val eventPayload: EventPayload<OrderDomainEvent> = when (eventType) {
            OrderPlaced.TYPE.value -> json.decodeFromString<OrderPlacedPayload>(payload)
            OrderPaymentFailed.TYPE.value -> json.decodeFromString<OrderPaymentFailedPayload>(payload)
            OrderPaid.TYPE.value -> json.decodeFromString<OrderPaidPayload>(payload)
            OrderExpired.TYPE.value -> json.decodeFromString<OrderExpiredPayload>(payload)
            OrderReleasedFromReservation.TYPE.value -> json.decodeFromString<OrderReleasedFromReservationPayload>(payload)
            OrderPhotosAttached.TYPE.value -> json.decodeFromString<OrderPhotosAttachedPayload>(payload)
            OrderPhotosSent.TYPE.value -> json.decodeFromString<OrderPhotosSentPayload>(payload)
            OrderPhotosApproved.TYPE.value -> json.decodeFromString<OrderPhotosApprovedPayload>(payload)
            OrderShipped.TYPE.value -> json.decodeFromString<OrderShippedPayload>(payload)
            OrderShipmentCorrected.TYPE.value -> json.decodeFromString<OrderShipmentCorrectedPayload>(payload)
            OrderDelivered.TYPE.value -> json.decodeFromString<OrderDeliveredPayload>(payload)
            OrderCancelled.TYPE.value -> json.decodeFromString<OrderCancelledPayload>(payload)
            OrderRefunded.TYPE.value -> json.decodeFromString<OrderRefundedPayload>(payload)
            OrderChangeRequested.TYPE.value -> json.decodeFromString<OrderChangeRequestedPayload>(payload)
            OrderChangeRejected.TYPE.value -> json.decodeFromString<OrderChangeRejectedPayload>(payload)
            OrderLineReplaced.TYPE.value -> json.decodeFromString<OrderLineReplacedPayload>(payload)
            OrderLineRemoved.TYPE.value -> json.decodeFromString<OrderLineRemovedPayload>(payload)
            OrderChangeApplied.TYPE.value -> json.decodeFromString<OrderChangeAppliedPayload>(payload)
            OrderBalancePaid.TYPE.value -> json.decodeFromString<OrderBalancePaidPayload>(payload)
            OrderReturnRequested.TYPE.value -> json.decodeFromString<OrderReturnRequestedPayload>(payload)
            OrderReturnReceived.TYPE.value -> json.decodeFromString<OrderReturnReceivedPayload>(payload)
            OrderReturnEnded.TYPE.value -> json.decodeFromString<OrderReturnEndedPayload>(payload)
            OrderPersonalDataErased.TYPE.value -> json.decodeFromString<OrderPersonalDataErasedPayload>(payload)
            else -> error("Unknown order event type '$eventType'")
        }
        return eventPayload.toDomainEvent()
    }
}
