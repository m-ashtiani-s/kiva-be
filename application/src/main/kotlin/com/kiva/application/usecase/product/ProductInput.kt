package com.kiva.application.usecase.product

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.bind
import com.kiva.common.railway.forField
import com.kiva.common.railway.mapOnSuccess
import com.kiva.common.railway.required
import com.kiva.common.railway.unwrapOr
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.SeoMeta
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.material.model.valueobject.CareInstructions
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.media.model.valueobject.MediaAlt
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.product.model.policy.ProductGalleryPolicy
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.Barcode
import com.kiva.domain.aggregate.product.model.valueobject.ClosureType
import com.kiva.domain.aggregate.product.model.valueobject.Dimensions
import com.kiva.domain.aggregate.product.model.valueobject.FitItem
import com.kiva.domain.aggregate.product.model.valueobject.HardwareInfo
import com.kiva.domain.aggregate.product.model.valueobject.Highlights
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import com.kiva.domain.aggregate.product.model.valueobject.LiningInfo
import com.kiva.domain.aggregate.product.model.valueobject.MaterialDescription
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.product.model.valueobject.Occasion
import com.kiva.domain.aggregate.product.model.valueobject.PocketInfo
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductDescription
import com.kiva.domain.aggregate.product.model.valueobject.ProductDetails
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.ProductSpecs
import com.kiva.domain.aggregate.product.model.valueobject.ProductTags
import com.kiva.domain.aggregate.product.model.valueobject.ShortDescription
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.StrapInfo
import com.kiva.domain.aggregate.product.model.valueobject.WarrantyInfo
import java.time.Instant

/**
 * Reads the product form (the contract's ProductUpsertRequest and VariantUpsertRequest) the same way for every use
 * case that takes it: making a product (CreateProductUc), editing it (UpdateProductUc) and, later, a color on its own.
 *
 * Every problem comes back at once, each under its full path (`specs.straps.0.lengthCm`, `variants.1.media.0.mediaId`,
 * `seo.title`), so the panel's form can mark every wrong field after one save. Nothing here reads the database: what
 * must exist (category, material, colors, uploaded files) and what must be free (slug, codes) is the use case's job,
 * inside its transaction.
 *
 * The description is sanitized here, before anything is kept (HtmlSanitizer: only the contract's tags, no script, no
 * `on*` attribute, no `javascript:` link); its length is counted after.
 */
class ProductInput(private val htmlSanitizer: HtmlSanitizer) {

    /**
     * The product's own fields, raw: everything nullable, as a request body has it. Left out (null) takes the
     * contract's default: maxPerOrder 5, returnable, reservable, sortPriority 0, no tags, no seo, empty specs.
     */
    data class Fields(
        val name: String?,
        val slug: String?,
        val sku: String?,
        val categoryId: Long?,
        val bagType: String?,
        val materialId: Long?,
        val materialDescription: String?,
        val price: Long?,
        val compareAtPrice: Long?,
        val shortDescription: String?,
        val description: String?,
        val highlights: List<String>?,
        val specs: Specs?,
        val tags: List<String>?,
        val newUntil: Instant?,
        val maxPerOrder: Int?,
        val returnable: Boolean?,
        val reservable: Boolean?,
        val sortPriority: Int?,
        val seo: Seo?,
    )

    /** The contract's ProductSpecsInput. */
    data class Specs(
        val dimensions: Size? = null,
        val weightGrams: Int? = null,
        val closure: String? = null,
        val straps: List<Strap>? = null,
        val pockets: Pockets? = null,
        val laptopMaxInch: Double? = null,
        val capacityLiters: Double? = null,
        val waterproof: Boolean? = null,
        val occasions: List<String>? = null,
        val fits: List<String>? = null,
        val lining: Lining? = null,
        val hardware: Hardware? = null,
        val stitching: String? = null,
        val handmade: Boolean? = null,
        val madeIn: String? = null,
        val careInstructions: List<String>? = null,
        val warranty: WarrantyText? = null,
    ) {
        companion object {
            val NONE = Specs()
        }
    }

    /** Length × height × width, in centimeters. */
    data class Size(val lengthCm: Double?, val heightCm: Double?, val widthCm: Double?)

    data class Strap(
        val type: String? = null,
        val label: String? = null,
        val lengthCm: Double? = null,
        val minLengthCm: Double? = null,
        val maxLengthCm: Double? = null,
        val adjustable: Boolean? = null,
        val detachable: Boolean? = null,
        val material: String? = null,
    )

