package com.kiva.application.usecase.product.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.product.MomentField
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.bind
import com.kiva.common.railway.forField
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.railway.mapOnSuccess
import com.kiva.common.railway.required
import com.kiva.common.railway.unwrapOr
import com.kiva.common.railway.zip
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.repository.ProductRepository
import java.time.Clock
import java.time.Instant

/**
 * The panel publishes, takes out, archives or schedules a product (`PATCH /admin/products/{productId}/status`; ADMIN,
 * CATALOG_MANAGER), by the contract's table (roadmap section 6.6):
 *
 * | now | asked | publishAt | result |
 * |---|---|---|---|
 * | DRAFT | ACTIVE | none, now or past | published (a schedule is dropped with it); no active color is 422 NO_ACTIVE_VARIANT |
 * | DRAFT | ACTIVE | future | scheduled, still a draft; no active color 422, more than 365 days 400 PUBLISH_AT_TOO_FAR |
 * | DRAFT | DRAFT | none | a schedule is called off; without one 422 PRODUCT_ALREADY_DRAFT |
 * | ACTIVE | ACTIVE | none | 422 PRODUCT_ALREADY_ACTIVE |
 * | ACTIVE | DRAFT | none | taken out of the shop |
 * | DRAFT or ACTIVE | ARCHIVED | none | archived |
 * | ARCHIVED | DRAFT or ACTIVE | — | 422 INVALID_STATUS_TRANSITION |
 * | ARCHIVED | ARCHIVED | — | 422 PRODUCT_ALREADY_ARCHIVED |
 * | any | DRAFT or ARCHIVED, or a product in the shop | given | 400 PUBLISH_AT_NOT_ALLOWED on publishAt |
 *
 * In this order: the form (400), the product (404 PRODUCT_NOT_FOUND, a malformed id too), the table, the save, one
 * change log row. Saving the same product at the same moment as someone else is refused by the event store
 * (ConcurrentChangeException, 409).
 */
