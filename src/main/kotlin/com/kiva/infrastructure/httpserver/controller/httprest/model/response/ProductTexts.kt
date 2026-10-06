package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.PriceDto
import com.kiva.application.dto.ProductDetailDto
import com.kiva.domain.aggregate.product.model.valueobject.BadgeCode
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.ClosureType
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.product.model.valueobject.Occasion
import com.kiva.domain.aggregate.product.model.valueobject.StockInfo
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus
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
        specs.strap?.let { add(SpecRowHttpResponse("STRAP", "بند و دسته", it)) }
        specs.pockets?.let { add(SpecRowHttpResponse("POCKETS", "جیب‌ها", it)) }
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
    }

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
