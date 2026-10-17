package com.kiva.infrastructure.persistence.event.serializer.payload.product

import com.kiva.domain.aggregate.material.model.valueobject.CareInstructions
import com.kiva.domain.aggregate.product.model.valueobject.ClosureType
import com.kiva.domain.aggregate.product.model.valueobject.FitItem
import com.kiva.domain.aggregate.product.model.valueobject.HardwareInfo
import com.kiva.domain.aggregate.product.model.valueobject.LiningInfo
import com.kiva.domain.aggregate.product.model.valueobject.Occasion
import com.kiva.domain.aggregate.product.model.valueobject.PocketInfo
import com.kiva.domain.aggregate.product.model.valueobject.ProductSpecs
import com.kiva.domain.aggregate.product.model.valueobject.StrapInfo
import com.kiva.domain.aggregate.product.model.valueobject.StrapType
import com.kiva.domain.aggregate.product.model.valueobject.WarrantyInfo
import kotlinx.serialization.Serializable

/**
 * [ProductSpecs] as stored, in product events and in productqm_tbl's `specs` (the same shape). Enum values are kept
 * by name (`ZIPPER`), so they are never renamed once in use.
 *
 * Before the full specs (step 1.5) the strap and the pockets were one line of text each: [strap] and [pockets]. Those
 * fields stay (payload fields never go or change type) and are written null from now on; such a payload reads back as
 * one strap with that label, and pockets with that summary. Every field added since has a default, so older payloads
 * read back with nothing in it.
 */
@Serializable
data class ProductSpecsPayload(
    val dimensions: DimensionsPayload?,
    val weightGrams: Int?,
    val closure: String?,
    val strap: String?,
    val pockets: String?,
    val occasions: List<String>,
    val straps: List<StrapPayload> = emptyList(),
    val pocketInfo: PocketInfoPayload? = null,
    val laptopMaxInch: Double? = null,
    val capacityLiters: Double? = null,
    val waterproof: Boolean = false,
    val fits: List<String> = emptyList(),
    val lining: LiningPayload? = null,
    val hardware: HardwarePayload? = null,
    val stitching: String? = null,
    val handmade: Boolean = false,
    val madeIn: String? = null,
    val careInstructions: List<String> = emptyList(),
    val warranty: WarrantyPayload? = null,
) {
    constructor(specs: ProductSpecs) : this(
        dimensions = specs.dimensions?.let { DimensionsPayload(it) },
        weightGrams = specs.weightGrams,
        closure = specs.closure?.name,
        strap = null,
        pockets = null,
        occasions = specs.occasions.map { it.name },
        straps = specs.straps.map(::StrapPayload),
        pocketInfo = specs.pockets?.let(::PocketInfoPayload),
        laptopMaxInch = specs.laptopMaxInch,
        capacityLiters = specs.capacityLiters,
        waterproof = specs.waterproof,
        fits = specs.fits.map { it.name },
        lining = specs.lining?.let { LiningPayload(it.material, it.color) },
        hardware = specs.hardware?.let { HardwarePayload(it.material, it.finish) },
        stitching = specs.stitching,
        handmade = specs.handmade,
        madeIn = specs.madeIn,
        careInstructions = specs.careInstructions.items,
        warranty = specs.warranty?.let { WarrantyPayload(it.months, it.description) },
    )

    fun toDomain(): ProductSpecs = ProductSpecs.constructFromPersisted(
        dimensions = dimensions?.toDomain(),
        weightGrams = weightGrams,
        closure = closure?.let { ClosureType.valueOf(it) },
        straps = straps.map { it.toDomain() }.ifEmpty { listOfNotNull(strap?.let(StrapInfo::legacy)) },
        pockets = pocketInfo?.toDomain() ?: pockets?.let(PocketInfo::legacy),
        laptopMaxInch = laptopMaxInch,
        capacityLiters = capacityLiters,
        waterproof = waterproof,
        occasions = occasions.map { Occasion.valueOf(it) }.toSet(),
        fits = fits.map { FitItem.valueOf(it) }.toSet(),
        lining = lining?.let { LiningInfo.constructFromPersisted(it.material, it.color) },
        hardware = hardware?.let { HardwareInfo.constructFromPersisted(it.material, it.finish) },
        stitching = stitching,
        handmade = handmade,
        madeIn = madeIn,
        careInstructions = CareInstructions.constructFromPersisted(careInstructions),
        warranty = warranty?.let { WarrantyInfo.constructFromPersisted(it.months, it.description) },
    )
}

@Serializable
data class StrapPayload(
    val type: String?,
    val label: String?,
    val lengthCm: Double?,
    val minLengthCm: Double?,
    val maxLengthCm: Double?,
    val adjustable: Boolean,
    val detachable: Boolean,
    val material: String?,
) {
    constructor(strap: StrapInfo) : this(
        strap.type?.name, strap.label, strap.lengthCm, strap.minLengthCm, strap.maxLengthCm, strap.adjustable, strap.detachable, strap.material,
    )

    fun toDomain(): StrapInfo = StrapInfo.constructFromPersisted(
        type?.let { StrapType.valueOf(it) }, label, lengthCm, minLengthCm, maxLengthCm, adjustable, detachable, material,
    )
}

@Serializable
data class PocketInfoPayload(
    val internal: Int,
    val internalZip: Int,
    val external: Int,
    val phone: Int,
    val cardSlots: Int,
    val billSlots: Int,
    val coinPocket: Boolean,
    val detachablePouch: Boolean,
    val laptopSleeve: Boolean,
    val summary: String?,
) {
    constructor(pockets: PocketInfo) : this(
        pockets.internal, pockets.internalZip, pockets.external, pockets.phone, pockets.cardSlots, pockets.billSlots,
        pockets.coinPocket, pockets.detachablePouch, pockets.laptopSleeve, pockets.summary,
    )

    fun toDomain(): PocketInfo = PocketInfo.constructFromPersisted(
        internal, internalZip, external, phone, cardSlots, billSlots, coinPocket, detachablePouch, laptopSleeve, summary,
    )
}

@Serializable
data class LiningPayload(val material: String?, val color: String?)

@Serializable
data class HardwarePayload(val material: String?, val finish: String?)

@Serializable
data class WarrantyPayload(val months: Int, val description: String?)