class ChangeProductStatusUc(
    private val transactionManager: TransactionManager,
    private val productRepository: ProductRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<ChangeProductStatusUc.Cmd, ProductId, ChangeProductStatusUc.Failure> {

    private val logger = getLogger()

    /**
     * @property productId the path's `{productId}` as it came.
     * @property publishAt as the body had it (ISO-8601 with its offset), null when left out.
     */
    data class Cmd(val productId: String, val status: String?, val publishAt: String?, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<ProductId, Failure> {
        logger.info("ChangeProductStatusUc.execute product=${command.productId} status=${command.status} publishAt=${command.publishAt} by=${command.actor.userId.value}")
        val (asked, publishAt) = Result.zip(
            command.status.required().bind { ProductStatus.makeNew(it) }.forField("status"),
            MomentField.makeNew(command.publishAt).forField("publishAt"),
            Failure::InvalidInput,
        ) { status, at -> status to at }.unwrapOr { return Result.failure(it) }
        if (publishAt != null && asked != ProductStatus.ACTIVE) return Result.failure(Failure.PublishAtNotAllowed)
        val id = command.productId.toLongOrNull()?.let { ProductId.makeNew(it).getOrNull() } ?: return Result.failure(Failure.NotFound)

        return transactionManager.inTransaction {
            val product = productRepository.get(id) ?: return@inTransaction Result.failure(Failure.NotFound)
            val before = product.status to product.publishAt
            val now = clock.instant()
            val by = command.actor.userId

            val action = move(product, asked, publishAt, by, now).unwrapOr { return@inTransaction Result.failure(it) }
            if (product.getRecordedEvents().isEmpty()) return@inTransaction Result.success(id)

            productRepository.save(product)
            auditTrail.record(
                AuditEntry(
                    at = now,
                    actor = command.actor,
                    action = action,
                    entityType = AuditEntityType.PRODUCT,
                    entityId = id.value.toString(),
                    entityLabel = product.details.name.value,
                    changes = AuditDiff.build {
                        field("status", before.first, product.status)
                        field("publishAt", before.second, product.publishAt)
                    },
                ),
            )
            Result.success(id)
        }
    }

    /** One row of the table: the change made, as the change log names it, or the row's refusal. */
    private fun move(product: Product, asked: ProductStatus, publishAt: Instant?, by: UserId, now: Instant): Result<AuditAction, Failure> {
        val from = product.status
        if (from == ProductStatus.ARCHIVED) {
            return Result.failure(if (asked == ProductStatus.ARCHIVED) Failure.AlreadyArchived else Failure.InvalidTransition(from, asked))
        }
        return when (asked) {
            ProductStatus.ACTIVE -> when {
                from == ProductStatus.ACTIVE && publishAt != null -> Result.failure(Failure.PublishAtNotAllowed)
                from == ProductStatus.ACTIVE -> Result.failure(Failure.AlreadyActive)
                publishAt != null && publishAt.isAfter(now) -> product.schedulePublish(publishAt, by, now)
                    .mapOnFailure {
                        when (it) {
                            Product.SchedulePublishFailure.NoActiveVariant -> Failure.NoActiveVariant
                            Product.SchedulePublishFailure.PublishAtTooFar -> Failure.PublishAtTooFar
                            Product.SchedulePublishFailure.PublishAtNotAllowed, Product.SchedulePublishFailure.ProductIsArchived ->
                                error("A draft can be scheduled: $it")
                        }
                    }
                    .mapOnSuccess { AuditAction.PRODUCT_PUBLISH_SCHEDULED }
                else -> product.publish(by, now)
                    .mapOnFailure {
                        when (it) {
                            Product.PublishFailure.NoActiveVariant -> Failure.NoActiveVariant
                            Product.PublishFailure.AlreadyActive, is Product.PublishFailure.InvalidStatusTransition -> error("A draft can be published: $it")
                        }
                    }
                    .mapOnSuccess { AuditAction.PRODUCT_PUBLISHED }
            }
            ProductStatus.DRAFT -> if (from == ProductStatus.ACTIVE) {
                product.unpublish(by, now).unwrapOr { error("A product in the shop can be taken out: $it") }
                Result.success(AuditAction.PRODUCT_UNPUBLISHED)
            } else {
                product.unschedulePublish(by, now).mapOnSuccess { AuditAction.PRODUCT_PUBLISH_UNSCHEDULED }.mapOnFailure { Failure.AlreadyDraft }
            }
            ProductStatus.ARCHIVED -> {
                product.archive(by, now).unwrapOr { error("Not archived yet: $it") }
                Result.success(AuditAction.PRODUCT_ARCHIVED)
            }
        }
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, causes, meta) {

        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        data object PublishAtNotAllowed : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = listOf(Field("PUBLISH_AT_NOT_ALLOWED")))

        data object PublishAtTooFar :
            Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = listOf(Field("PUBLISH_AT_TOO_FAR", mapOf("maxDays" to Product.MAX_SCHEDULE_AHEAD.toDays().toInt()))))

        data object NotFound : Failure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)

        data object NoActiveVariant : Failure("NO_ACTIVE_VARIANT", FailureKind.BUSINESS_RULE)

        data object AlreadyActive : Failure("PRODUCT_ALREADY_ACTIVE", FailureKind.BUSINESS_RULE)

        data object AlreadyDraft : Failure("PRODUCT_ALREADY_DRAFT", FailureKind.BUSINESS_RULE)

        data object AlreadyArchived : Failure("PRODUCT_ALREADY_ARCHIVED", FailureKind.BUSINESS_RULE)

        data class InvalidTransition(val from: ProductStatus, val to: ProductStatus) :
            Failure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, meta = mapOf("from" to from.name, "to" to to.name))
    }

    /** A problem of the `publishAt` field. */
    private class Field(code: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, "publishAt", meta = meta)
}
