package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.PriceDto
import com.kiva.application.dto.ProductDetailDto
import com.kiva.domain.aggregate.product.model.valueobject.BadgeCode
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.ClosureType
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.product.model.valueobject.Occasion
import com.kiva.domain.aggregate.product.model.valueobject.PocketInfo
import com.kiva.domain.aggregate.product.model.valueobject.StockInfo
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus
import com.kiva.domain.aggregate.product.model.valueobject.StrapInfo
import com.kiva.domain.aggregate.product.model.valueobject.StrapType
import com.kiva.infrastructure.common.http.toPersianNumber

/**
 * The Persian words, tones and icons of a product's codes, as the prototype shows them. The domain gives only codes
 * (BadgeCode, StockStatus, ClosureType, ...); the words live here, like ErrorMessages for errors.
 */
object ProductTexts {

    /** One badge of a card: its label (with the discount or the number left when it has one), tone and icon. */
    fun badge(code: BadgeCode, price: PriceDto, stock: StockInfo): ProductBadgeHttpResponse = when (code) {
        BadgeCode.NEW -> ProductBadgeHttpResponse(code.name, "جدید", tone = "DEFAULT")
        BadgeCode.SALE -> ProductBadgeHttpResponse(code.name, "${price.discountPercent.toLong().toPersianNumber()}٪ تخفیف", tone = "SALE")
        BadgeCode.HAS_VIDEO -> ProductBadgeHttpResponse(code.name, "ویدیو دارد", tone = "GLASS", icon = "play")
        BadgeCode.FREE_SHIPPING -> ProductBadgeHttpResponse(code.name, "ارسال رایگان", tone = "DEFAULT")
        BadgeCode.SOLD_OUT -> ProductBadgeHttpResponse(code.name, "تمام شد", tone = "DARK")
        BadgeCode.LOW_STOCK -> ProductBadgeHttpResponse(code.name, "فقط ${shown(stock)} عدد", tone = "DANGER")
    }

    fun stockLabel(stock: StockInfo): String = when (stock.status) {
        StockStatus.IN_STOCK -> "موجود در انبار"
        StockStatus.LOW_STOCK -> "فقط ${shown(stock)} عدد در انبار باقی مونده"
        StockStatus.OUT_OF_STOCK -> "ناموجود"
    }

    fun bagType(bagType: BagType): String = when (bagType) {
        BagType.HOBO -> "دوشی هوبو"
        BagType.CROSSBODY -> "کراس‌بادی"
        BagType.BACKPACK -> "کوله"
        BagType.SATCHEL -> "دستی"
        BagType.CLUTCH -> "کلاچ / مجلسی"
        BagType.TOTE -> "شاپر / توت"
        BagType.BUCKET -> "سطلی"
        BagType.WALLET -> "کیف پول"
    }

    fun closure(type: ClosureType): String = when (type) {
        ClosureType.ZIPPER -> "زیپ فلزی"
        ClosureType.DOUBLE_ZIPPER -> "زیپ دوطرفه"
        ClosureType.ZIPPER_WITH_DECORATIVE_LOCK -> "زیپ + قفل تزئینی"
        ClosureType.MAGNETIC_FLAP -> "درب مگنتی"
        ClosureType.MAGNETIC_SNAP -> "دکمه مگنتی"
        ClosureType.DRAWSTRING -> "بند کشی"
        ClosureType.METAL_BUTTON -> "دکمه فلزی"
        ClosureType.OPEN_TOP -> "بدون درب"
    }

    fun occasion(occasion: Occasion): String = when (occasion) {
        Occasion.DAILY -> "روزمره"
        Occasion.UNIVERSITY -> "دانشگاه"
        Occasion.WORK -> "محل کار"
        Occasion.FORMAL -> "رسمی"
        Occasion.PARTY -> "مهمانی و مجالس"
        Occasion.TRAVEL -> "سفر"
        Occasion.SHOPPING -> "خرید"
        Occasion.GATHERING -> "دورهمی"
    }

    fun mediaView(view: MediaView): String = when (view) {
        MediaView.FRONT -> "نمای جلو"
        MediaView.SIDE -> "نمای کنار"
        MediaView.BACK -> "پشت"
        MediaView.DETAIL -> "جزئیات"
        MediaView.INTERIOR -> "داخل کیف"
        MediaView.STYLE -> "استایل"
        MediaView.ON_MODEL -> "روی مدل"
        MediaView.SCALE -> "مقایسه‌ی اندازه"
    }

