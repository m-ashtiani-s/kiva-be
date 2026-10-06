package com.kiva.domain.aggregate.product.entity

import com.kiva.common.event.EventSourceEntity
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.entity.event.ProductArchived
import com.kiva.domain.aggregate.product.entity.event.ProductCreated
import com.kiva.domain.aggregate.product.entity.event.ProductDetailsChanged
import com.kiva.domain.aggregate.product.entity.event.ProductDomainEvent
import com.kiva.domain.aggregate.product.entity.event.ProductPriceChanged
import com.kiva.domain.aggregate.product.entity.event.ProductPublished
import com.kiva.domain.aggregate.product.entity.event.ProductUnpublished
import com.kiva.domain.aggregate.product.entity.event.VariantAdded
import com.kiva.domain.aggregate.product.entity.event.VariantChanged
import com.kiva.domain.aggregate.product.entity.event.VariantDeactivated
import com.kiva.domain.aggregate.product.entity.event.VariantMediaReplaced
import com.kiva.domain.aggregate.product.entity.event.VariantReactivated
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductDetails
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus.ACTIVE
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus.ARCHIVED
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus.DRAFT
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant
import kotlin.properties.Delegates

/**
 * A bag in the shop: what it is, what it costs, its colors, and whether shoppers can see it.
 *
 * Event-sourced: it is stored as the list of what happened to it and rebuilt by replaying that list, because its
 * life (draft, published, archived) and its price history matter. Stock is not here: it is the Inventory aggregate,
 * one per color, so a purchase never competes with an edit of the description.
 *
 * Rules it keeps by itself:
 * - every product starts as a DRAFT; DRAFT and ACTIVE go back and forth; either can be ARCHIVED, which is final and
 *   can't be edited any more
 * - it is published only with at least one active color, and while published it keeps at least one
 * - each color appears once, and among the active colors exactly one is the default
 * - a price tag's crossed-out price is above its price ([PriceTag]), for the product and for a color with its own
 *
 * Slug and SKU being unique across products is not its job: it can't see other products. The use case checks the
 * read model and a unique index has the last word.
 */
class Product private constructor() : EventSourceEntity<ProductDomainEvent>() {

    // Set by apply(). Kotlin doesn't allow lateinit on value classes, so those use Delegates.notNull().
    var id: ProductId by Delegates.notNull()
        private set
    var slug: Slug by Delegates.notNull()
        private set
    var sku: Sku by Delegates.notNull()
        private set
    lateinit var details: ProductDetails
        private set
    lateinit var priceTag: PriceTag
        private set
    var status: ProductStatus = DRAFT
        private set

    private val variantList = mutableListOf<Variant>()

    /** The colors, in the order they were added (deactivated ones included). */
    val variants: List<Variant> get() = variantList.toList()

    /** Loading: rebuild from the stored events. */
    constructor(events: List<ProductDomainEvent>) : this() {
        applyEventStream(events)
    }

    /** Something new: the first event of a product. */
    private constructor(
        id: ProductId,
        slug: Slug,
        sku: Sku,
        details: ProductDetails,
        priceTag: PriceTag,
        by: UserId,
        now: Instant,
    ) : this() {
        raiseEvent(ProductCreated(id, nextSequence(), now, by, slug, sku, details, priceTag))
    }

    companion object {
        /**
         * A new product, always a DRAFT and without colors (add them with [addVariant]). Without a typed SKU it gets one
         * from its id (`KV-1001`). It can't fail: every part is already a valid value.
         */
        fun create(
            id: ProductId,
            slug: Slug,
            sku: Sku?,
            details: ProductDetails,
            priceTag: PriceTag,
            by: UserId,
            now: Instant,
        ): Result<Product, Nothing> = Result.success(Product(id, slug, sku ?: Sku.forProduct(id), details, priceTag, by, now))
    }

    fun activeVariants(): List<Variant> = variantList.filter { it.isActive }