    data class Pockets(
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

    data class Lining(val material: String?, val color: String?)

    data class Hardware(val material: String?, val finish: String?)

    data class WarrantyText(val months: Int?, val description: String?)

    /** Only these three are read; the rest of the contract's SeoMeta is the server's own. */
    data class Seo(val title: String?, val description: String?, val noIndex: Boolean?)

    /**
     * One color (VariantUpsertRequest), raw.
     *
     * @property sku null or blank to make one from the product's (`KV-1001-LILAC`).
     * @property stockQuantity the starting stock, only when the color is made; null is 0.
     */
    data class Variant(
        val colorKey: String?,
        val sku: String? = null,
        val barcode: String? = null,
        val priceOverride: Long? = null,
        val compareAtPriceOverride: Long? = null,
        val stockQuantity: Int? = null,
        val isDefault: Boolean? = null,
        val isActive: Boolean? = null,
        val sortOrder: Int? = null,
        val media: List<Media>? = null,
    )

    /** One item of a color's gallery (ProductMediaInput), raw. */
    data class Media(val mediaId: String?, val view: String?, val sortOrder: Int? = null, val isPrimary: Boolean? = null, val alt: String? = null)

    /** The product's fields, checked. [sku] is null when the form asks for one to be made. */
    data class Checked(val slug: Slug, val sku: Sku?, val details: ProductDetails, val priceTag: PriceTag)

    /**
     * One color, checked. [gallery] is in its final order (ProductGalleryPolicy); whether its files exist and are
     * product files is for the use case to ask, naming each by its place in the form ([mediaPlaces]: a file is in a
     * gallery at most once).
     */
    data class CheckedVariant(
        val colorKey: ColorKey,
        val sku: Sku?,
        val barcode: Barcode?,
        val ownPriceTag: PriceTag?,
        val isDefault: Boolean,
        val isActive: Boolean,
        val sortOrder: Int?,
        val stockQuantity: Int,
        val gallery: List<ProductGalleryPolicy.Entry>,
        val mediaPlaces: Map<MediaId, Int>,
    ) {
        /** The form's field of one file of the gallery, inside the color: `media.2.mediaId`. */
        fun mediaFieldOf(id: MediaId): String = "media.${mediaPlaces.getValue(id)}.mediaId"
    }

    /** Every problem of the product's own fields at once, each under its path. */
    fun check(fields: Fields): Result<Checked, InputProblems> {
        val problems = Problems()
        val slug = problems.take(fields.slug.required().forField("slug").bind { Slug.makeNew(it).forField("slug") })
        val sku = problems.take(skuOf(fields.sku))
        val name = problems.take(fields.name.required().forField("name").bind { ProductName.makeNew(it) })
        val categoryId = problems.take(fields.categoryId.required().bind { CategoryId.makeNew(it) }.forField("categoryId"))
        val materialId = problems.take(fields.materialId.required().bind { MaterialId.makeNew(it) }.forField("materialId"))
        val bagType = problems.take(fields.bagType.required().forField("bagType").bind { BagType.makeNew(it) })
        val materialDescription = problems.take(MaterialDescription.makeNew(fields.materialDescription))
        val shortDescription = problems.take(ShortDescription.makeNew(fields.shortDescription))
        val description = problems.take(ProductDescription.makeNew(fields.description, htmlSanitizer))
        val highlights = problems.take(Highlights.makeNew(fields.highlights.orEmpty()))
        val specs = problems.take(specsOf(fields.specs ?: Specs.NONE).forField("specs"))
        val tags = problems.take(ProductTags.makeNew(fields.tags))
        val maxPerOrder = problems.take(fields.maxPerOrder?.let { MaxPerOrder.makeNew(it) } ?: Result.success(MaxPerOrder.DEFAULT))
        val seo = problems.take(fields.seo?.let { SeoMeta.makeNew(it.title, it.description, it.noIndex) } ?: Result.success(SeoMeta.NONE))
        val priceTag = problems.take(fields.price.required().forField("price").bind { priceTagOf(it, fields.compareAtPrice, "price", "compareAtPrice") })
        if (problems.any()) return Result.failure(problems.all())

        val details = ProductDetails(
            name = name.value,
            categoryId = categoryId.value,
            materialId = materialId.value,
            materialDescription = materialDescription.value,
            bagType = bagType.value,
            shortDescription = shortDescription.value,
            description = description.value,
            highlights = highlights.value,
            specs = specs.value,
            newUntil = fields.newUntil,
            maxPerOrder = maxPerOrder.value,
            returnable = fields.returnable ?: true,
            reservable = fields.reservable ?: true,
            sortPriority = fields.sortPriority ?: 0,
            tags = tags.value,
            seo = seo.value,
        )
        return Result.success(Checked(slug.value, sku.value, details, priceTag.value))
    }

    /** Every problem of one color at once, each under its path inside the color (`media.0.mediaId`); the caller puts `variants.1` in front. */
    fun checkVariant(variant: Variant): Result<CheckedVariant, InputProblems> {
        val problems = Problems()
        val colorKey = problems.take(variant.colorKey.required().bind { ColorKey.makeNew(it) }.forField("colorKey"))
        val sku = problems.take(skuOf(variant.sku))
        val barcode = problems.take(Barcode.makeNew(variant.barcode).forField("barcode"))
        val ownPriceTag = problems.take(ownPriceTagOf(variant))
        val stock = problems.take(stockQuantityOf(variant.stockQuantity))
        val gallery = problems.take(galleryOf(variant.media.orEmpty()).forField("media"))
        if (problems.any()) return Result.failure(problems.all())

        return Result.success(
            CheckedVariant(
                colorKey = colorKey.value,
                sku = sku.value,
                barcode = barcode.value,
                ownPriceTag = ownPriceTag.value,
                isDefault = variant.isDefault ?: false,
                isActive = variant.isActive ?: true,
                sortOrder = variant.sortOrder,
                stockQuantity = stock.value,
                gallery = gallery.value.first,
                mediaPlaces = gallery.value.second,
            ),
        )
    }

    // ============ the parts ============

    /** Every problem under `specs` (`dimensions`, `straps.1.lengthCm`, `occasions.0`, `weightGrams`); the caller puts `specs` in front. */
    private fun specsOf(specs: Specs): Result<ProductSpecs, ResultFailure> {
        val problems = Problems()
        val dimensions = problems.take(
            specs.dimensions?.let { Dimensions.makeNew(it.lengthCm ?: 0.0, it.heightCm ?: 0.0, it.widthCm ?: 0.0) } ?: Result.success(null),
        )
        val closure = problems.take(specs.closure?.let { ClosureType.makeNew(it).forField("closure.type") } ?: Result.success(null))
        val straps = problems.take(
            specs.straps.orEmpty().mapIndexed { index, strap ->
                StrapInfo.makeNew(strap.type, strap.label, strap.lengthCm, strap.minLengthCm, strap.maxLengthCm, strap.adjustable, strap.detachable, strap.material)
                    .forField("straps.$index")
            }.allOrProblems(),
        )
        val pockets = problems.take(
            specs.pockets?.let {
                PocketInfo.makeNew(it.internal, it.internalZip, it.external, it.phone, it.cardSlots, it.billSlots, it.coinPocket, it.detachablePouch, it.laptopSleeve, it.summary)
                    .forField("pockets")
            } ?: Result.success(null),
        )
        val occasions = problems.take(specs.occasions.orEmpty().mapIndexed { index, raw -> Occasion.makeNew(raw).forField("occasions.$index") }.allOrProblems())
        val fits = problems.take(specs.fits.orEmpty().mapIndexed { index, raw -> FitItem.makeNew(raw).forField("fits.$index") }.allOrProblems())
        val lining = problems.take(specs.lining?.let { LiningInfo.makeNew(it.material, it.color).forField("lining") } ?: Result.success(null))
        val hardware = problems.take(specs.hardware?.let { HardwareInfo.makeNew(it.material, it.finish).forField("hardware") } ?: Result.success(null))
        val warranty = problems.take(specs.warranty?.let { WarrantyInfo.makeNew(it.months, it.description).forField("warranty") } ?: Result.success(null))
        val care = problems.take(CareInstructions.makeNew(specs.careInstructions))
        // The limits on the whole (weight, number of straps, ...) come with the parts' problems.
        ProductSpecs.problemsOfWhole(specs.weightGrams, specs.straps.orEmpty().size, specs.laptopMaxInch, specs.capacityLiters, specs.stitching, specs.madeIn)
            .forEach(problems::add)
        if (problems.any()) return Result.failure(problems.all())

        return ProductSpecs.makeNew(
            dimensions = dimensions.value,
            weightGrams = specs.weightGrams,
            closure = closure.value,
            straps = straps.value,
            pockets = pockets.value,
            laptopMaxInch = specs.laptopMaxInch,
            capacityLiters = specs.capacityLiters,
            waterproof = specs.waterproof ?: false,
            occasions = occasions.value.toSet(),
            fits = fits.value.toSet(),
            lining = lining.value,
            hardware = hardware.value,
            stitching = specs.stitching,
            handmade = specs.handmade ?: false,
            madeIn = specs.madeIn,
            careInstructions = care.value,
            warranty = warranty.value,
        ).unwrapOr { error("Checked just above: $it") }.let { Result.success(it) }
    }

    /** Each item's own problems (`3.view`), then the gallery's rules (`4.isPrimary`, too many); the caller puts `media` in front. */
    private fun galleryOf(media: List<Media>): Result<Pair<List<ProductGalleryPolicy.Entry>, Map<MediaId, Int>>, ResultFailure> {
        val entries = media.mapIndexed { index, item ->
            val problems = Problems()
            val id = problems.take(item.mediaId.required().bind { MediaId.makeNew(it) }.forField("mediaId"))
            val view = problems.take(item.view.required().bind { MediaView.makeNew(it) }.forField("view"))
            val alt = problems.take(MediaAlt.makeNew(item.alt))
            if (problems.any()) {
                Result.failure(problems.all()).forField("$index")
            } else {
                Result.success(ProductGalleryPolicy.Entry(id.value, view.value, alt.value, item.isPrimary ?: false, item.sortOrder))
            }
        }.allOrProblems()
        // Too many is said once, whatever else is wrong; the other rules need every item readable.
        val tooMany = if (media.size > ProductGalleryPolicy.MAX_ITEMS) ProductGalleryPolicy.Problem.TooMany else null
        val itemProblems = entries.failureOrNull() ?: run {
            val asListed = entries.getOrNull().orEmpty()
            return ProductGalleryPolicy.order(asListed).mapOnSuccess { ordered -> ordered to asListed.withIndex().associate { it.value.mediaId to it.index } }
        }
        return Result.failure(InputProblems(listOfNotNull(tooMany, itemProblems)))
    }

    private fun priceTagOf(price: Long, compareAtPrice: Long?, priceField: String, compareAtField: String): Result<PriceTag, ResultFailure> {
        val problems = Problems()
        val amount = problems.take(Money.makeNew(price).forField(priceField))
        val compareAt = problems.take(compareAtPrice?.let { Money.makeNew(it).forField(compareAtField) } ?: Result.success(null))
        if (problems.any()) return Result.failure(problems.all())
        return PriceTag.makeNew(amount.value, compareAt.value).forField(compareAtField)
    }

    private fun ownPriceTagOf(variant: Variant): Result<PriceTag?, ResultFailure> = when {
        variant.priceOverride != null ->
            priceTagOf(variant.priceOverride, variant.compareAtPriceOverride, "priceOverride", "compareAtPriceOverride")
        variant.compareAtPriceOverride != null -> Result.failure(Problem.PriceOverrideRequired)
        else -> Result.success(null)
    }

    private fun stockQuantityOf(raw: Int?): Result<Int, ResultFailure> =
        if (raw == null || raw in 0..MAX_STOCK_QUANTITY) Result.success(raw ?: 0) else Result.failure(Problem.StockQuantityInvalid)

    // The contract: an empty code means "make one".
    private fun skuOf(raw: String?): Result<Sku?, ResultFailure> =
        raw?.takeIf { it.isNotBlank() }?.let { Sku.makeNew(it) } ?: Result.success(null)

    private fun <T> List<Result<T, ResultFailure>>.allOrProblems(): Result<List<T>, ResultFailure> {
        val failures = mapNotNull { it.failureOrNull() }
        if (failures.isNotEmpty()) return Result.failure(InputProblems(failures))
        return Result.success(map { (it as Result.Success).value })
    }

    /**
     * Collects the problems of a form checked part by part. Each part is asked once ([take]); when none failed, every
     * [Part.value] is there. More readable than nested zips for a form of fifteen parts.
     */
    private class Problems {
        private val found = mutableListOf<ResultFailure>()

        class Part<T>(private val result: Result<T, ResultFailure>) {
            val value: T get() = (result as Result.Success).value
        }

        fun <T> take(result: Result<T, ResultFailure>): Part<T> {
            result.failureOrNull()?.let { found += it }
            return Part(result)
        }

        fun add(problem: ResultFailure) {
            found += problem
        }

        fun any(): Boolean = found.isNotEmpty()

        fun all(): InputProblems = InputProblems(found.toList())
    }

    companion object {
        /** From the contract (`stockQuantity.maximum`). */
        const val MAX_STOCK_QUANTITY = 10_000
    }

    sealed class Problem(code: String, field: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, field, meta = meta) {
        /** A crossed-out price for a color without a price of its own. */
        data object PriceOverrideRequired : Problem("PRICE_OVERRIDE_REQUIRED", "priceOverride")

        data object StockQuantityInvalid : Problem("STOCK_QUANTITY_INVALID", "stockQuantity", mapOf("max" to MAX_STOCK_QUANTITY))
    }
}
