package com.kiva.infrastructure.common.http

import com.kiva.common.railway.FailureKind

/**
 * The Persian text shown to the user for every error code (the `message` field of an error response).
 *
 * All user-facing error texts live here, in one place, so they can be reviewed and edited together.
 * Business code only produces codes; it never writes Persian sentences.
 *
 * A text may contain `{name}` placeholders filled from the failure's `meta`
 * (`{minSubtotal}` -> "۲٬۰۰۰٬۰۰۰"). Numbers are written with Persian digits and `٬` separators.
 * If a placeholder has no value, the general text for the failure's kind is used instead,
 * so a half-filled sentence never reaches the user.
 */
object ErrorMessages {

    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val NOT_FOUND = "NOT_FOUND"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
    const val CHANGED_BY_SOMEONE_ELSE = "CHANGED_BY_SOMEONE_ELSE"

    private val byCode: Map<String, String> = mapOf(
        // --- general (contract ErrorCode) ---
        VALIDATION_ERROR to "چند تا از فیلدها رو باید درست کنی",
        "UNAUTHORIZED" to "برای ادامه اول وارد حسابت شو",
        "TOKEN_EXPIRED" to "نشستت تموم شده، دوباره وارد شو",
        "REFRESH_TOKEN_INVALID" to "نشستت تموم شده، دوباره وارد شو",
        "FORBIDDEN" to "به این بخش دسترسی نداری",
        NOT_FOUND to "چیزی که دنبالش بودی پیدا نشد",
        "RATE_LIMITED" to "یه کم صبر کن و دوباره امتحان کن",
        INTERNAL_ERROR to "یه مشکلی پیش اومد. چند لحظه بعد دوباره امتحان کن",
        CHANGED_BY_SOMEONE_ELSE to "این مورد همین الان توسط کس دیگه‌ای تغییر کرد. صفحه رو تازه کن و دوباره امتحان کن",
        // --- auth ---
        "PHONE_INVALID" to "موبایل معتبر وارد کن",
        "OTP_INVALID" to "کد درست نیست",
        "OTP_EXPIRED" to "کد منقضی شده، یه کد جدید بگیر",
        "OTP_TOO_MANY_ATTEMPTS" to "چند بار اشتباه زدی، یه کد جدید بگیر",
        "OTP_RESEND_TOO_SOON" to "{retryAfterSeconds} ثانیه دیگه می‌تونی دوباره کد بگیری",
        "PHONE_ALREADY_REGISTERED" to "این شماره مال یه حساب دیگه‌ست",
        // --- catalog & cart ---
        "PRODUCT_NOT_FOUND" to "این محصول پیدا نشد",
        "VARIANT_NOT_FOUND" to "این رنگ برای این محصول پیدا نشد",
        "PRODUCT_UNAVAILABLE" to "این محصول الان قابل خرید نیست",
        "OUT_OF_STOCK" to "این کالا تموم شده",
        "QUANTITY_EXCEEDS_STOCK" to "فقط {availableQuantity} عدد از این کالا موجوده",
        "QUANTITY_EXCEEDS_LIMIT" to "از این کالا بیشتر از این تعداد نمی‌شه خرید",
        "CART_NOT_FOUND" to "سبد خرید پیدا نشد",
        "CART_EMPTY" to "سبد خریدت خالیه",
        "CART_ITEM_NOT_FOUND" to "این کالا توی سبدت نیست",
        // --- discount ---
        "DISCOUNT_CODE_INVALID" to "این کد تخفیف معتبر نیست",
        "DISCOUNT_CODE_EXPIRED" to "مهلت این کد تخفیف تموم شده",
        "DISCOUNT_CODE_NOT_STARTED" to "این کد تخفیف هنوز فعال نشده",
        "DISCOUNT_MIN_SUBTOTAL_NOT_MET" to "این کد برای خرید بالای {minSubtotal} تومان فعاله",
        "DISCOUNT_FIRST_ORDER_ONLY" to "این کد فقط برای خرید اوله",
        "DISCOUNT_USAGE_LIMIT_REACHED" to "ظرفیت استفاده از این کد تموم شده",
        "DISCOUNT_LOGIN_REQUIRED" to "برای استفاده از این کد اول وارد حسابت شو",
        "DISCOUNT_NOT_APPLICABLE" to "این کد روی کالاهای سبدت اعمال نمی‌شه",
        // --- address & checkout ---
        "ADDRESS_NOT_FOUND" to "این آدرس پیدا نشد",
        "ADDRESS_LIMIT_REACHED" to "به سقف تعداد آدرس‌ها رسیدی",
        "CITY_NOT_IN_PROVINCE" to "این شهر توی استان انتخاب‌شده نیست",
        "SHIPPING_METHOD_UNAVAILABLE" to "این روش ارسال برای سفارشت در دسترس نیست",
        "MESSENGER_REQUIRED" to "یه پیام‌رسان برای دریافت عکس قبل از ارسال انتخاب کن",
        "MESSENGER_PHONE_INVALID" to "شماره‌ی پیام‌رسان معتبر نیست",
        "TERMS_NOT_ACCEPTED" to "برای ادامه باید قوانین رو بپذیری",
        "PRICE_CHANGED" to "قیمت‌ها تغییر کرده؛ مبلغ قابل پرداخت الان {payable} تومانه",
        "IDEMPOTENCY_KEY_REUSED" to "این درخواست قبلاً با اطلاعات دیگه‌ای ثبت شده",
        "RESERVATION_UNAVAILABLE" to "رزرو ۴ روزه برای این خرید در دسترس نیست",
        // --- orders & payment ---
        "ORDER_NOT_FOUND" to "سفارش پیدا نشد",
        "ORDER_NOT_CANCELLABLE" to "این سفارش دیگه قابل لغو نیست",
        "ORDER_NOT_RETURNABLE" to "این سفارش قابل مرجوع کردن نیست",
        "RETURN_WINDOW_EXPIRED" to "مهلت مرجوع کردن این سفارش تموم شده",
        "ORDER_ALREADY_PAID" to "این سفارش قبلاً پرداخت شده",
        "CHANGE_REQUEST_NOT_ALLOWED" to "سفارش ارسال شده و دیگه نمی‌شه تغییرش داد",
        "INVALID_STATUS_TRANSITION" to "این تغییر وضعیت مجاز نیست",
        "PAYMENT_NOT_FOUND" to "پرداخت پیدا نشد",
        "PAYMENT_FAILED" to "پرداخت انجام نشد. اگه مبلغی کم شده، خودکار برمی‌گرده",
        "PAYMENT_EXPIRED" to "مهلت پرداخت تموم شده",
        "PAYMENT_GATEWAY_UNAVAILABLE" to "درگاه پرداخت الان در دسترس نیست. چند دقیقه بعد امتحان کن",
        "TRACKING_NOT_FOUND" to "سفارشی با این شماره و موبایل پیدا نکردیم.",
        // --- reviews, alerts, wishlist, uploads, admin ---
        "REVIEW_ALREADY_EXISTS" to "برای این محصول یه نظر در انتظار تأیید داری",
        "REVIEW_NOT_EDITABLE" to "این نظر دیگه قابل ویرایش نیست",
        "STOCK_ALERT_EXISTS" to "قبلاً برای موجود شدن این کالا عضو شدی",
        "PRODUCT_IN_STOCK" to "این کالا الان موجوده",
        "WISHLIST_LIMIT_REACHED" to "لیست علاقه‌مندی‌هات پر شده",
        "UPLOAD_TOO_LARGE" to "حجم فایل زیاده",
        "UPLOAD_TYPE_NOT_ALLOWED" to "این نوع فایل پذیرفته نمی‌شه",
        "DUPLICATE_SLUG" to "این آدرس (slug) قبلاً استفاده شده",
        "DUPLICATE_SKU" to "این کد کالا (SKU) قبلاً استفاده شده",
        // --- field-level codes produced by common and the domain value objects ---
        "PHONE_REQUIRED" to "موبایلت رو وارد کن",
        "MONEY_NEGATIVE" to "مبلغ نمی‌تونه منفی باشه",
        "PAGE_INVALID" to "شماره‌ی صفحه درست نیست",
        "PAGE_SIZE_INVALID" to "تعداد آیتم‌های هر صفحه حداکثر {maxSize} تاست",
        "SORT_NOT_ALLOWED" to "این نوع مرتب‌سازی پشتیبانی نمی‌شه",
        "USER_ID_INVALID" to "شناسه‌ی کاربر درست نیست",
        "SLUG_INVALID" to "آدرس (slug) فقط حروف کوچک انگلیسی، رقم و خط تیره می‌گیره و باید حداقل یه حرف داشته باشه",
        "COLOR_KEY_INVALID" to "کلید رنگ باید با حرف کوچک انگلیسی شروع بشه و فقط حروف کوچک، رقم و خط تیره داشته باشه",
        "CATEGORY_ID_INVALID" to "دسته‌بندی انتخاب‌شده درست نیست",
        "MATERIAL_ID_INVALID" to "متریال انتخاب‌شده درست نیست",
        "HEX_COLOR_INVALID" to "کد رنگ باید به شکل #RRGGBB باشه، مثل #C8B6E2",
        "COLOR_NAME_REQUIRED" to "اسم رنگ رو وارد کن",
        "COLOR_NAME_TOO_LONG" to "اسم رنگ حداکثر {maxLength} حرفه",
        "CATEGORY_NAME_REQUIRED" to "اسم دسته‌بندی رو وارد کن",
        "CATEGORY_NAME_TOO_LONG" to "اسم دسته‌بندی حداکثر {maxLength} حرفه",
        "MATERIAL_NAME_REQUIRED" to "اسم متریال رو وارد کن",
        "MATERIAL_NAME_TOO_LONG" to "اسم متریال حداکثر {maxLength} حرفه",
        "PRODUCT_ID_INVALID" to "محصول انتخاب‌شده درست نیست",
        "VARIANT_ID_INVALID" to "رنگ انتخاب‌شده درست نیست",
        "SKU_INVALID" to "کد کالا (SKU) باید با حرف انگلیسی شروع بشه و فقط حرف، رقم و خط تیره داشته باشه",
        "PRODUCT_NAME_REQUIRED" to "اسم محصول رو وارد کن",
        "PRODUCT_NAME_TOO_LONG" to "اسم محصول حداکثر {maxLength} حرفه",
        "MATERIAL_DESCRIPTION_TOO_LONG" to "توضیح جنس حداکثر {maxLength} حرفه",
        "SHORT_DESCRIPTION_TOO_LONG" to "توضیح کوتاه حداکثر {maxLength} حرفه",
        "DESCRIPTION_TOO_LONG" to "توضیحات حداکثر {maxLength} حرفه",
        "HIGHLIGHTS_TOO_MANY" to "حداکثر {maxItems} ویژگی می‌شه نوشت",
        "HIGHLIGHT_TOO_LONG" to "هر ویژگی حداکثر {maxLength} حرفه",
        "MAX_PER_ORDER_INVALID" to "سقف تعداد در هر سفارش باید حداقل ۱ باشه",
        "DIMENSIONS_INVALID" to "ابعاد باید عدد مثبت و حداکثر {maxCm} سانتی‌متر باشه",
        "WEIGHT_INVALID" to "وزن باید عدد مثبت و حداکثر {maxGrams} گرم باشه",
        "STRAP_TOO_LONG" to "توضیح بند حداکثر {maxLength} حرفه",
        "POCKETS_TOO_LONG" to "توضیح جیب‌ها حداکثر {maxLength} حرفه",
        "MEDIA_URL_INVALID" to "آدرس فایل باید یه آدرس کامل http یا https باشه",
        "MEDIA_ALT_TOO_LONG" to "متن جایگزین عکس حداکثر {maxLength} حرفه",
        "COMPARE_AT_PRICE_NOT_ABOVE_PRICE" to "قیمت قبل از تخفیف باید از قیمت فروش بیشتر باشه",
        "PRODUCT_ARCHIVED" to "این محصول آرشیو شده و دیگه قابل ویرایش نیست",
        "PRODUCT_ALREADY_ACTIVE" to "این محصول از قبل منتشر شده",
        "PRODUCT_ALREADY_DRAFT" to "این محصول از قبل پیش‌نویسه",
        "PRODUCT_ALREADY_ARCHIVED" to "این محصول از قبل آرشیو شده",
        "NO_ACTIVE_VARIANT" to "برای انتشار، محصول حداقل یه رنگ فعال لازم داره",
        "COLOR_ALREADY_ON_PRODUCT" to "این رنگ قبلاً برای این محصول تعریف شده",
        "INACTIVE_VARIANT_CANNOT_BE_DEFAULT" to "رنگ غیرفعال نمی‌تونه رنگ پیش‌فرض باشه",
        "VARIANT_ALREADY_INACTIVE" to "این رنگ از قبل غیرفعاله",
        "VARIANT_ALREADY_ACTIVE" to "این رنگ از قبل فعاله",
        "CATEGORY_NOT_FOUND" to "این دسته‌بندی وجود نداره",
        "MATERIAL_NOT_FOUND" to "این متریال وجود نداره",
        "COLOR_NOT_FOUND" to "این رنگ تو پالت رنگ‌ها نیست",
        "LAST_ACTIVE_VARIANT" to "محصول منتشرشده حداقل یه رنگ فعال لازم داره؛ اول محصول رو از انتشار دربیار",
        "QUANTITY_INVALID" to "تعداد باید بین ۱ و {max} باشه",
        "ORDER_CODE_INVALID" to "کد سفارش باید به شکل KV-218340 باشه",
        "STOCK_DELTA_INVALID" to "تغییر موجودی باید یه عدد غیرصفر و حداکثر {maxAbs} باشه (مثبت برای ورود، منفی برای خروج)",
        "ADJUSTMENT_NOTE_TOO_LONG" to "یادداشت حداکثر {maxLength} حرفه",
        "ADJUSTMENT_REASON_INVALID" to "دلیل تغییر باید یکی از RESTOCK، STOCKTAKE، DAMAGED، RETURNED یا MANUAL_CORRECTION باشه",
        "ADJUSTMENT_DIRECTION_INVALID" to "این دلیل با جهت تغییر جور نیست: ورود کالا و مرجوعی فقط اضافه می‌کنن و آسیب‌دیده فقط کم می‌کنه",
        "RESERVATION_QUANTITY_MISMATCH" to "برای این سفارش قبلاً تعداد دیگه‌ای رزرو شده",
        "ORDER_ALREADY_SHIPPED" to "این سفارش قبلاً ارسال شده",
        "RESERVATION_NOT_FOUND" to "برای این سفارش چیزی از این رنگ رزرو نشده",
        "BAG_TYPE_INVALID" to "نوع کیف باید یکی از HOBO، CROSSBODY، BACKPACK، SATCHEL، CLUTCH، TOTE، BUCKET یا WALLET باشه",
        "CLOSURE_TYPE_INVALID" to "نوع بسته شدن کیف از گزینه‌های مجاز نیست",
        "OCCASION_INVALID" to "«مناسب برای» از گزینه‌های مجاز نیست",
        "PRODUCT_STATUS_INVALID" to "وضعیت محصول باید DRAFT، ACTIVE یا ARCHIVED باشه",
        "PRICE_OVERRIDE_REQUIRED" to "قیمت قبل از تخفیف این رنگ بدون قیمت فروش خودش معنی نداره",
        "SEARCH_QUERY_TOO_LONG" to "متن جستجو حداکثر {maxLength} حرفه",
        "IDS_TOO_MANY" to "حداکثر {maxItems} محصول رو می‌شه با هم خواست",
        "NUMBER_INVALID" to "اینجا باید یه عدد بدون اعشار بنویسی",
        "PRICE_RANGE_INVALID" to "حداقل قیمت نمی‌تونه از حداکثر قیمت بیشتر باشه",
        "LIMIT_INVALID" to "تعداد باید عددی بین ۱ و {max} باشه",
    )