    /** The color a product card shows first; null only while the product has no active color. */
    fun defaultVariant(): Variant? = variantList.firstOrNull { it.isActive && it.isDefault }

    fun findVariant(id: VariantId): Variant? = variantList.firstOrNull { it.id == id }

    private fun canBeEdited(): Boolean = status != ARCHIVED

    /** The color that takes over as the default: the first active one in display order, [except] left out. */
    private fun nextDefaultCandidate(except: VariantId): Variant? =
        activeVariants().filter { it.id != except }.minWithOrNull(compareBy<Variant>({ it.sortOrder }, { it.id.value }))

    // ============ content and price ============

    /** Saving the form with the same content changes nothing and records nothing. */
    fun changeDetails(details: ProductDetails, by: UserId, now: Instant): Result<Product, ChangeDetailsFailure> {
        if (!canBeEdited()) return Result.failure(ChangeDetailsFailure.ProductIsArchived)
        if (details == this.details) return Result.success(this)

        raiseEvent(ProductDetailsChanged(id, nextSequence(), now, by, details))
        return Result.success(this)
    }

    /** The same price again changes nothing and records nothing. Colors with their own price tag keep it. */
    fun changePrice(priceTag: PriceTag, by: UserId, now: Instant): Result<Product, ChangePriceFailure> {
        if (!canBeEdited()) return Result.failure(ChangePriceFailure.ProductIsArchived)
        if (priceTag == this.priceTag) return Result.success(this)

        raiseEvent(ProductPriceChanged(id, nextSequence(), now, by, priceTag))
        return Result.success(this)
    }

    // ============ colors ============

    /**
     * Adds a color. Without a typed SKU it gets one from the product's (`KV-1001-LILAC`). The first color, or one asked
     * to be the default, becomes the default; the color that was the default until then is not any more.
     *
     * [variantId] is handed out before (a sequence), so an id already on this product is a bug, not a user mistake.
     */
    fun addVariant(
        variantId: VariantId,
        colorKey: ColorKey,
        sku: Sku?,
        ownPriceTag: PriceTag?,
        makeDefault: Boolean,
        sortOrder: Int,
        media: List<ProductMedia>,
        by: UserId,
        now: Instant,
    ): Result<Product, AddVariantFailure> {
        if (!canBeEdited()) return Result.failure(AddVariantFailure.ProductIsArchived)
        require(variantList.none { it.id == variantId }) { "Variant ${variantId.value} is already on product ${id.value}" }
        if (variantList.any { it.colorKey == colorKey }) return Result.failure(AddVariantFailure.ColorAlreadyAdded(colorKey))

        val variantSku = sku ?: Sku.forVariant(this.sku, colorKey)
        if (variantSku == this.sku || variantList.any { it.sku == variantSku }) return Result.failure(AddVariantFailure.SkuAlreadyUsed)

        val isDefault = makeDefault || defaultVariant() == null
        raiseEvent(VariantAdded(id, nextSequence(), now, by, variantId, colorKey, variantSku, ownPriceTag, isDefault, sortOrder, media.toList()))
        return Result.success(this)
    }

    /**
     * Changes a color's own price tag (null = follow the product's) and its position, and can make it the default.
     * [makeDefault] false leaves the default where it is: to move it, make another color the default.
     * The same values again record nothing.
     */
    fun changeVariant(
        variantId: VariantId,
        ownPriceTag: PriceTag?,
        sortOrder: Int,
        makeDefault: Boolean,
        by: UserId,
        now: Instant,
    ): Result<Product, ChangeVariantFailure> {
        if (!canBeEdited()) return Result.failure(ChangeVariantFailure.ProductIsArchived)
        val variant = findVariant(variantId) ?: return Result.failure(ChangeVariantFailure.VariantNotFound(variantId))
        if (makeDefault && !variant.isActive) return Result.failure(ChangeVariantFailure.InactiveVariantCannotBeDefault)

        val isDefault = makeDefault || variant.isDefault
        if (variant.ownPriceTag == ownPriceTag && variant.sortOrder == sortOrder && variant.isDefault == isDefault) {
            return Result.success(this)
        }

        raiseEvent(VariantChanged(id, nextSequence(), now, by, variantId, ownPriceTag, sortOrder, isDefault))
        return Result.success(this)
    }

