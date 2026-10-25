package com.kiva.application.usecase.catalog.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.repository.StillReferencedException
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.testimonial.repository.TestimonialRepository
import java.time.Clock

/**
 * The panel removes a palette color (`DELETE /admin/colors/{colorKey}`; ADMIN, CATALOG_MANAGER), only while nothing
 * uses it: a variant of any product (active or not, any status), a category's icon, or (phase 11) a testimonial.
 * Otherwise 409 IN_USE with `meta.productCount`, `meta.categoryCount` and `meta.testimonialCount`. The database's
 * foreign keys are the second wall for a use added in between.
 *
 * A malformed key is the same 404 as one nobody has. A removal logs one COLOR_DELETED row.
 */
class DeleteColorUc(
    private val transactionManager: TransactionManager,
    private val colorRepository: ColorRepository,
    private val categoryRepository: CategoryRepository,
    private val productQmRepository: ProductQmRepository,
    private val testimonialRepository: TestimonialRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<DeleteColorUc.Cmd, Unit, DeleteColorUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val colorKey: String, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("DeleteColorUc.execute color=${command.colorKey} by=${command.actor.userId.value}")
        val key = ColorKey.makeNew(command.colorKey).getOrNull() ?: return Result.failure(Failure.NotFound)

        return transactionManager.inTransaction {
            val color = colorRepository.findByKey(key) ?: return@inTransaction Result.failure(Failure.NotFound)
            val products = productQmRepository.countAllWithColor(key)
            val categories = categoryRepository.countWithIcon(key)
            val testimonials = testimonialRepository.countWithColor(key)
            if (products + categories + testimonials > 0) return@inTransaction Result.failure(Failure.InUse(products, categories, testimonials))
            try {
                colorRepository.delete(key)
            } catch (e: StillReferencedException) {
                logger.info("DeleteColorUc: ${e.message}; something got it after the count")
                return@inTransaction Result.failure(Failure.InUse(maxOf(products, 1), categories, testimonials))
            }
            auditTrail.record(AuditEntry(clock.instant(), command.actor, AuditAction.COLOR_DELETED, AuditEntityType.COLOR, key.value, color.name.value, null))
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)

        data class InUse(val productCount: Int, val categoryCount: Int, val testimonialCount: Int) : Failure(
            "IN_USE", FailureKind.CONFLICT,
            mapOf("productCount" to productCount, "categoryCount" to categoryCount, "testimonialCount" to testimonialCount),
        )
    }
}
