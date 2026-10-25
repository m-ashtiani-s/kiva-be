package com.kiva.application.dto

import com.kiva.application.repository.FeedbackDecision
import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.ResolutionDecision
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.order.model.valueobject.ChangeType
import com.kiva.domain.aggregate.order.model.valueobject.OrderPhoto
import com.kiva.domain.aggregate.order.model.valueobject.PhotoStatus
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import java.time.Instant

/**
 * The photos before shipping of an order (the contract's PreShipmentMedia), without the Persian words: where they are,
 * the batch sent with its files, the operator's [draft] (the panel's only: the customer's answers never carry it), the
 * customer's last answer and until when they may give one.
 *
 * @property sentOn when the last batch was sent.
 */
data class PreShipmentMediaDto(
    val status: PhotoStatus,
    val sentOn: Instant?,
    val sent: List<Photo>,
    val draft: List<Photo>,
    val feedback: Feedback?,
    val feedbackDeadline: Instant?,
) {
    /**
     * One photo or video: the upload, the order line it shows (`orderItemId`), its view, and when it was taken
     * ([capturedAt], the upload's time).
     */
    data class Photo(val media: UploadedMediaDto, val lineId: Int?, val view: MediaView?, val capturedAt: Instant)

    /**
     * The customer's last answer: «همونه!», or a change request as they asked it ([changeType], [lineId],
     * [desiredVariantId], [note]) and how staff resolved it, once they did ([resolution]). [note] is personal.
     */
    data class Feedback(
        val decision: FeedbackDecision,
        val at: Instant,
        val changeType: ChangeType?,
        val lineId: Int?,
        val desiredVariantId: Long?,
        val note: String?,
        val resolution: Resolution?,
    ) {
        override fun toString(): String = "Feedback(decision=$decision, changeType=$changeType, at=$at)"
    }

    /** How staff resolved a change request; [variantId] is the color put in (COLOR, MODEL); [note] is for the customer. */
    data class Resolution(val decision: ResolutionDecision, val variantId: Long?, val note: String?, val at: Instant) {
        override fun toString(): String = "Resolution(decision=$decision, variantId=$variantId, at=$at)"
    }

    companion object {
        /** [order]'s photos, their files taken from [media] (a file gone since is left out). */
        fun of(order: OrderQm, media: Map<MediaId, MediaAsset>): PreShipmentMediaDto = PreShipmentMediaDto(
            status = order.photoStatus,
            sentOn = order.photosSentOn,
            sent = photosOf(order.photosSent, media),
            draft = photosOf(order.photoDraft, media),
            feedback = order.feedback?.let { answer ->
                Feedback(
                    answer.decision, answer.at, answer.changeType, answer.lineId, answer.desiredVariantId, answer.note,
                    answer.resolution?.let { Resolution(it.decision, it.variantId, it.note, it.at) },
                )
            },
            feedbackDeadline = order.feedbackDeadline,
        )

        private fun photosOf(photos: List<OrderPhoto>, media: Map<MediaId, MediaAsset>): List<Photo> =
            photos.sortedBy { it.sortOrder }.mapNotNull { photo ->
                media[photo.mediaId]?.let { Photo(UploadedMediaDto.from(it), photo.lineId, photo.view, it.uploadedOn) }
            }
    }
}
