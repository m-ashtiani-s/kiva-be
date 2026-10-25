package com.kiva.infrastructure.persistence.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.SeoMeta
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.material.model.valueobject.CareInstructions
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.media.model.valueobject.MediaAlt
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.product.entity.event.ProductArchived
import com.kiva.domain.aggregate.product.entity.event.ProductCreated
import com.kiva.domain.aggregate.product.entity.event.ProductDetailsChanged
import com.kiva.domain.aggregate.product.entity.event.ProductDomainEvent
import com.kiva.domain.aggregate.product.entity.event.ProductPriceChanged
import com.kiva.domain.aggregate.product.entity.event.ProductPublishScheduled
import com.kiva.domain.aggregate.product.entity.event.ProductPublishUnscheduled
import com.kiva.domain.aggregate.product.entity.event.ProductPublished
import com.kiva.domain.aggregate.product.entity.event.ProductUnpublished
import com.kiva.domain.aggregate.product.entity.event.VariantAdded
import com.kiva.domain.aggregate.product.entity.event.VariantChanged
import com.kiva.domain.aggregate.product.entity.event.VariantDeactivated
import com.kiva.domain.aggregate.product.entity.event.VariantMediaReplaced
import com.kiva.domain.aggregate.product.entity.event.VariantReactivated
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.Barcode
import com.kiva.domain.aggregate.product.model.valueobject.ClosureType
import com.kiva.domain.aggregate.product.model.valueobject.Dimensions
import com.kiva.domain.aggregate.product.model.valueobject.FitItem
import com.kiva.domain.aggregate.product.model.valueobject.HardwareInfo
import com.kiva.domain.aggregate.product.model.valueobject.Highlights
import com.kiva.domain.aggregate.product.model.valueobject.LiningInfo
import com.kiva.domain.aggregate.product.model.valueobject.MaterialDescription
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.product.model.valueobject.Occasion
import com.kiva.domain.aggregate.product.model.valueobject.PocketInfo
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductDescription
import com.kiva.domain.aggregate.product.model.valueobject.ProductDetails
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.ProductSpecs
import com.kiva.domain.aggregate.product.model.valueobject.ProductTags
import com.kiva.domain.aggregate.product.model.valueobject.ShortDescription
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.StrapInfo
import com.kiva.domain.aggregate.product.model.valueobject.StrapType
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.product.model.valueobject.WarrantyInfo
import java.time.Instant

/**
 * One event of every kind, with every field filled in, plus the kinds whose optional parts can be left empty.
 * They are samples for storing and reading back, not one product's history: they don't have to replay together.
 */
object ProductEventSamples {

    private val product = ProductId.constructFromPersisted(7)
    private val admin = UserId.constructFromPersisted(12)

    // Down to the nanosecond on purpose: the stored event must keep the exact moment.
    private val at = Instant.parse("2026-10-06T08:15:30.123456789Z")

    private fun eventId(n: Int) = EventId.constructFromPersisted("3f6d2c1e-8b4a-4c6e-9a1b-%012d".format(n))
    private fun sequence(n: Long) = EventSequence.constructFromPersisted(n)
    private fun variant(id: Long) = VariantId.constructFromPersisted(id)
    private fun priceTag(price: Long, compareAt: Long?) =
        PriceTag.constructFromPersisted(Money.constructFromPersisted(price), compareAt?.let { Money.constructFromPersisted(it) })

    /** Every optional part given; texts with Persian letters, quotes and a line break, to test the JSON escaping. */
    val fullDetails = ProductDetails(
        name = ProductName.constructFromPersisted("کیف دوشی ماهک"),
        categoryId = CategoryId.constructFromPersisted(1),
        materialId = MaterialId.constructFromPersisted(4),
        materialDescription = MaterialDescription.constructFromPersisted("چرم مصنوعی مات درجه یک"),
        bagType = BagType.HOBO,
        shortDescription = ShortDescription.constructFromPersisted("کیف دوشی جادار برای هر روز"),
        description = ProductDescription.constructFromPersisted("یه کیف \"ساده\" با دو جیب.\nخط دوم."),
        highlights = Highlights.constructFromPersisted(listOf("یراق‌آلات فلزی ضدزنگ", "آستر ساتن")),
        specs = ProductSpecs.constructFromPersisted(
            dimensions = Dimensions.constructFromPersisted(32.5, 24.0, 12.0),
            weightGrams = 650,
            closure = ClosureType.ZIPPER_WITH_DECORATIVE_LOCK,
            straps = listOf(
                StrapInfo.constructFromPersisted(StrapType.SHOULDER, "بند دوشی ثابت ۵۵ سانتی‌متر", 55.0, null, null, adjustable = false, detachable = false, material = null),
                StrapInfo.constructFromPersisted(StrapType.CROSSBODY, null, null, 80.0, 120.0, adjustable = true, detachable = true, material = "زنجیر فلزی"),
            ),
            pockets = PocketInfo.constructFromPersisted(0, 1, 0, 2, 0, 0, coinPocket = false, detachablePouch = true, laptopSleeve = false, summary = "۱ جیب زیپ‌دار داخلی، ۲ جیب موبایل"),
            laptopMaxInch = 13.3,
            capacityLiters = 9.5,
            waterproof = true,
            occasions = setOf(Occasion.DAILY, Occasion.WORK),
            fits = setOf(FitItem.PHONE, FitItem.A5_NOTEBOOK),
            lining = LiningInfo.constructFromPersisted("آستر ضدلک", "یاسی کیوا"),
            hardware = HardwareInfo.constructFromPersisted("فلز ضدزنگ", null),
            stitching = "دوخت دولایه",
            handmade = true,
            madeIn = "ایران",
            careInstructions = CareInstructions.constructFromPersisted(listOf("با دستمال مرطوب")),
            warranty = WarrantyInfo.constructFromPersisted(6, "ضمانت دوخت و یراق"),
        ),
        newUntil = Instant.parse("2026-10-20T00:00:00Z"),
        maxPerOrder = MaxPerOrder.constructFromPersisted(3),
        returnable = false,
        reservable = false,
        sortPriority = 10,
        tags = ProductTags.constructFromPersisted(listOf("چرم", "اداری")),
        seo = SeoMeta.constructFromPersisted("کیف دوشی ماهک | کیوا", null, noIndex = true),
    )

