package com.kiva.application.usecase.catalog.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.repository.ProductQmRepository.NamedInSearch
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
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
import com.kiva.common.valueobject.SeoMeta
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import java.time.Clock

/**
 * The panel edits a category (`PUT /admin/categories/{categoryId}`; ADMIN, CATALOG_MANAGER): the whole form replaces
 * the old values.
 *
 * The form first (400, all at once); a malformed id is the same 404 as one nobody has. Then one transaction: the
 * category exists (404 NOT_FOUND), the icon's color is in the palette, the photo is a CATEGORY photo, the slug is
 * nobody else's (409 DUPLICATE_SLUG). The slug may change: the read model follows it (ON UPDATE CASCADE). A new name
 * or slug is written into the products' search text in the same transaction (refreshSearchText), so a search for the
 * new name finds them the moment the change is committed, and never misses them if anything fails.
 *
 * The same values again change nothing and log nothing; a change logs one CATEGORY_UPDATED row with the fields that
 * changed.
 */
class UpdateCategoryUc(
    private val transactionManager: TransactionManager,
    private val categoryRepository: CategoryRepository,
    private val colorRepository: ColorRepository,
    private val productQmRepository: ProductQmRepository,
    private val mediaAttachments: MediaAttachments,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<UpdateCategoryUc.Cmd, CategoryId, UpdateCategoryUc.Failure> {

    private val logger = getLogger()

    /** @property categoryId the path's `{categoryId}` as it came. */
    data class Cmd(val categoryId: String, val fields: CategoryFields, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<CategoryId, Failure> {
        logger.info("UpdateCategoryUc.execute category=${command.categoryId} slug=${command.fields.slug} by=${command.actor.userId.value}")
        val form = command.fields.check().unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }
        val id = command.categoryId.toLongOrNull()?.let { CategoryId.makeNew(it).getOrNull() } ?: return Result.failure(Failure.NotFound)

        return transactionManager.inTransaction {
            val before = categoryRepository.findById(id) ?: return@inTransaction Result.failure(Failure.NotFound)
            colorRepository.findByKey(form.icon.colorKey) ?: return@inTransaction Result.failure(Failure.ColorNotFound(form.icon.colorKey))
            val image = form.imageMediaId?.takeIf { it != before.imageMediaId }?.let { MediaAttachments.Wanted(it, "imageMediaId") }
            mediaAttachments.check(listOfNotNull(image), MediaPurpose.CATEGORY, setOf(MediaType.IMAGE))
                .unwrapOr { return@inTransaction Result.failure(Failure.InvalidInput(it.causes)) }
            val holder = categoryRepository.findBySlug(form.slug)
            if (holder != null && holder.id != id) return@inTransaction Result.failure(Failure.DuplicateSlug(form.slug))

            val after = before.change(form.slug, form.name, form.description, form.icon, form.imageMediaId, form.sortOrder, form.showInMenu, form.seo)
            if (after.sameValuesAs(before)) return@inTransaction Result.success(id)

            val now = clock.instant()
            try {
                categoryRepository.update(after)
            } catch (e: UniqueTakenException) {
                logger.info("UpdateCategoryUc: ${e.message} between the check and the save")
                return@inTransaction Result.failure(Failure.DuplicateSlug(form.slug))
            }
            if (after.name != before.name || after.slug != before.slug) {
                val refreshed = productQmRepository.refreshSearchText(NamedInSearch.Category(after.slug))
                logger.info("UpdateCategoryUc: search text of $refreshed product(s) written again")
            }
            mediaAttachments.attach(listOfNotNull(after.imageMediaId), now)
            auditTrail.record(
                AuditEntry(now, command.actor, AuditAction.CATEGORY_UPDATED, AuditEntityType.CATEGORY, id.value.toString(), after.name.value, changesOf(before, after)),
            )
            Result.success(id)
        }
    }

    private fun changesOf(before: Category, after: Category) = AuditDiff.build {
        field("name", before.name.value, after.name.value)
        field("slug", before.slug.value, after.slug.value)
        field("description", before.description?.value, after.description?.value)
        field("iconBagType", before.icon.bagType, after.icon.bagType)
        field("iconColorKey", before.icon.colorKey.value, after.icon.colorKey.value)
        field("imageMediaId", before.imageMediaId?.value, after.imageMediaId?.value)
        field("sortOrder", before.sortOrder, after.sortOrder)
        field("showInMenu", before.showInMenu, after.showInMenu)
        json("seo", seoOf(before.seo), seoOf(after.seo))
    }

    private fun seoOf(seo: SeoMeta) = mapOf("title" to seo.title, "description" to seo.description, "noIndex" to seo.noIndex)

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, causes, meta) {

        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)

        data class ColorNotFound(val colorKey: ColorKey) :
            Failure("COLOR_NOT_FOUND", FailureKind.INVALID, "iconColorKey", meta = mapOf("colorKey" to colorKey.value))

        data class DuplicateSlug(val slug: Slug) : Failure("DUPLICATE_SLUG", FailureKind.CONFLICT, "slug", meta = mapOf("slug" to slug.value))
    }
}
