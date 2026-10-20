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
        "PHONE_SAME_AS_CURRENT" to "این همین شماره‌ی فعلی حسابته",
        "ACCOUNT_HAS_ACTIVE_ORDERS" to "تا وقتی سفارش جاری داری نمی‌شه حساب رو حذف کرد",
        "ROLE_CHANGE_NOT_ALLOWED" to "این تغییر نقش مجاز نیست: نقش‌های خودت رو نمی‌تونی عوض کنی، و آخرین ادمین هم باید ادمین بمونه",
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
        "DISCOUNT_CODE_REQUIRED" to "کد تخفیف رو وارد کن",
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
        "CHANGE_REQUEST_NOT_ALLOWED" to "الان نمی‌شه برای این سفارش درخواست تغییر داد؛ فقط بعد از دیدن عکس و قبل از ارسال، و یکی یکی",
        "INVALID_STATUS_TRANSITION" to "این تغییر وضعیت مجاز نیست",
        "PAYMENT_NOT_FOUND" to "پرداخت پیدا نشد",
        "PAYMENT_FAILED" to "پرداخت انجام نشد. اگه مبلغی کم شده، خودکار برمی‌گرده",
        "PAYMENT_EXPIRED" to "مهلت پرداخت تموم شده",
        "PAYMENT_GATEWAY_UNAVAILABLE" to "درگاه پرداخت الان در دسترس نیست. چند دقیقه بعد امتحان کن",
        "TRACKING_NOT_FOUND" to "سفارشی با این شماره و موبایل پیدا نکردیم.",
        "BALANCE_DUE" to "این سفارش هنوز {balanceDue} تومان مانده‌ی پرداخت داره و تا پرداخت نشه ارسال نمی‌شه",
        "AWAITING_CUSTOMER_FEEDBACK" to "مشتری هنوز عکس رو تأیید نکرده و مهلت جوابش تموم نشده؛ بعد از تأیید یا پایان مهلت ارسال کن",
        "GROUP_NOT_READY" to "همه‌ی سفارش‌های این گروه هنوز آماده‌ی ارسال نیستن؛ گروه با هم ارسال می‌شه",
        "PHOTOS_INCOMPLETE" to "برای هر کالای سفارش حداقل یه عکس لازمه",
        "REFUND_NOT_FOUND" to "این استرداد پیدا نشد",
        "PAYMENT_IN_PROGRESS" to "یه پرداخت این سفارش همین الان در حال تأییده؛ چند دقیقه‌ی دیگه دوباره امتحان کن",
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
        "IN_USE" to "این مورد هنوز روی محصول یا جای دیگه‌ای استفاده شده، برای همین حذف نمی‌شه",
        "DUPLICATE_CODE" to "این کد قبلاً استفاده شده",
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
        "MAX_PER_ORDER_INVALID" to "سقف تعداد در هر سفارش باید بین ۱ و {max} باشه",
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
        "ORDER_FILTER_INVALID" to "فیلتر سفارش‌ها باید یکی از all، current، delivered یا cancelled باشه",
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
        "STOCK_STATUS_INVALID" to "وضعیت موجودی باید IN_STOCK، LOW_STOCK یا OUT_OF_STOCK باشه",
        "SEARCH_QUERY_TOO_LONG" to "متن جستجو حداکثر {maxLength} حرفه",
        "IDS_TOO_MANY" to "حداکثر {maxItems} محصول رو می‌شه با هم خواست",
        "NUMBER_INVALID" to "اینجا باید یه عدد بدون اعشار بنویسی",
        "BOOLEAN_INVALID" to "اینجا فقط true یا false قبول می‌شه",
        "SHIPPING_METHOD_INVALID" to "روش ارسال باید یکی از TIPAX، POST یا COURIER باشه",
        "QUANTITY_OR_VARIANT_REQUIRED" to "تعداد تازه یا رنگ تازه رو بفرست",
        "PAYMENT_GATEWAY_INVALID" to "درگاه پرداخت باید یکی از ZARINPAL، SAMAN یا MELLAT باشه",
        "PROVINCE_ID_INVALID" to "استان انتخاب‌شده درست نیست",
        "CITY_ID_INVALID" to "شهر انتخاب‌شده درست نیست",
        "PROVINCE_REQUIRED" to "استان رو انتخاب کن",
        "CITY_REQUIRED" to "شهر رو انتخاب کن",
        "PROVINCE_NOT_FOUND" to "این استان توی فهرست نیست",
        "CITY_NOT_FOUND" to "این شهر توی فهرست نیست",
        "ADDRESS_ID_INVALID" to "آدرس انتخاب‌شده درست نیست",
        "ADDRESS_REQUIRED" to "یه آدرس انتخاب کن یا آدرس تازه بنویس",
        "ADDRESS_AMBIGUOUS" to "یا آدرس ذخیره‌شده رو بفرست یا آدرس تازه رو، نه هر دو",
        "SHIPPING_METHOD_REQUIRED" to "روش ارسال رو انتخاب کن",
        "PAYMENT_GATEWAY_REQUIRED" to "درگاه پرداخت رو انتخاب کن",
        "ADDRESS_LINE_TOO_SHORT" to "آدرس رو کامل‌تر بنویس (حداقل {minLength} حرف)",
        "ADDRESS_LINE_TOO_LONG" to "آدرس حداکثر {maxLength} حرفه",
        "ADDRESS_LINE_INVALID" to "آدرس نمی‌تونه علامت‌هایی مثل < و > داشته باشه",
        "POSTAL_CODE_INVALID" to "کد پستی باید ۱۰ رقم باشه",
        "RECIPIENT_NAME_REQUIRED" to "اسم گیرنده رو بنویس",
        "RECIPIENT_NAME_TOO_SHORT" to "اسم گیرنده حداقل {minLength} حرفه",
        "RECIPIENT_NAME_TOO_LONG" to "اسم گیرنده حداکثر {maxLength} حرفه",
        "RECIPIENT_NAME_INVALID" to "اسم گیرنده نمی‌تونه علامت‌هایی مثل < و > داشته باشه",
        "ADDRESS_TITLE_TOO_LONG" to "عنوان آدرس حداکثر {maxLength} حرفه",
        "ADDRESS_TITLE_INVALID" to "عنوان آدرس نمی‌تونه علامت‌هایی مثل < و > داشته باشه",
        "PLAQUE_TOO_LONG" to "پلاک حداکثر {maxLength} حرفه",
        "PLAQUE_INVALID" to "پلاک نمی‌تونه علامت‌هایی مثل < و > داشته باشه",
        "UNIT_TOO_LONG" to "واحد حداکثر {maxLength} حرفه",
        "UNIT_INVALID" to "واحد نمی‌تونه علامت‌هایی مثل < و > داشته باشه",
        "LOCATION_INVALID" to "موقعیت روی نقشه درست نیست",
        "IDEMPOTENCY_KEY_REQUIRED" to "شناسه‌ی درخواست (Idempotency-Key) فرستاده نشده",
        "IDEMPOTENCY_KEY_INVALID" to "شناسه‌ی درخواست (Idempotency-Key) باید یه UUID باشه",
        "MESSENGER_USERNAME_TOO_LONG" to "آیدی پیام‌رسان حداکثر {maxLength} حرفه",
        "MESSENGER_USERNAME_INVALID" to "آیدی پیام‌رسان علامت نامعتبر داره",
        "MESSENGER_NOTE_TOO_LONG" to "توضیح برای عکس حداکثر {maxLength} حرفه",
        "MESSENGER_NOTE_INVALID" to "توضیح برای عکس علامت نامعتبر داره",
        "CUSTOMER_NOTE_TOO_LONG" to "توضیح سفارش حداکثر {maxLength} حرفه",
        "CUSTOMER_NOTE_INVALID" to "توضیح سفارش علامت نامعتبر داره",
        "GIFT_MESSAGE_TOO_LONG" to "پیام هدیه حداکثر {maxLength} حرفه",
        "GIFT_MESSAGE_INVALID" to "پیام هدیه علامت نامعتبر داره",
        "PRICE_RANGE_INVALID" to "حداقل قیمت نمی‌تونه از حداکثر قیمت بیشتر باشه",
        "LIMIT_INVALID" to "تعداد باید عددی بین ۱ و {max} باشه",
        "PERSON_NAME_REQUIRED" to "اسم نمی‌تونه خالی باشه",
        "PERSON_NAME_TOO_LONG" to "اسم حداکثر {maxLength} حرفه",
        "PERSON_NAME_INVALID" to "اسم نمی‌تونه علامت‌هایی مثل < و > داشته باشه",
        "EMAIL_INVALID" to "ایمیل معتبر وارد کن، مثل sara@example.com",
        "EMAIL_TOO_LONG" to "ایمیل حداکثر {maxLength} حرفه",
        "BIRTH_DATE_INVALID" to "تاریخ تولد درست نیست",
        "GENDER_INVALID" to "جنسیت باید یکی از FEMALE، MALE یا UNSPECIFIED باشه",
        "MESSENGER_CHANNEL_INVALID" to "پیام‌رسان باید یکی از RUBIKA، TELEGRAM یا BALE باشه",
        "ROLE_INVALID" to "نقش باید یکی از CUSTOMER، ADMIN، CATALOG_MANAGER، OPERATOR، CONTENT_EDITOR یا SUPPORT باشه",
        "OTP_CODE_FORMAT_INVALID" to "کد تأیید {codeLength} رقمیه",
        "OTP_PURPOSE_INVALID" to "از اینجا فقط کد ورود (LOGIN) فرستاده می‌شه",
        // --- panel: catalog, media and the change log (roadmap phase 1) ---
        "FIELD_REQUIRED" to "این فیلد رو باید پر کنی",
        "SLUG_IMMUTABLE" to "آدرس (slug) بعد از ساخت عوض نمی‌شه؛ برای آدرس دیگه، محصول تازه بساز",
        "SKU_IMMUTABLE" to "کد کالا (SKU) بعد از ساخت عوض نمی‌شه",
        "COLOR_KEY_IMMUTABLE" to "کلید رنگ عوض نمی‌شه؛ برای رنگ دیگه، رنگ تازه اضافه کن",
        "IF_MATCH_INVALID" to "نسخه‌ی فرستاده‌شده (If-Match) درست نیست؛ صفحه رو تازه کن",
        "PUBLISH_AT_NOT_ALLOWED" to "زمان انتشار فقط برای منتشر کردن یه محصول پیش‌نویس معنی داره",
        "PUBLISH_AT_TOO_FAR" to "زمان انتشار حداکثر {maxDays} روز بعده",
        "MEDIA_ID_INVALID" to "شناسه‌ی فایل درست نیست",
        "MEDIA_NOT_FOUND" to "این فایل پیدا نشد؛ دوباره آپلودش کن",
        "MEDIA_PURPOSE_MISMATCH" to "این فایل برای جای دیگه‌ای آپلود شده و اینجا نمی‌شه ازش استفاده کرد",
        "MEDIA_TYPE_NOT_ALLOWED" to "اینجا فقط عکس قبول می‌شه، نه ویدیو",
        "MEDIA_VIEW_INVALID" to "زاویه‌ی عکس باید یکی از FRONT، SIDE، BACK، DETAIL، INTERIOR، STYLE، ON_MODEL یا SCALE باشه",
        "MEDIA_PRIMARY_DUPLICATE" to "فقط یه عکس می‌تونه عکس اصلی باشه",
        "MEDIA_DUPLICATE" to "یه فایل دو بار توی گالری اومده",
        "MEDIA_TOO_MANY" to "هر رنگ حداکثر {maxItems} عکس و ویدیو می‌گیره",
        "MEDIA_INVALID" to "یکی از فایل‌های گالری مشکل داره",
        "MEDIA_PURPOSE_INVALID" to "نوع آپلود (purpose) از گزینه‌های مجاز نیست",
        "IMAGE_TOO_SMALL" to "عکس خیلی کوچیکه؛ ضلع کوتاهش باید حداقل {minSide} پیکسل باشه",
        "IMAGE_TOO_LARGE" to "عکس خیلی بزرگه؛ حداکثر {maxMegapixels} مگاپیکسل قبول می‌شه",
        "VIDEO_TOO_LONG" to "ویدیو حداکثر {maxSeconds} ثانیه می‌تونه باشه",
        "FILE_COUNT_INVALID" to "دقیقاً یه فایل بفرست",
        "STOCK_QUANTITY_INVALID" to "موجودی اولیه باید بین ۰ و {max} باشه",
        "BARCODE_INVALID" to "بارکد باید ۴ تا ۳۲ حرف لاتین، رقم یا خط تیره باشه",
        "TAG_INVALID" to "هر برچسب باید ۱ تا {maxLength} حرف باشه",
        "TAGS_TOO_MANY" to "حداکثر {maxItems} برچسب می‌شه گذاشت",
        "SEO_TITLE_TOO_LONG" to "عنوان سئو حداکثر {maxLength} حرفه",
        "SEO_DESCRIPTION_TOO_LONG" to "توضیح سئو حداکثر {maxLength} حرفه",
        "MATERIAL_FAMILY_INVALID" to "خانواده‌ی متریال از گزینه‌های مجاز نیست",
        "CATEGORY_DESCRIPTION_TOO_LONG" to "توضیح دسته‌بندی حداکثر {maxLength} حرفه",
        "MATERIAL_NOTE_TOO_LONG" to "توضیح متریال حداکثر {maxLength} حرفه",
        "CARE_INSTRUCTIONS_INVALID" to "حداکثر {maxItems} دستور نگه‌داری، هر کدوم ۱ تا {maxLength} حرف",
        "DATE_INVALID" to "تاریخ باید به شکل YYYY-MM-DD باشه",
        "DATE_TIME_INVALID" to "زمان باید به شکل کامل با منطقه‌ی زمانی باشه، مثل 2026-11-01T10:00:00+03:30",
        "DATE_RANGE_INVALID" to "تاریخ پایان نمی‌تونه قبل از تاریخ شروع باشه",
        "AUDIT_ENTITY_TYPE_INVALID" to "نوع مورد از گزینه‌های مجاز نیست",
        "ENTITY_ID_TOO_LONG" to "شناسه حداکثر {maxLength} حرفه",
        // --- the full specs of a product (each under its own path, like specs.straps.1.lengthCm) ---
        "SPECS_INVALID" to "چند تا از مشخصات محصول مشکل داره",
        "STRAPS_TOO_MANY" to "حداکثر {maxItems} بند می‌شه تعریف کرد",
        "STRAP_TYPE_INVALID" to "نوع بند از گزینه‌های مجاز نیست",
        "STRAP_LENGTH_INVALID" to "طول بند باید عدد مثبت و حداکثر {maxCm} سانتی‌متر باشه",
        "STRAP_LENGTH_RANGE_INVALID" to "کمترین طول بند نمی‌تونه از بیشترینش بیشتر باشه",
        "STRAP_MATERIAL_TOO_LONG" to "جنس بند حداکثر {maxLength} حرفه",
        "POCKET_COUNT_INVALID" to "تعداد جیب باید بین ۰ و {max} باشه",
        "LAPTOP_MAX_INCH_INVALID" to "اندازه‌ی لپ‌تاپ باید عدد مثبت و حداکثر {max} اینچ باشه",
        "CAPACITY_LITERS_INVALID" to "حجم باید عدد مثبت و حداکثر {max} لیتر باشه",
        "FIT_ITEM_INVALID" to "«چی توش جا می‌شه» از گزینه‌های مجاز نیست",
        "LINING_MATERIAL_TOO_LONG" to "جنس آستر حداکثر {maxLength} حرفه",
        "LINING_COLOR_TOO_LONG" to "رنگ آستر حداکثر {maxLength} حرفه",
        "HARDWARE_MATERIAL_TOO_LONG" to "جنس یراق حداکثر {maxLength} حرفه",
        "HARDWARE_FINISH_TOO_LONG" to "پوشش یراق حداکثر {maxLength} حرفه",
        "STITCHING_TOO_LONG" to "توضیح دوخت حداکثر {maxLength} حرفه",
        "MADE_IN_TOO_LONG" to "محل تولید حداکثر {maxLength} حرفه",
        "WARRANTY_MONTHS_INVALID" to "مدت ضمانت باید بین ۱ و {max} ماه باشه",
        "WARRANTY_DESCRIPTION_TOO_LONG" to "توضیح ضمانت حداکثر {maxLength} حرفه",
        // --- work on orders in the panel (phase 2) ---
        "NOTE_REQUIRED" to "توضیحش رو بنویس",
        "NOTE_TOO_LONG" to "یادداشت حداکثر {maxLength} حرفه",
        "ORDER_ITEM_NOT_FOUND" to "این کالا توی این سفارش نیست",
        "MANUAL_ONLY" to "ارسال عکس فقط دستیه؛ عکس‌ها رو توی پیام‌رسان بفرست و بعد ثبت کن",
        "CARRIER_MISMATCH" to "روش ارسال باید همون روش سفارش باشه",
        "CARRIER_INVALID" to "روش ارسال باید یکی از TIPAX، POST یا COURIER باشه",
        "SHIPPED_AT_INVALID" to "زمان تحویل به حامل نمی‌تونه در آینده، بیشتر از ۷ روز پیش یا قبل از ارسال عکس باشه",
        "TRACKING_CODE_REQUIRED" to "کد رهگیری رو بنویس",
        "TRACKING_CODE_INVALID" to "کد رهگیری باید ۶ تا ۴۰ حرف و رقم انگلیسی (یا خط تیره) باشه",
        "REFUND_EXCEEDS_PAID" to "مبلغ استرداد بیشتر از مبلغ قابل برگشته؛ حداکثر {refundable} تومان",
        "REFUND_AMOUNT_INVALID" to "مبلغ استرداد باید بیشتر از صفر باشه",
        "REFUND_METHOD_INVALID" to "روش استرداد باید ORIGINAL_PAYMENT یا BANK_TRANSFER باشه",
        "REFUND_STATUS_INVALID" to "وضعیت استرداد باید PROCESSING، COMPLETED یا FAILED باشه",
        "REFUND_REASON_REQUIRED" to "دلیل استرداد رو بنویس",
        "REFUND_REASON_TOO_LONG" to "دلیل استرداد حداکثر {maxLength} حرفه",
        "REFUND_NOTE_TOO_LONG" to "توضیح استرداد حداکثر {maxLength} حرفه",
        "REFERENCE_ID_REQUIRED" to "برای «انجام شد» شماره‌ی پیگیری بانک لازمه",
        "REFERENCE_ID_INVALID" to "شماره‌ی پیگیری حداکثر {maxLength} حرف و رقم انگلیسی (یا خط تیره) باشه",
        "RETURN_NOT_FOUND" to "این مرجوعی پیدا نشد",
        "ORDER_STATUS_INVALID" to "وضعیت سفارش از مقدارهای مجاز نیست",
        "CANCEL_REASON_INVALID" to "دلیل لغو از گزینه‌های مجاز نیست",
        "STATUS_NOTE_INVALID" to "توضیح علامت نامعتبر داره",
        "STATUS_NOTE_TOO_LONG" to "توضیح حداکثر {maxLength} حرفه",
        "PHOTO_MESSAGE_TOO_LONG" to "پیام حداکثر {maxLength} حرفه",
        "PHOTOS_REQUIRED" to "حداقل یه عکس یا ویدیو انتخاب کن",
        "PHOTOS_TOO_MANY" to "حداکثر {maxItems} عکس و ویدیو برای هر سفارش",
        "SORT_ORDER_INVALID" to "ترتیب باید عدد صفر یا مثبت باشه",
        "FEEDBACK_DECISION_INVALID" to "پاسخ باید APPROVE یا REQUEST_CHANGE باشه",
        "REFUND_STATUS_FILTER_INVALID" to "وضعیت استرداد باید PENDING یا PROCESSING باشه",
        "DAYS_INVALID" to "تعداد روزها باید بین ۱ و {max} باشه",
        "TRACKING_QUERY_TOO_LONG" to "متن جستجو حداکثر {maxLength} حرفه",
        // --- a change request after the photos, and paying what is left (phase 3) ---
        "CHANGE_TYPE_REQUIRED" to "بگو چه تغییری می‌خوای",
        "CHANGE_TYPE_INVALID" to "نوع تغییر باید COLOR، MODEL، CANCEL_ITEM یا OTHER باشه",
        "ORDER_ITEM_REQUIRED" to "کالایی که می‌خوای تغییر کنه رو انتخاب کن",
        "DESIRED_VARIANT_REQUIRED" to "رنگ یا مدلی که می‌خوای رو انتخاب کن",
        "DESIRED_VARIANT_INVALID" to "این رنگ یا مدل برای این تغییر درست نیست",
        "RESOLUTION_DECISION_INVALID" to "تصمیم باید APPLY یا REJECT باشه",
        "VARIANT_NOT_ALLOWED" to "رنگ جایگزین فقط برای اجرای تغییر رنگ یا مدل معنی داره",
        // --- the shop's settings, discount codes and the report in the panel (phase 4) ---
        "NUMBER_OUT_OF_RANGE" to "این عدد باید بین {min} و {max} باشه",
        "TEXT_TOO_LONG" to "این متن حداکثر {maxLength} حرفه",
        "TOO_MANY_ITEMS" to "حداکثر {maxItems} مورد می‌شه گذاشت",
        "LIST_ITEM_DUPLICATE" to "یه مورد دو بار اومده",
        "SHIPPING_METHOD_LIST_INVALID" to "لیست روش‌های ارسال باید TIPAX و POST رو هر کدوم دقیقاً یه بار داشته باشه",
        "SHIPPING_METHOD_NOT_SUPPORTED" to "ارسال با پیک هنوز راه نیفتاده",
        "NO_ACTIVE_SHIPPING_METHOD" to "حداقل یه روش ارسال باید فعال بمونه",
        "DEFAULT_SHIPPING_METHOD_INVALID" to "روش ارسال پیش‌فرض باید یکی از روش‌های فعال باشه",
        "ICON_INVALID" to "اسم آیکن فقط حروف کوچک انگلیسی، رقم و خط تیره می‌گیره (حداکثر ۳۰ حرف)",
        "TRACKING_URL_TEMPLATE_INVALID" to "آدرس رهگیری باید با https:// شروع بشه، {code} رو داشته باشه و حداکثر ۳۰۰ حرف باشه",
        "PAYMENT_GATEWAY_LIST_INVALID" to "لیست درگاه‌ها باید هر سه درگاه رو هر کدوم دقیقاً یه بار داشته باشه",
        "NO_ACTIVE_GATEWAY" to "حداقل یه درگاه پرداخت باید فعال بمونه",
        "DEFAULT_GATEWAY_INVALID" to "دقیقاً یه درگاه باید پیش‌فرض باشه، و اون هم فعال",
        "WEEKDAY_INVALID" to "روز هفته باید یکی از SATURDAY تا FRIDAY باشه",
        "NON_SHIPPING_WEEKDAYS_INVALID" to "حداقل یه روز هفته باید برای ارسال بمونه",
        "SUPPORT_PHONE_INVALID" to "شماره‌ی پشتیبانی باید یه موبایل یا تلفن ثابت ایران با پیش‌شماره باشه، مثل 02191001234",
        "SUPPORT_HOURS_INVALID" to "ساعت پشتیبانی درست نیست: عنوان ۱ تا ۵۰ حرف، حداقل یه روز، و ساعت شروع قبل از پایان (به شکل 09:00)",
        "SOCIAL_LINK_INVALID" to "لینک شبکه‌ی اجتماعی درست نیست: اسم ۱ تا ۴۰ حرف و آدرس با https://",
        "FEATURE_UNKNOWN" to "این قابلیت شناخته‌شده نیست",
        "FEATURE_NOT_AVAILABLE" to "این قابلیت هنوز آماده نیست و نمی‌شه روشنش کرد",
        "DISCOUNT_CODE_TEXT_INVALID" to "کد تخفیف باید ۳ تا ۳۲ حرف بزرگ انگلیسی، رقم، خط تیره یا زیرخط باشه",
        "DISCOUNT_TYPE_INVALID" to "نوع تخفیف باید PERCENT یا FIXED باشه",
        "DISCOUNT_VALUE_INVALID" to "مقدار تخفیف درصدی باید بین ۱ و ۱۰۰ و مبلغی بین ۱٬۰۰۰ و ۱۰۰٬۰۰۰٬۰۰۰ تومان باشه",
        "MAX_DISCOUNT_NOT_ALLOWED" to "سقف تخفیف فقط برای کد درصدی معنی داره",
        "DATE_RANGE_TOO_LONG" to "بازه حداکثر {maxDays} روزه",
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
        FailureKind.PRECONDITION_FAILED to byCode.getValue(CHANGED_BY_SOMEONE_ELSE),
        FailureKind.PAYLOAD_TOO_LARGE to byCode.getValue("UPLOAD_TOO_LARGE"),
        FailureKind.UNSUPPORTED_MEDIA_TYPE to byCode.getValue("UPLOAD_TYPE_NOT_ALLOWED"),
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