    /**
     * The «مشخصات» tab, row by row in the contract's order. A spec nobody filled in has no row; the stock row is there
     * only when few are left or none, the only times a shopper is told about stock.
     */
    fun specTable(detail: ProductDetailDto): List<SpecRowHttpResponse> = buildList {
        val specs = detail.specs
        add(SpecRowHttpResponse("MATERIAL", "جنس", detail.materialDescription ?: detail.material.name))
        specs.dimensions?.let {
            val size = "${number(it.lengthCm)} × ${number(it.heightCm)} × ${number(it.widthCm)} سانتی‌متر"
            add(SpecRowHttpResponse("DIMENSIONS", "ابعاد (طول × ارتفاع × عرض)", size))
        }
        specs.weightGrams?.let { add(SpecRowHttpResponse("WEIGHT", "وزن", "${it.toLong().toPersianNumber()} گرم")) }
        specs.closure?.let { add(SpecRowHttpResponse("CLOSURE", "نوع بسته شدن", closure(it))) }
        if (specs.straps.isNotEmpty()) add(SpecRowHttpResponse("STRAP", "بند و دسته", specs.straps.joinToString("، ") { strap(it) }))
        specs.pockets?.let { add(SpecRowHttpResponse("POCKETS", "جیب‌ها", pockets(it))) }
        add(
            SpecRowHttpResponse(
                "COLORS", "رنگ‌های موجود", detail.variants.joinToString("، ") { it.color.name },
                swatches = detail.variants.map { ColorHttpResponse(it.color.key, it.color.name, it.color.hex, it.color.isLight, it.color.sortOrder) },
            ),
        )
        if (specs.occasions.isNotEmpty()) {
            add(SpecRowHttpResponse("OCCASIONS", "مناسب برای", specs.occasions.sorted().joinToString("، ") { occasion(it) }))
        }
        add(SpecRowHttpResponse("CATEGORY", "دسته‌بندی", detail.card.category.name))
        add(SpecRowHttpResponse("SKU", "کد محصول", detail.card.sku))
        when (detail.card.stock.status) {
            StockStatus.LOW_STOCK ->
                add(SpecRowHttpResponse("STOCK", "موجودی انبار", "فقط ${shown(detail.card.stock)} عدد باقی مانده", tone = "DANGER"))
            StockStatus.OUT_OF_STOCK -> add(SpecRowHttpResponse("STOCK", "موجودی انبار", "تمام شد", tone = "DARK"))
            StockStatus.IN_STOCK -> Unit
        }
        specs.laptopMaxInch?.let { add(SpecRowHttpResponse("LAPTOP", "جای لپ‌تاپ", "تا ${number(it)} اینچ")) }
        if (specs.waterproof) add(SpecRowHttpResponse("WATERPROOF", "ضدآب", "دارد"))
        specs.lining?.let { add(SpecRowHttpResponse("LINING", "آستر", listOfNotNull(it.material, it.color).joinToString("، "))) }
        specs.hardware?.let { add(SpecRowHttpResponse("HARDWARE", "یراق‌آلات", listOfNotNull(it.material, it.finish).joinToString("، "))) }
        specs.madeIn?.let { add(SpecRowHttpResponse("MADE_IN", "ساخت", it)) }
        specs.warranty?.let { add(SpecRowHttpResponse("WARRANTY", "ضمانت", it.description ?: "${it.months.toLong().toPersianNumber()} ماه")) }
    }

    fun strapType(type: StrapType): String = when (type) {
        StrapType.SHOULDER -> "بند دوشی"
        StrapType.CROSSBODY -> "بند کراس‌بادی"
        StrapType.TOP_HANDLE -> "دسته‌ی دستی"
        StrapType.CHAIN -> "بند زنجیری"
        StrapType.BACKPACK_STRAPS -> "بند کوله"
        StrapType.WRISTLET -> "بند مچی"
        StrapType.NONE -> "بدون بند"
    }

    /** The panel's label, or the kind and the length: «بند دوشی ۵۵ سانتی‌متر», «بند کراس‌بادی ۸۰ تا ۱۲۰ سانتی‌متر». */
    fun strap(strap: StrapInfo): String {
        strap.label?.let { return it }
        val kind = strap.type?.let(::strapType) ?: "بند"
        val min = strap.minLengthCm
        val max = strap.maxLengthCm
        val fixed = strap.lengthCm
        val length = when {
            min != null && max != null -> "${number(min)} تا ${number(max)} سانتی‌متر"
            fixed != null -> "${number(fixed)} سانتی‌متر"
            else -> null
        }
        return listOfNotNull(kind, length).joinToString(" ")
    }

    /** The panel's summary, or one made from the counts: «۱ جیب زیپ‌دار داخلی، ۲ جیب موبایل». */
    fun pockets(pockets: PocketInfo): String {
        pockets.summary?.let { return it }
        val counted = listOf(
            pockets.internal to "جیب داخلی",
            pockets.internalZip to "جیب زیپ‌دار داخلی",
            pockets.external to "جیب بیرونی",
            pockets.phone to "جیب موبایل",
            pockets.cardSlots to "جای کارت",
            pockets.billSlots to "جای اسکناس",
        ).filter { it.first > 0 }.map { (count, name) -> "${count.toLong().toPersianNumber()} $name" }
        val flags = listOfNotNull(
            "جیب پول خرد".takeIf { pockets.coinPocket },
            "کیف کوچک جداشدنی".takeIf { pockets.detachablePouch },
            "جای لپ‌تاپ".takeIf { pockets.laptopSleeve },
        )
        return (counted + flags).joinToString("، ").ifEmpty { "بدون جیب" }
    }

    /**
     * What «سؤال داری؟» puts in a messenger (roadmap section 15.6): «سلام، درباره «کیف دوشی ماهک» رنگ یاسی (کد KV-1001)
     * سؤال دارم. می‌شه عکس/ویدیوی بیشتری بفرستید؟». [code] is the product's own (KV-1001), as its page shows it.
     */
    fun inquiryText(name: String, colorName: String, code: String): String =
        "سلام، درباره «$name» رنگ $colorName (کد $code) سؤال دارم. می‌شه عکس/ویدیوی بیشتری بفرستید؟"

    // StockPolicy gives the number exactly when the status is LOW_STOCK.
    private fun shown(stock: StockInfo): String =
        (stock.shownQuantity ?: error("LOW_STOCK without a number: $stock")).toLong().toPersianNumber()

    /** 34.0 is «۳۴», 32.5 is «۳۲٫۵». */
    private fun number(value: Double): String {
        val whole = value.toLong()
        if (value == whole.toDouble()) return whole.toPersianNumber()
        val fraction = value.toBigDecimal().stripTrailingZeros().toPlainString().substringAfter('.')
        return whole.toPersianNumber() + "٫" + fraction.map { it.digitToInt().toLong().toPersianNumber() }.joinToString("")
    }
}