    /**
     * Takes a color out of the shop; it stays on the product, because past orders point to it. If it was the default,
     * the next active color (in display order) becomes the default. A published product keeps at least one active
     * color: to take the last one away, unpublish the product first.
     */
    fun deactivateVariant(variantId: VariantId, by: UserId, now: Instant): Result<Product, DeactivateVariantFailure> {
        if (!canBeEdited()) return Result.failure(DeactivateVariantFailure.ProductIsArchived)
        val variant = findVariant(variantId) ?: return Result.failure(DeactivateVariantFailure.VariantNotFound(variantId))
        if (!variant.isActive) return Result.failure(DeactivateVariantFailure.AlreadyInactive)
        if (status == ACTIVE && activeVariants().size == 1) return Result.failure(DeactivateVariantFailure.LastActiveVariant)

        val newDefault = if (variant.isDefault) nextDefaultCandidate(except = variantId) else null
        raiseEvent(VariantDeactivated(id, nextSequence(), now, by, variantId, newDefault?.id))
        return Result.success(this)
    }

    /** Brings a deactivated color back. It becomes the default only if no other active color is. */
    fun reactivateVariant(variantId: VariantId, by: UserId, now: Instant): Result<Product, ReactivateVariantFailure> {
        if (!canBeEdited()) return Result.failure(ReactivateVariantFailure.ProductIsArchived)
        val variant = findVariant(variantId) ?: return Result.failure(ReactivateVariantFailure.VariantNotFound(variantId))
        if (variant.isActive) return Result.failure(ReactivateVariantFailure.AlreadyActive)

        raiseEvent(VariantReactivated(id, nextSequence(), now, by, variantId, isDefault = defaultVariant() == null))
        return Result.success(this)
    }

    /**
     * Replaces a color's whole gallery, in the given order (the first is the card photo). Works for inactive colors
     * too. The same gallery again records nothing.
     */
    fun replaceVariantMedia(
        variantId: VariantId,
        media: List<ProductMedia>,
        by: UserId,
        now: Instant,
    ): Result<Product, ReplaceVariantMediaFailure> {
        if (!canBeEdited()) return Result.failure(ReplaceVariantMediaFailure.ProductIsArchived)
        val variant = findVariant(variantId) ?: return Result.failure(ReplaceVariantMediaFailure.VariantNotFound(variantId))
        if (variant.media == media) return Result.success(this)

        raiseEvent(VariantMediaReplaced(id, nextSequence(), now, by, variantId, media.toList()))
        return Result.success(this)
    }

    // ============ status ============

    /** DRAFT to ACTIVE: into the shop. Needs at least one active color, or the shop would show a bag nobody can pick. */
    fun publish(by: UserId, now: Instant): Result<Product, PublishFailure> {
        when (status) {
            ACTIVE -> return Result.failure(PublishFailure.AlreadyActive)
            ARCHIVED -> return Result.failure(PublishFailure.InvalidStatusTransition(from = ARCHIVED, to = ACTIVE))
            DRAFT -> if (activeVariants().isEmpty()) return Result.failure(PublishFailure.NoActiveVariant)
        }
        raiseEvent(ProductPublished(id, nextSequence(), now, by))
        return Result.success(this)
    }

