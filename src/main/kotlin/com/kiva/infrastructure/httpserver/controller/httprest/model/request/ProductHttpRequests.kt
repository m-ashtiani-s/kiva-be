package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import com.kiva.application.usecase.product.ProductInput
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * The body of `POST` and `PUT /admin/products*` (contract ProductUpsertRequest). Every field nullable, so one left out
 * is reported by the use case together with every other problem of the form. `status` and `variants` are read only by
 * `POST`.
 */
@Serializable
data class ProductUpsertHttpRequest(
    val name: String? = null,
    val slug: String? = null,
    val sku: String? = null,
    val categoryId: Long? = null,
    val bagType: String? = null,
    val materialId: Long? = null,
    val materialDescription: String? = null,
    val price: Long? = null,
    val compareAtPrice: Long? = null,
    val shortDescription: String? = null,
    val description: String? = null,
    val highlights: List<String>? = null,
    val specs: SpecsHttpRequest? = null,
    val tags: List<String>? = null,
    val newUntil: String? = null,
    val maxPerOrder: Int? = null,
    val returnable: Boolean? = null,
    val reservable: Boolean? = null,
    val sortPriority: Int? = null,
    val status: String? = null,
    val seo: SeoHttpRequest? = null,
    val variants: List<VariantUpsertHttpRequest>? = null,
) {
    fun toFields() = ProductInput.Fields(
        name, slug, sku, categoryId, bagType, materialId, materialDescription, price, compareAtPrice, shortDescription, description,
        highlights, specs?.toSpecs(), tags, newUntil?.let { runCatching { Instant.parse(it) }.getOrNull() }, maxPerOrder, returnable, reservable,
        sortPriority, seo?.let { ProductInput.Seo(it.title, it.description, it.noIndex) },
    )

    fun toVariants() = variants.orEmpty().map { it.toVariant() }
}

@Serializable
data class SpecsHttpRequest(
    val dimensions: SizeHttpRequest? = null,
    val weightGrams: Int? = null,
    val closure: ClosureHttpRequest? = null,
    val straps: List<StrapHttpRequest>? = null,
    val pockets: PocketsHttpRequest? = null,
    val laptopMaxInch: Double? = null,
    val capacityLiters: Double? = null,
    val waterproof: Boolean? = null,
    val occasions: List<OccasionHttpRequest>? = null,
    val fits: List<String>? = null,
    val lining: TwoTextsHttpRequest? = null,
    val hardware: HardwareHttpRequest? = null,
    val stitching: String? = null,
    val handmade: Boolean? = null,
    val madeIn: String? = null,
    val careInstructions: List<String>? = null,
    val warranty: WarrantyHttpRequest? = null,
) {
    fun toSpecs() = ProductInput.Specs(
        dimensions = dimensions?.let { ProductInput.Size(it.lengthCm, it.heightCm, it.widthCm) },
        weightGrams = weightGrams,
        closure = closure?.type,
        straps = straps?.map { ProductInput.Strap(it.type, it.label, it.lengthCm, it.minLengthCm, it.maxLengthCm, it.adjustable, it.detachable, it.material) },
        pockets = pockets?.let {
            ProductInput.Pockets(it.internal, it.internalZip, it.external, it.phone, it.cardSlots, it.billSlots, it.coinPocket, it.detachablePouch, it.laptopSleeve, it.summary)
        },
        laptopMaxInch = laptopMaxInch,
        capacityLiters = capacityLiters,
        waterproof = waterproof,
        occasions = occasions?.mapNotNull { it.code },
        fits = fits,
        lining = lining?.let { ProductInput.Lining(it.material, it.color) },
        hardware = hardware?.let { ProductInput.Hardware(it.material, it.finish) },
        stitching = stitching,
        handmade = handmade,
        madeIn = madeIn,
        careInstructions = careInstructions,
        warranty = warranty?.let { ProductInput.WarrantyText(it.months, it.description) },
    )
}

@Serializable
data class SizeHttpRequest(val lengthCm: Double? = null, val heightCm: Double? = null, val widthCm: Double? = null)

@Serializable
data class ClosureHttpRequest(val type: String? = null)

@Serializable
data class StrapHttpRequest(
    val type: String? = null,
    val label: String? = null,
    val lengthCm: Double? = null,
    val minLengthCm: Double? = null,
    val maxLengthCm: Double? = null,
    val adjustable: Boolean? = null,
    val detachable: Boolean? = null,
    val material: String? = null,
)

@Serializable
data class PocketsHttpRequest(
    val internal: Int? = null,
    val internalZip: Int? = null,
    val external: Int? = null,
    val phone: Int? = null,
    val cardSlots: Int? = null,
    val billSlots: Int? = null,
    val coinPocket: Boolean? = null,
    val detachablePouch: Boolean? = null,
    val laptopSleeve: Boolean? = null,
    val summary: String? = null,
)

@Serializable
data class OccasionHttpRequest(val code: String? = null)

@Serializable
data class TwoTextsHttpRequest(val material: String? = null, val color: String? = null)

@Serializable
data class HardwareHttpRequest(val material: String? = null, val finish: String? = null)

@Serializable
data class WarrantyHttpRequest(val months: Int? = null, val description: String? = null)

/** One color of the form (contract VariantUpsertRequest). */
@Serializable
data class VariantUpsertHttpRequest(
    val colorKey: String? = null,
    val sku: String? = null,
    val barcode: String? = null,
    val priceOverride: Long? = null,
    val compareAtPriceOverride: Long? = null,
    val stockQuantity: Int? = null,
    val isDefault: Boolean? = null,
    val isActive: Boolean? = null,
    val sortOrder: Int? = null,
    val media: List<MediaInputHttpRequest>? = null,
) {
    fun toVariant() = ProductInput.Variant(
        colorKey, sku, barcode, priceOverride, compareAtPriceOverride, stockQuantity, isDefault, isActive, sortOrder,
        media?.map { ProductInput.Media(it.mediaId, it.view, it.sortOrder, it.isPrimary, it.alt) },
    )
}

/** One item of a color's gallery (contract ProductMediaInput). */
@Serializable
data class MediaInputHttpRequest(
    val mediaId: String? = null,
    val view: String? = null,
    val sortOrder: Int? = null,
    val isPrimary: Boolean? = null,
    val alt: String? = null,
)