    /** Every optional part left empty. */
    val bareDetails = ProductDetails(
        name = ProductName.constructFromPersisted("کیف پول ساده"),
        categoryId = CategoryId.constructFromPersisted(8),
        materialId = MaterialId.constructFromPersisted(1),
        materialDescription = null,
        bagType = BagType.WALLET,
        shortDescription = null,
        description = null,
        highlights = Highlights.NONE,
        specs = ProductSpecs.NONE,
        newUntil = null,
        maxPerOrder = MaxPerOrder.DEFAULT,
        returnable = true,
        reservable = true,
        sortPriority = 0,
    )

    val frontPhoto = ProductMedia.constructFromPersisted(
        MediaId.constructFromPersisted("med_5Hq2xT9aLmQ0vB3nR7sK1w"), MediaType.IMAGE, MediaView.FRONT, MediaAlt.constructFromPersisted("نمای جلوی کیف یاسی"),
    )
    val video = ProductMedia.constructFromPersisted(MediaId.constructFromPersisted("med_0000000000000000000002"), MediaType.VIDEO, MediaView.SIDE, null)

    val productCreated = ProductCreated(
        product, sequence(1), at, admin,
        slug = Slug.constructFromPersisted("mahak-shoulder-bag"),
        sku = Sku.constructFromPersisted("KV-1007"),
        details = fullDetails,
        priceTag = priceTag(1_750_000, 2_100_000),
        id = eventId(1),
    )

    val variantAdded = VariantAdded(
        product, sequence(2), at, admin,
        variantId = variant(21),
        colorKey = ColorKey.constructFromPersisted("lilac"),
        sku = Sku.constructFromPersisted("KV-1007-LILAC"),
        ownPriceTag = priceTag(1_450_000, 1_750_000),
        isDefault = true,
        sortOrder = 0,
        media = listOf(frontPhoto, video),
        barcode = Barcode.constructFromPersisted("6260000123456"),
        id = eventId(2),
    )

    /** One of every kind of product event. */
    val onePerKind: List<ProductDomainEvent> = listOf(
        productCreated,
        variantAdded,
        VariantChanged(product, sequence(3), at, admin, variant(21), ownPriceTag = priceTag(1_500_000, null), sortOrder = 2, isDefault = true, barcode = Barcode.constructFromPersisted("KV-BAR-1"), id = eventId(3)),
        VariantMediaReplaced(product, sequence(4), at, admin, variant(21), media = listOf(video, frontPhoto), id = eventId(4)),
        ProductDetailsChanged(product, sequence(5), at, admin, details = bareDetails, id = eventId(5)),
        ProductPriceChanged(product, sequence(6), at, admin, priceTag = priceTag(1_890_000, 2_000_000), id = eventId(6)),
        ProductPublished(product, sequence(7), at, admin, id = eventId(7)),
        VariantDeactivated(product, sequence(8), at, admin, variant(21), newDefaultVariantId = variant(22), id = eventId(8)),
        VariantReactivated(product, sequence(9), at, admin, variant(21), isDefault = true, id = eventId(9)),
        ProductUnpublished(product, sequence(10), at, UserId.SYSTEM, id = eventId(10)),
        ProductArchived(product, sequence(11), at, admin, id = eventId(11)),
        ProductPublishScheduled(product, sequence(12), at, admin, publishAt = Instant.parse("2026-10-10T06:30:00Z"), id = eventId(12)),
        ProductPublishUnscheduled(product, sequence(13), at, UserId.SYSTEM, id = eventId(13)),
    )

    /** The kinds that have optional parts, with all of them left empty. */
    val nothingOptional: List<ProductDomainEvent> = listOf(
        ProductCreated(product, sequence(1), at, admin, Slug.constructFromPersisted("simple-wallet"), Sku.constructFromPersisted("KV-1007"), bareDetails, priceTag(1_890_000, null), eventId(21)),
        VariantAdded(product, sequence(2), at, admin, variant(21), ColorKey.constructFromPersisted("black"), Sku.constructFromPersisted("KV-1007-BLACK"), null, false, 0, emptyList(), id = eventId(22)),
        VariantChanged(product, sequence(3), at, admin, variant(21), ownPriceTag = null, sortOrder = 0, isDefault = false, id = eventId(23)),
        VariantMediaReplaced(product, sequence(4), at, admin, variant(21), media = emptyList(), id = eventId(24)),
        VariantDeactivated(product, sequence(5), at, admin, variant(21), newDefaultVariantId = null, id = eventId(25)),
        VariantReactivated(product, sequence(6), at, admin, variant(21), isDefault = false, id = eventId(26)),
    )
}
