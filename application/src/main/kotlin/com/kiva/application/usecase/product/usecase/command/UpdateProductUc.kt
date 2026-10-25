package com.kiva.application.usecase.product.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.product.ProductInput
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.flattenGroups
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.SeoMeta
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.product.ProductDomainService
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductDetails
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.repository.ProductRepository
import java.time.Clock

/**
 * The panel saves the product form (`PUT /admin/products/{productId}`; ADMIN, CATALOG_MANAGER): the whole form
 * replaces the product's content and price. Its `status` and `variants` are not read (the route never passes them):
 * the status and the colors change through their own endpoints.
 *
 * In this order, as the contract says:
 * 1. `If-Match`, when sent, must be one of the three accepted shapes (400 IF_MATCH_INVALID);
 * 2. the product exists (404 PRODUCT_NOT_FOUND; a malformed id is the same);
 * 3. `If-Match` is its current version, or 412 CHANGED_BY_SOMEONE_ELSE with `meta.currentEtag` and nothing saved:
 *    **before** every other rule, so someone with a stale form never gets a message about data that is gone;
 * 4. the form (400, every problem at once); the slug and the product's code never change (400 SLUG_IMMUTABLE /
 *    SKU_IMMUTABLE; a code left out or blank means the same one);
 * 5. not archived (422 PRODUCT_ARCHIVED); category and material exist (400 on their field);
 * 6. save. Two saves of the same version at the same moment take the same next sequence; the event store keeps the
 *    first and refuses the second (ConcurrentChangeException, which the HTTP layer answers 409
 *    CHANGED_BY_SOMEONE_ELSE). Nothing of the second is stored.
 *
 * The same values again record no event and no change log. A change of the price alone logs PRODUCT_PRICE_CHANGED;
 * anything else PRODUCT_UPDATED, with every field that changed (the price too).
 *
 * The form is checked before the transaction like every other; only when it is wrong and an `If-Match` came is the
 * product's version read first (a short read), so a stale form gets its 412 rather than the form's problems.
 */
