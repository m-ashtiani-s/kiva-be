package com.kiva.application.usecase.catalog.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.catalog.CategoryFields
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.repository.CategoryIdGenerator
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import java.time.Clock

/**
 * A new category from the panel (`POST /admin/categories`; ADMIN, CATALOG_MANAGER).
 *
 * The form first, every problem at once (400). Then one transaction, in the contract's order: the icon's color must be
 * in the palette (400 COLOR_NOT_FOUND on `iconColorKey`), the photo must be a CATEGORY photo (400 MEDIA_* on
 * `imageMediaId`), the slug must be free (409 DUPLICATE_SLUG). Two requests with the same slug at the same moment both
 * pass that check; the unique index refuses the second, which gets the same 409. The photo is marked used and the
 * change log gets one CATEGORY_CREATED row.
 *
 * @return the new category's id; the route answers with the category as the panel lists it.
 */
class CreateCategoryUc(
    private val transactionManager: TransactionManager,
    private val categoryRepository: CategoryRepository,
    private val categoryIdGenerator: CategoryIdGenerator,
    private val colorRepository: ColorRepository,
    private val mediaAttachments: MediaAttachments,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<CreateCategoryUc.Cmd, CategoryId, CreateCategoryUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val fields: CategoryFields, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<CategoryId, Failure> {
        logger.info("CreateCategoryUc.execute slug=${command.fields.slug} by=${command.actor.userId.value}")
        val form = command.fields.check().unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }

        return transactionManager.inTransaction {
            colorRepository.findByKey(form.icon.colorKey) ?: return@inTransaction Result.failure(Failure.ColorNotFound(form.icon.colorKey))
            val image = form.imageMediaId?.let { MediaAttachments.Wanted(it, "imageMediaId") }
            mediaAttachments.check(listOfNotNull(image), MediaPurpose.CATEGORY, setOf(MediaType.IMAGE))
                .unwrapOr { return@inTransaction Result.failure(Failure.InvalidInput(it.causes)) }
            if (categoryRepository.findBySlug(form.slug) != null) return@inTransaction Result.failure(Failure.DuplicateSlug(form.slug))

            val now = clock.instant()
            val category = Category.create(
                categoryIdGenerator.nextCategoryId(), form.slug, form.name, form.description, form.icon, form.imageMediaId, form.sortOrder,
                form.showInMenu, form.seo,
            )
            try {
                categoryRepository.insert(category)
            } catch (e: UniqueTakenException) {
                logger.info("CreateCategoryUc: ${e.message} between the check and the save")
                return@inTransaction Result.failure(Failure.DuplicateSlug(form.slug))
            }
            mediaAttachments.attach(listOfNotNull(form.imageMediaId), now)
            auditTrail.record(
                AuditEntry(now, command.actor, AuditAction.CATEGORY_CREATED, AuditEntityType.CATEGORY, category.id.value.toString(), category.name.value, null),
            )
            Result.success(category.id)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, causes, meta) {

        /** One or more fields are wrong; each cause names its field (400 VALIDATION_ERROR). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        data class ColorNotFound(val colorKey: ColorKey) :
            Failure("COLOR_NOT_FOUND", FailureKind.INVALID, "iconColorKey", meta = mapOf("colorKey" to colorKey.value))

        data class DuplicateSlug(val slug: Slug) : Failure("DUPLICATE_SLUG", FailureKind.CONFLICT, "slug", meta = mapOf("slug" to slug.value))
    }
}