    // Used when a code has no text of its own (or its placeholders can't be filled).
    private val byKind: Map<FailureKind, String> = mapOf(
        FailureKind.INVALID to "این مقدار درست نیست",
        FailureKind.UNAUTHORIZED to byCode.getValue("UNAUTHORIZED"),
        FailureKind.FORBIDDEN to byCode.getValue("FORBIDDEN"),
        FailureKind.NOT_FOUND to byCode.getValue(NOT_FOUND),
        FailureKind.CONFLICT to "این مورد قبلاً ثبت شده یا در این فاصله تغییر کرده",
        FailureKind.BUSINESS_RULE to "این کار الان ممکن نیست",
        FailureKind.RATE_LIMITED to byCode.getValue("RATE_LIMITED"),
    )

    /** Codes that have their own text. */
    val knownCodes: Set<String> get() = byCode.keys

    fun messageFor(code: String, kind: FailureKind? = null, meta: Map<String, Any> = emptyMap()): String {
        val fallback = kind?.let(byKind::getValue) ?: byCode.getValue(INTERNAL_ERROR)
        val template = byCode[code] ?: return fallback
        return fill(template, meta) ?: fallback
    }

    private val PLACEHOLDER = Regex("\\{(\\w+)}")

    /** Fills `{name}` from [meta]; null if any placeholder has no value. */
    private fun fill(template: String, meta: Map<String, Any>): String? {
        var missing = false
        val text = PLACEHOLDER.replace(template) { match ->
            val value = meta[match.groupValues[1]]
            if (value == null) {
                missing = true
                ""
            } else {
                format(value)
            }
        }
        return text.takeUnless { missing }
    }

    private fun format(value: Any): String = when (value) {
        is Long -> value.toPersianNumber()
        is Int -> value.toLong().toPersianNumber()
        is Short -> value.toLong().toPersianNumber()
        is Byte -> value.toLong().toPersianNumber()
        else -> value.toString()
    }
}

private const val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"
private const val PERSIAN_THOUSANDS_SEPARATOR = '٬'

/** 2000000 -> "۲٬۰۰۰٬۰۰۰" */
internal fun Long.toPersianNumber(): String {
    val digits = toString().removePrefix("-")
    val grouped = digits.reversed().chunked(3).joinToString(PERSIAN_THOUSANDS_SEPARATOR.toString()).reversed()
    val persian = grouped.map { if (it.isDigit()) PERSIAN_DIGITS[it - '0'] else it }.joinToString("")
    return if (this < 0) "-$persian" else persian
}