class UpdateProductUc(
    private val transactionManager: TransactionManager,
    private val productDomainService: ProductDomainService,
    private val productRepository: ProductRepository,
    private val productInput: ProductInput,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<UpdateProductUc.Cmd, ProductId, UpdateProductUc.Failure> {

    private val logger = getLogger()

    /**
     * @property productId the path's `{productId}` as it came.
     * @property ifMatch the `If-Match` header as it came, or null when none was sent.
     */
    data class Cmd(val productId: String, val fields: ProductInput.Fields, val ifMatch: String?, val actor: PanelActor)

    override suspend fun execute(command: Cmd): Result<ProductId, Failure> {
        logger.info("UpdateProductUc.execute product=${command.productId} ifMatch=${command.ifMatch} by=${command.actor.userId.value}")
        val expected = command.ifMatch?.let { versionOf(it) ?: return Result.failure(Failure.IfMatchInvalid) }
        val id = command.productId.toLongOrNull()?.let { ProductId.makeNew(it).getOrNull() } ?: return Result.failure(Failure.NotFound)

        val form = productInput.check(command.fields).unwrapOr { problems ->
            if (expected != null) staleOrMissing(id, expected)?.let { return Result.failure(it) }
            return Result.failure(Failure.InvalidInput(problems.causes.flattenGroups()))
        }

        return transactionManager.inTransaction {
            val product = productRepository.get(id) ?: return@inTransaction Result.failure(Failure.NotFound)
            if (expected != null && expected != product.version) return@inTransaction Result.failure(Failure.ChangedBySomeoneElse(product.version))
            if (form.slug != product.slug) return@inTransaction Result.failure(Failure.SlugImmutable)
            if (form.sku != null && form.sku != product.sku) return@inTransaction Result.failure(Failure.SkuImmutable)
            if (product.status == ProductStatus.ARCHIVED) return@inTransaction Result.failure(Failure.Archived)

            val now = clock.instant()
            val by = command.actor.userId
            val detailsBefore = product.details
            val priceBefore = product.priceTag
            productDomainService.changeDetails(product, form.details, by, now)
                .mapOnFailure {
                    when (it) {
                        is ProductDomainService.ChangeDetailsFailure.CategoryNotFound -> Failure.CategoryNotFound(it.categoryId)
                        is ProductDomainService.ChangeDetailsFailure.MaterialNotFound -> Failure.MaterialNotFound(it.materialId)
                        ProductDomainService.ChangeDetailsFailure.ProductIsArchived -> Failure.Archived
                    }
                }
                .unwrapOr { return@inTransaction Result.failure(it) }
            product.changePrice(form.priceTag, by, now).unwrapOr { error("Checked just above: not archived ($it)") }
            if (product.getRecordedEvents().isEmpty()) return@inTransaction Result.success(id)

            productRepository.save(product)
            val detailsChanged = detailsBefore != product.details
            auditTrail.record(
                AuditEntry(
                    at = now,
                    actor = command.actor,
                    action = if (detailsChanged) AuditAction.PRODUCT_UPDATED else AuditAction.PRODUCT_PRICE_CHANGED,
                    entityType = AuditEntityType.PRODUCT,
                    entityId = id.value.toString(),
                    entityLabel = product.details.name.value,
                    changes = changesOf(detailsBefore, product.details, priceBefore, product.priceTag),
                ),
            )
            Result.success(id)
        }
    }

    /** Only for a wrong form: the 404 or 412 that comes before the form's problems, or null when it is current. */
    private suspend fun staleOrMissing(id: ProductId, expected: Long): Failure? =
        transactionManager.inTransaction {
            val product = productRepository.get(id)
            Result.success(
                when {
                    product == null -> Failure.NotFound
                    product.version != expected -> Failure.ChangedBySomeoneElse(product.version)
                    else -> null
                },
            )
        }.getOrNull()

    private fun changesOf(before: ProductDetails, after: ProductDetails, priceBefore: PriceTag, priceAfter: PriceTag) = AuditDiff.build {
        field("name", before.name.value, after.name.value)
        field("categoryId", before.categoryId.value, after.categoryId.value)
        field("bagType", before.bagType, after.bagType)
        field("materialId", before.materialId.value, after.materialId.value)
        field("materialDescription", before.materialDescription?.value, after.materialDescription?.value)
        field("price", priceBefore.price.toman, priceAfter.price.toman)
        field("compareAtPrice", priceBefore.compareAtPrice?.toman, priceAfter.compareAtPrice?.toman)
        field("shortDescription", before.shortDescription?.value, after.shortDescription?.value)
        longText("description", before.description?.value, after.description?.value)
        list("highlights", before.highlights.items, after.highlights.items)
        // The specs are a big object: only that they changed.
        longText("specs", before.specs.toString(), after.specs.toString())
        list("tags", before.tags.items, after.tags.items)
        field("newUntil", before.newUntil, after.newUntil)
        field("maxPerOrder", before.maxPerOrder.value, after.maxPerOrder.value)
        field("returnable", before.returnable, after.returnable)
        field("reservable", before.reservable, after.reservable)
        field("sortPriority", before.sortPriority, after.sortPriority)
        json("seo", seoOf(before.seo), seoOf(after.seo))
    }

    private fun seoOf(seo: SeoMeta) = mapOf("title" to seo.title, "description" to seo.description, "noIndex" to seo.noIndex)

    companion object {
        private val IF_MATCH = Regex("""^\s*(?:W/)?"?(\d{1,18})"?\s*$""")

        /** `W/"12"`, `"12"` and `12` are the same version; anything else is null. */
        fun versionOf(ifMatch: String): Long? {
            val match = IF_MATCH.matchEntire(ifMatch) ?: return null
            val raw = match.groupValues[1]
            // Quotes come in pairs, and the weak mark only with them.
            val quotes = ifMatch.count { it == '"' }
            if (quotes != 0 && quotes != 2) return null
            if (ifMatch.trim().startsWith("W/") && quotes == 0) return null
            return raw.toLongOrNull()
        }
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, causes, meta) {

        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        /** `If-Match` in none of the accepted shapes; a field problem on the header, so the client sees where. */
        data object IfMatchInvalid : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = listOf(IfMatchShape))

        data object NotFound : Failure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)

        /** The form was filled from another version; [currentVersion] is the one to reload. */
        data class ChangedBySomeoneElse(val currentVersion: Long) :
            Failure("CHANGED_BY_SOMEONE_ELSE", FailureKind.PRECONDITION_FAILED, meta = mapOf("currentEtag" to "W/\"$currentVersion\""))

        data object SlugImmutable : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = listOf(Immutable("SLUG_IMMUTABLE", "slug")))

        data object SkuImmutable : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = listOf(Immutable("SKU_IMMUTABLE", "sku")))

        data class CategoryNotFound(val categoryId: CategoryId) :
            Failure("CATEGORY_NOT_FOUND", FailureKind.INVALID, "categoryId", meta = mapOf("categoryId" to categoryId.value))

        data class MaterialNotFound(val materialId: MaterialId) :
            Failure("MATERIAL_NOT_FOUND", FailureKind.INVALID, "materialId", meta = mapOf("materialId" to materialId.value))

        data object Archived : Failure("PRODUCT_ARCHIVED", FailureKind.BUSINESS_RULE)
    }

    private data object IfMatchShape : ResultFailure("IF_MATCH_INVALID", FailureKind.INVALID, "If-Match")

    private class Immutable(code: String, field: String) : ResultFailure(code, FailureKind.INVALID, field)
}
