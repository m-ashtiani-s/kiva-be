package com.kiva.application.usecase.product.usecase.command

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.repository.ProductRepository
import java.time.Clock

/**
 * The publish job's work on one candidate (FindDueScheduledProductsUc), as [UserId.SYSTEM] and without a change log row
 * (the system's work never writes one).
 *
 * The product is read again: if it is no longer a draft, no longer scheduled, or its moment has not come, nothing
 * happens (someone changed it since it was found). Otherwise it is published, at the moment the job runs. A draft that
 * lost its last active color since it was scheduled can't go into the shop: its schedule is dropped, with a WARN, so
 * the panel shows a plain draft and staff decide.
 *
 * Two runs on one product at the same moment (two servers, or a slow run overlapping the next) both read the draft;
 * the event store keeps the first publish and refuses the second (ConcurrentChangeException), which is taken here as
 * "done by the other run": one ProductPublished, never two.
 */
class PublishScheduledProductUc(
    private val transactionManager: TransactionManager,
    private val productRepository: ProductRepository,
    private val clock: Clock,
) : CommandUseCase<PublishScheduledProductUc.Cmd, PublishScheduledProductUc.Outcome, Nothing> {

    private val logger = getLogger()

    data class Cmd(val productId: ProductId)

    enum class Outcome {
        PUBLISHED,

        /** It could not go into the shop (no active color): its schedule was dropped. */
        SCHEDULE_DROPPED,

        /** Not due any more, or done by another run. */
        NOTHING,
    }

    override suspend fun execute(command: Cmd): Result<Outcome, Nothing> = try {
        transactionManager.inTransaction<Outcome, Nothing> { Result.success(publishOnce(command.productId)) }
    } catch (e: ConcurrentChangeException) {
        logger.info("PublishScheduledProductUc: product ${command.productId.value} was changed by another run meanwhile; nothing more to do")
        Result.success(Outcome.NOTHING)
    }

    private fun publishOnce(id: ProductId): Outcome {
        val product = productRepository.get(id) ?: return Outcome.NOTHING
        val now = clock.instant()
        val publishAt = product.publishAt
        if (product.status != ProductStatus.DRAFT || publishAt == null || publishAt.isAfter(now)) return Outcome.NOTHING

        val outcome = when (product.publish(UserId.SYSTEM, now).failureOrNull()) {
            null -> Outcome.PUBLISHED
            Product.PublishFailure.NoActiveVariant -> {
                product.unschedulePublish(UserId.SYSTEM, now).getOrNull() ?: error("Scheduled just above")
                logger.warn("product ${id.value} was due to be published at $publishAt but has no active color; its schedule was dropped")
                Outcome.SCHEDULE_DROPPED
            }
            else -> error("A draft can be published")
        }
        productRepository.save(product)
        logger.info("PublishScheduledProductUc: product ${id.value} $outcome (due $publishAt)")
        return outcome
    }
}