    /** ACTIVE back to DRAFT: out of the shop for now. */
    fun unpublish(by: UserId, now: Instant): Result<Product, UnpublishFailure> {
        when (status) {
            DRAFT -> return Result.failure(UnpublishFailure.AlreadyDraft)
            ARCHIVED -> return Result.failure(UnpublishFailure.InvalidStatusTransition(from = ARCHIVED, to = DRAFT))
            ACTIVE -> Unit
        }
        raiseEvent(ProductUnpublished(id, nextSequence(), now, by))
        return Result.success(this)
    }

    /** DRAFT or ACTIVE to ARCHIVED: out of the shop for good. Past orders still point to it, so it is never deleted. */
    fun archive(by: UserId, now: Instant): Result<Product, ArchiveFailure> {
        if (status == ARCHIVED) return Result.failure(ArchiveFailure.AlreadyArchived)

        raiseEvent(ProductArchived(id, nextSequence(), now, by))
        return Result.success(this)
    }

    // ============ events -> state ============

    override fun apply(event: ProductDomainEvent) {
        when (event) {
            is ProductCreated -> {
                id = event.productId
                slug = event.slug
                sku = event.sku
                details = event.details
                priceTag = event.priceTag
                status = DRAFT
            }
            is ProductDetailsChanged -> details = event.details
            is ProductPriceChanged -> priceTag = event.priceTag
            is ProductPublished -> status = ACTIVE
            is ProductUnpublished -> status = DRAFT
            is ProductArchived -> status = ARCHIVED
            is VariantAdded -> {
                if (event.isDefault) variantList.replaceAll { it.copy(isDefault = false) }
                variantList += Variant(
                    id = event.variantId,
                    colorKey = event.colorKey,
                    sku = event.sku,
                    ownPriceTag = event.ownPriceTag,
                    isDefault = event.isDefault,
                    isActive = true,
                    sortOrder = event.sortOrder,
                    media = event.media.toList(),
                )
            }
            is VariantChanged -> {
                if (event.isDefault) variantList.replaceAll { it.copy(isDefault = false) }
                updateVariant(event.variantId) {
                    it.copy(ownPriceTag = event.ownPriceTag, sortOrder = event.sortOrder, isDefault = event.isDefault)
                }
            }
            is VariantDeactivated -> {
                updateVariant(event.variantId) { it.copy(isActive = false, isDefault = false) }
                event.newDefaultVariantId?.let { newDefault -> updateVariant(newDefault) { it.copy(isDefault = true) } }
            }
            is VariantReactivated -> {
                if (event.isDefault) variantList.replaceAll { it.copy(isDefault = false) }
                updateVariant(event.variantId) { it.copy(isActive = true, isDefault = event.isDefault) }
            }
            is VariantMediaReplaced -> updateVariant(event.variantId) { it.copy(media = event.media.toList()) }
        }
    }

    private fun updateVariant(id: VariantId, change: (Variant) -> Variant) {
        val index = variantList.indexOfFirst { it.id == id }
        check(index >= 0) { "Event for variant ${id.value}, which product ${this.id.value} doesn't have" }
        variantList[index] = change(variantList[index])
    }

    override fun equals(other: Any?): Boolean = other is Product && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Product(id=${id.value}, sku=${sku.value})"

    // ============ failures, one sealed family per operation ============

