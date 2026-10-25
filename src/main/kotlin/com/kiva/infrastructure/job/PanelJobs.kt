package com.kiva.infrastructure.job

import com.kiva.application.usecase.audit.usecase.command.DeleteOldAuditEntriesUc
import com.kiva.application.usecase.media.usecase.command.DeleteUnattachedMediaUc
import com.kiva.application.usecase.media.usecase.query.FindUnattachedMediaUc
import com.kiva.application.usecase.order.usecase.command.ApproveSilentPhotosUc
import com.kiva.application.usecase.order.usecase.query.FindSilentPhotoOrdersUc
import com.kiva.application.usecase.product.usecase.command.PublishScheduledProductUc
import com.kiva.application.usecase.product.usecase.query.FindDueScheduledProductsUc
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import java.time.Duration
import java.time.LocalTime
import kotlin.coroutines.cancellation.CancellationException

/**
 * The jobs of the panel (roadmap section 25), each run by the system and never written to the change log. A candidate
 * that breaks is logged and the others go on.
 *
 * | job | when |
 * |---|---|
 * | removing the change log's rows past 90 days | daily, 03:30 Tehran |
 * | removing uploads nothing used within 48 hours | daily, 04:30 Tehran |
 * | publishing the drafts whose scheduled moment has come | every minute |
 * | approving the photos nobody answered by their deadline | every 10 minutes |
 */
class PanelJobs(
    private val deleteOldAuditEntries: DeleteOldAuditEntriesUc,
    private val findUnattachedMedia: FindUnattachedMediaUc,
    private val deleteUnattachedMedia: DeleteUnattachedMediaUc,
    private val findDueScheduledProducts: FindDueScheduledProductsUc,
    private val publishScheduledProduct: PublishScheduledProductUc,
    private val findSilentPhotoOrders: FindSilentPhotoOrdersUc,
    private val approveSilentPhoto: ApproveSilentPhotosUc,
) {
    private val logger = getLogger()

    fun all(): List<ScheduledJob> = listOf(
        ScheduledJob("delete-old-audit-entries", Schedule.DailyAt(LocalTime.of(3, 30), ShopCalendar.ZONE)) {
            deleteOldAuditEntries.execute(DeleteOldAuditEntriesUc.Cmd)
        },
        ScheduledJob("delete-unattached-media", Schedule.DailyAt(LocalTime.of(4, 30), ShopCalendar.ZONE)) { deleteUnattachedMedia() },
        ScheduledJob("publish-scheduled-products", Schedule.Every(Duration.ofMinutes(1))) { publishScheduledProducts() },
        ScheduledJob("approve-silent-photos", Schedule.Every(Duration.ofMinutes(10))) { approveSilentPhotos() },
    )

    /** One run of the publish job: every due draft, each by itself (one that breaks doesn't hold the others). */
    suspend fun publishScheduledProducts() {
        val due = findDueScheduledProducts.execute(FindDueScheduledProductsUc.Qry).unwrapOr { it }
        due.forEach { productId -> each("product ${productId.value}") { publishScheduledProduct.execute(PublishScheduledProductUc.Cmd(productId)) } }
    }

    /** One run of the approve-silent-photos job: every order past its deadline, each by itself. */
    suspend fun approveSilentPhotos() {
        val silent = findSilentPhotoOrders.execute(FindSilentPhotoOrdersUc.Qry).unwrapOr { it }
        silent.forEach { orderId -> each("order ${orderId.value}") { approveSilentPhoto.execute(ApproveSilentPhotosUc.Cmd(orderId)) } }
    }

    /**
     * One run of the media clean-up: every upload nothing used, each removed by itself, batch after batch, at most
     * [MAX_MEDIA_BATCHES] of them: candidates that keep failing must not hold the job for ever.
     */
    suspend fun deleteUnattachedMedia() {
        repeat(MAX_MEDIA_BATCHES) {
            val candidates = findUnattachedMedia.execute(FindUnattachedMediaUc.Qry()).unwrapOr { it }
            candidates.forEach { mediaId -> each("media ${mediaId.value}") { deleteUnattachedMedia.execute(DeleteUnattachedMediaUc.Cmd(mediaId)) } }
            if (candidates.size < FindUnattachedMediaUc.BATCH) return
        }
    }

    private suspend fun each(what: String, work: suspend () -> Unit) {
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("PanelJobs: $what failed; the next run tries again", e)
        }
    }

    private companion object {
        const val MAX_MEDIA_BATCHES = 50
    }
}