    sealed class ChangeDetailsFailure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object ProductIsArchived : ChangeDetailsFailure("PRODUCT_ARCHIVED", FailureKind.BUSINESS_RULE)
    }

    sealed class ChangePriceFailure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object ProductIsArchived : ChangePriceFailure("PRODUCT_ARCHIVED", FailureKind.BUSINESS_RULE)
    }

    sealed class AddVariantFailure(code: String, kind: FailureKind, field: String? = null, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, meta = meta) {
        data object ProductIsArchived : AddVariantFailure("PRODUCT_ARCHIVED", FailureKind.BUSINESS_RULE)

        data class ColorAlreadyAdded(val colorKey: ColorKey) :
            AddVariantFailure("COLOR_ALREADY_ON_PRODUCT", FailureKind.CONFLICT, "colorKey", mapOf("colorKey" to colorKey.value))

        data object SkuAlreadyUsed : AddVariantFailure("DUPLICATE_SKU", FailureKind.CONFLICT, "sku")
    }

    sealed class ChangeVariantFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, meta = meta) {
        data object ProductIsArchived : ChangeVariantFailure("PRODUCT_ARCHIVED", FailureKind.BUSINESS_RULE)

        data class VariantNotFound(val variantId: VariantId) :
            ChangeVariantFailure("VARIANT_NOT_FOUND", FailureKind.NOT_FOUND, mapOf("variantId" to variantId.value))

        data object InactiveVariantCannotBeDefault :
            ChangeVariantFailure("INACTIVE_VARIANT_CANNOT_BE_DEFAULT", FailureKind.BUSINESS_RULE)
    }

    sealed class DeactivateVariantFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, meta = meta) {
        data object ProductIsArchived : DeactivateVariantFailure("PRODUCT_ARCHIVED", FailureKind.BUSINESS_RULE)

        data class VariantNotFound(val variantId: VariantId) :
            DeactivateVariantFailure("VARIANT_NOT_FOUND", FailureKind.NOT_FOUND, mapOf("variantId" to variantId.value))

        /** Already out of the shop: the caller can treat it as done. */
        data object AlreadyInactive : DeactivateVariantFailure("VARIANT_ALREADY_INACTIVE", FailureKind.BUSINESS_RULE)

        data object LastActiveVariant : DeactivateVariantFailure("LAST_ACTIVE_VARIANT", FailureKind.BUSINESS_RULE)
    }

    sealed class ReactivateVariantFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, meta = meta) {
        data object ProductIsArchived : ReactivateVariantFailure("PRODUCT_ARCHIVED", FailureKind.BUSINESS_RULE)

        data class VariantNotFound(val variantId: VariantId) :
            ReactivateVariantFailure("VARIANT_NOT_FOUND", FailureKind.NOT_FOUND, mapOf("variantId" to variantId.value))

        /** Already in the shop: the caller can treat it as done. */
        data object AlreadyActive : ReactivateVariantFailure("VARIANT_ALREADY_ACTIVE", FailureKind.BUSINESS_RULE)
    }

    sealed class ReplaceVariantMediaFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, meta = meta) {
        data object ProductIsArchived : ReplaceVariantMediaFailure("PRODUCT_ARCHIVED", FailureKind.BUSINESS_RULE)

        data class VariantNotFound(val variantId: VariantId) :
            ReplaceVariantMediaFailure("VARIANT_NOT_FOUND", FailureKind.NOT_FOUND, mapOf("variantId" to variantId.value))
    }

    sealed class PublishFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, meta = meta) {
        /** Already in the shop: the caller can treat it as done. */
        data object AlreadyActive : PublishFailure("PRODUCT_ALREADY_ACTIVE", FailureKind.BUSINESS_RULE)

        data object NoActiveVariant : PublishFailure("NO_ACTIVE_VARIANT", FailureKind.BUSINESS_RULE)

        data class InvalidStatusTransition(val from: ProductStatus, val to: ProductStatus) :
            PublishFailure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, mapOf("from" to from.name, "to" to to.name))
    }

    sealed class UnpublishFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, meta = meta) {
        /** Already out of the shop: the caller can treat it as done. */
        data object AlreadyDraft : UnpublishFailure("PRODUCT_ALREADY_DRAFT", FailureKind.BUSINESS_RULE)

        data class InvalidStatusTransition(val from: ProductStatus, val to: ProductStatus) :
            UnpublishFailure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, mapOf("from" to from.name, "to" to to.name))
    }

    sealed class ArchiveFailure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        /** Already archived: the caller can treat it as done. */
        data object AlreadyArchived : ArchiveFailure("PRODUCT_ALREADY_ARCHIVED", FailureKind.BUSINESS_RULE)
    }
}
