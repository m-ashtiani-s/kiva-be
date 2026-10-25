# کاتالوگ پنل: راهنمای فرانت

> بک‌اند: برنچ `claude/sweet-lamport-v3gy0d`، فاز ۱ ِ `COMPLETION_ROADMAP.md` (گام‌های ۱.۰ تا ۱.۱۰)، ۲۶ مهر ۱۴۰۵. مرجع دقیق شکل درخواست‌ها و پاسخ‌ها کانترکت `api/kiva-openapi.yml` (نسخه‌ی 1.8.0) است؛ بخش ۶.۸ ِ نقشه‌ی راه هر تغییر کانترکت این فاز رو فهرست کرده.
> این فایل می‌گه پنل (kiva-backoffice-fe) چطور دسته، رنگ، متریال، محصول، رنگ‌های محصول، موجودی و مدیا رو می‌سازه و ویرایش می‌کنه، و کجاها راحت می‌شه اشتباه کرد.

## خلاصه

- همه‌ی endpointهای `/admin/*` ِ کاتالوگ با `Authorization` و نقش `ADMIN` یا `CATALOG_MANAGER` کار می‌کنن (آپلود مدیا `OPERATOR` و `CONTENT_EDITOR` رو هم قبول می‌کنه). نقش ناکافی ← `403`، بدون توکن ← `401`.
- همه‌ی خطاهای فرم با هم در یه `400 VALIDATION_ERROR` میان، هر کدوم با **مسیر کامل** فیلد (`specs.straps.0.lengthCm`، `variants.1.media.0.mediaId`). فیلد رو با همین مسیر پیدا کن و خطا رو زیرش نشون بده.
- فایل‌ها اول آپلود می‌شن (`POST /admin/media`) و بعد با `mediaId` به محصول، رنگ یا دسته وصل می‌شن. آپلودی که ۴۸ ساعت به هیچ‌جا وصل نشه پاک می‌شه.
- صفحه‌ی محصول یه نسخه داره (`ETag` و `etag`). فرم رو با `If-Match` ِ همون نسخه ذخیره کن تا ذخیره‌ی کس دیگه رو بازنویسی نکنی (`412`).
- وضعیت محصول (`DRAFT`، `ACTIVE`، `ARCHIVED`) فقط با `PATCH …/status` و `DELETE` عوض می‌شه، رنگ‌ها فقط با endpointهای رنگ، موجودی فقط با `POST /admin/inventory/adjustments`. `PUT /admin/products/{id}` این‌ها رو نادیده می‌گیره.
- هر نوشتن یه ردیف تو لاگ تغییرات (`GET /admin/audit-log`) می‌ذاره؛ ذخیره‌ی بی‌تغییر هیچی.

## ۱. آپلود مدیا

```js
POST /api/v1/admin/media            multipart: purpose=PRODUCT, file=@front.jpg   (purpose قبل یا بعد از file، فرقی نداره)
201  { "id": "med_5Hq2xT9aLmQ0vB3nR7sK1w", "type": "IMAGE", "url": "https://…/media/2026/10/med_…/1600.webp",
       "thumbnailUrl": "…/160.webp", "srcSet": [{ "width": 160, "url": "…" }, …], "posterUrl": null,
       "width": 1600, "height": 1200, "durationSec": null, "mimeType": "image/webp", "alt": null, "blurhash": "LKO2?U%2Tw=w" }
```

| `purpose` | عکس | ویدیو |
|---|---|---|
| `PRODUCT` | jpeg، png، webp؛ تا ۱۵ مگ؛ تا ۴۰ مگاپیکسل؛ ضلع کوچک‌تر حداقل ۳۲۰ | mp4 با H.264؛ تا ۲۰۰ مگ؛ تا ۱۸۰ ثانیه |
| `CATEGORY` | همون | نه |

- نوع فایل از **محتوا** تشخیص داده می‌شه، نه پسوند: SVG، GIF، HEIC یا فایلی که فقط اسمش `.jpg` ـه ← `415 UPLOAD_TYPE_NOT_ALLOWED` با `meta.allowed`. ویدیوی HEVC (پیش‌فرض آیفون) ← `415` با `meta.hint = "H264"`: به کاربر بگو دوربین رو روی «Most Compatible» بذاره.
- فایل بزرگ‌تر از سقف ← `413 UPLOAD_TOO_LARGE` با `meta.maxBytes`. سرور وسط خوندن قطع می‌کنه؛ پس قبل از ارسال حجم رو خودت چک کن تا کاربر منتظر نمونه.
- عکس کوچیک ← `400 IMAGE_TOO_SMALL` (`meta.minSide`)، خیلی بزرگ ← `400 IMAGE_TOO_LARGE`.
- خروجی همیشه webp ِ تمیزه (EXIF و GPS پاک، چرخش درست). اسم فایل کاربر هیچ‌جا نگه داشته نمی‌شه.
- `blurhash` رو تا وقتی عکس لود می‌شه به‌جاش نشون بده.

## ۲. دسته، رنگ، متریال

- دسته: `POST`/`PUT /admin/categories*` با `name`، `slug`، `iconBagType`، `iconColorKey` (اجباری) و `imageMediaId` (فقط مدیای `CATEGORY`، فقط عکس) و `seo` (`title` ≤ ۷۰، `description` ≤ ۱۷۰، `noIndex`). slug ِ تکراری ← `409 DUPLICATE_SLUG`. عوض کردن slug یا اسم، فیلتر و جستجوی فروشگاه رو همون لحظه به‌روز می‌کنه.
- رنگ: `key` بعد از ساخت عوض نمی‌شه (`400 COLOR_KEY_IMMUTABLE`). `hex` با حروف بزرگ ذخیره می‌شه.
- متریال: `family` از enum ِ کانترکت؛ حداکثر ۱۰ دستور نگه‌داری.
- **حذف چیزی که در استفاده‌ست** ← `409 IN_USE` با `meta.productCount` (محصول در **هر** وضعیتی، حتی آرشیو و رنگ غیرفعال) یا `meta.categoryCount` (رنگی که آیکن یه دسته‌ست). پیام رو با عدد نشون بده و راه حل رو بگو (محصول‌ها رو منتقل کن).
- `productCount` ِ لیست‌های پنل همه‌ی وضعیت‌هاست؛ `productCount` ِ فروشگاه فقط منتشرشده‌ها. با هم قاطی‌شون نکن.

## ۳. محصول

### لیست و صفحه

`GET /admin/products?q=&status=&category=&stockStatus=&sort=&page=&size=`. `q` اسم، دسته، رنگ، متریال و کد محصول (`KV-1001` و `KV-1001-LILAC`) رو می‌گرده. `stockStatus = LOW_STOCK` محصولی رو می‌ده که حداقل یه رنگ فعالش کم مونده.

`GET /admin/products/{id}` همه‌ی فیلدهای صفحه‌ی فروشگاه (پیش‌نمایش دقیق) به‌اضافه‌ی فیلدهای پنل رو داره: `variants` فقط رنگ‌های **فعال** با قانون موجودی فروشگاه، `adminVariants` **همه‌ی** رنگ‌ها با موجودی دقیق (`stockOnHand`، `stockReserved`، `stockAvailable`)، `status`، `publishAt`، `tags`، `createdBy` (اسم یا موبایل ماسک‌شده؛ «سیستم»؛ «حساب حذف‌شده»).

### ساخت

```js
POST /api/v1/admin/products
{ "name": "کیف دوشی ماهک", "slug": "mahak-shoulder-bag", "categoryId": 1, "bagType": "HOBO", "materialId": 3, "price": 1890000,
  "description": "<p>…</p>", "tags": ["چرم"], "seo": { "title": "…" }, "status": "ACTIVE",
  "specs": { "weightGrams": 650, "closure": { "type": "ZIPPER" }, "straps": [{ "type": "SHOULDER", "lengthCm": 55 }], "pockets": { "phone": 2 } },
  "variants": [
    { "colorKey": "lilac", "isDefault": true, "stockQuantity": 12, "barcode": "6260000123456",
      "media": [{ "mediaId": "med_…", "view": "FRONT", "isPrimary": true }, { "mediaId": "med_…", "view": "SIDE", "sortOrder": 1 }] },
    { "colorKey": "black", "priceOverride": 1750000, "stockQuantity": 5 } ] }
201  ETag: W/"5"   { …AdminProduct… }
```

- `sku` ِ خالی یعنی «بساز» (`KV-1001`، رنگ‌ها `KV-1001-LILAC`). `slug` و `sku` بعداً عوض نمی‌شن.
- `stockQuantity` موجودی اولیه‌ی رنگه و تو تاریخچه‌ی موجودی یه ردیف `RESTOCK` می‌سازه. فقط در ساخت خونده می‌شه.
- `status = ACTIVE` بدون هیچ رنگ فعالی ← `422 NO_ACTIVE_VARIANT`. رنگ با `isActive: false` اضافه و همون لحظه غیرفعال می‌شه.
- `description` قبل از ذخیره sanitize می‌شه: فقط تگ‌های `HtmlString` (`p, b, strong, em, a, ul, ol, li, br, mark, h2, h3, blockquote`)، `href` فقط `http(s)` ِ کامل. چیزی که برمی‌گرده رو تو ادیتور جایگزین کن، چون همونیه که ذخیره شده.
- `newUntil` و `publishAt` زمان کامل با منطقه‌ی زمانی‌ان (`2026-11-01T10:00:00+03:30`)؛ شکل دیگه ← `400 DATE_TIME_INVALID`.

### گالری هر رنگ

- فقط مدیای آپلودشده با `purpose = PRODUCT` (عکس یا ویدیو). مدیای ناموجود ← `400 MEDIA_NOT_FOUND`، `purpose` ِ دیگه (مثلاً `PRE_SHIPMENT`) ← `400 MEDIA_PURPOSE_MISMATCH`، هر دو روی `variants.i.media.j.mediaId`.
- حداکثر ۲۰ تا (`MEDIA_TOO_MANY` روی `variants.i.media`)، هر فایل یه بار (`MEDIA_DUPLICATE`)، حداکثر یه `isPrimary` (`MEDIA_PRIMARY_DUPLICATE`).
- ترتیب نهایی: `isPrimary` اول، بعد `sortOrder` (بی‌`sortOrder`ها آخر)، بعد ترتیب لیست. اولین **عکس** گالری عکس کارت فروشگاهه و دومی hover؛ ویدیو هیچ‌وقت عکس کارت نیست.
- `alt` ِ آیتم گالری، وگرنه `alt` ِ خود فایل.

### ویرایش و `If-Match`

```js
GET /api/v1/admin/products/7              200  ETag: W/"12"   { …, "etag": "W/\"12\"" }
PUT /api/v1/admin/products/7   If-Match: W/"12"   { کل فرم }
200  ETag: W/"13"   { …AdminProduct… }
412  { "code": "CHANGED_BY_SOMEONE_ELSE", "meta": { "currentEtag": "W/\"14\"" } }     ← کس دیگه‌ای قبلش ذخیره کرده
409  { "code": "CHANGED_BY_SOMEONE_ELSE" }                                         ← دو ذخیره در یک لحظه
```

- همیشه `If-Match` بفرست (`W/"12"`، `"12"` و `12` هر سه قبوله؛ شکل دیگه ← `400 IF_MATCH_INVALID`). `412` قبل از هر خطای دیگه‌ای میاد، حتی اگه فرم خطا داشته باشه: کاربر اول باید صفحه رو تازه کنه. برای `412` و `409` پیامی مثل «کس دیگه‌ای همین الان این محصول رو عوض کرد» نشون بده و دکمه‌ی «بارگذاری دوباره» بذار؛ تغییرات کاربر رو جایی نگه دار تا گم نشه.
- `ETag` ِ پاسخ رو برای ذخیره‌ی بعدی نگه دار. نسخه با هر تغییر محصول یا رنگ‌هاش بالا می‌ره (تغییر موجودی نه).
- `PUT` کل فرمه: فیلدی که نفرستی پیش‌فرض کانترکت رو می‌گیره. `status` و `variants` نادیده گرفته می‌شن. slug ِ دیگه ← `400 SLUG_IMMUTABLE`؛ `sku` ِ دیگه ← `400 SKU_IMMUTABLE` (نفرستادن یعنی همون).
- محصول آرشیو ← `422 PRODUCT_ARCHIVED`. ذخیره‌ی بی‌تغییر ← `200` بدون نسخه‌ی تازه.

### وضعیت و انتشار زمان‌بندی‌شده

`PATCH /admin/products/{id}/status` با `{ "status": "ACTIVE" | "DRAFT" | "ARCHIVED", "publishAt"?: "…" }`:

| الان | درخواست | `publishAt` | نتیجه |
|---|---|---|---|
| `DRAFT` | `ACTIVE` | نیومده یا گذشته | منتشر (برنامه‌ی قبلی هم پاک). بدون رنگ فعال ← `422 NO_ACTIVE_VARIANT` |
| `DRAFT` | `ACTIVE` | آینده | `DRAFT` می‌مونه با `publishAt`؛ سرور هر دقیقه موعدها رو منتشر می‌کنه. بیشتر از ۳۶۵ روز ← `400 PUBLISH_AT_TOO_FAR` |
| `DRAFT` | `DRAFT` | نیومده | برنامه لغو؛ بی‌برنامه ← `422 PRODUCT_ALREADY_DRAFT` |
| `ACTIVE` | `ACTIVE` | نیومده | `422 PRODUCT_ALREADY_ACTIVE` |
| `ACTIVE` | `DRAFT` | نیومده | از فروشگاه بیرون |
| `DRAFT`/`ACTIVE` | `ARCHIVED` | نیومده | آرشیو |
| `ARCHIVED` | هر چیز دیگه | — | `422 INVALID_STATUS_TRANSITION` (`meta.from`، `meta.to`) یا `PRODUCT_ALREADY_ARCHIVED` |
| هر | `DRAFT`/`ARCHIVED`، یا محصول `ACTIVE` | اومده | `400 PUBLISH_AT_NOT_ALLOWED` روی `publishAt` |

- محصول برنامه‌ریزی‌شده‌ای که تا موعد رنگ فعالش رو از دست بده منتشر نمی‌شه و برنامه‌ش پاک می‌شه؛ پس `publishAt` رو بعد از هر بار باز کردن صفحه از سرور بخون.
- `DELETE /admin/products/{id}` آرشیو می‌کنه (هیچ‌وقت پاک نمی‌کنه، سفارش‌ها بهش اشاره دارن). دوباره ← باز هم `204`. سبدهایی که این محصول رو دارن ردیفشون `purchasable = false` می‌شه.

## ۴. رنگ‌های محصول

- `POST /admin/products/{id}/variants`: مثل یه رنگ ِ فرم ساخت (`colorKey`، `stockQuantity`، `media`، …). رنگی که روی محصول هست، حتی غیرفعال ← `409 COLOR_ALREADY_ON_PRODUCT` (به‌جاش همون رو فعال کن). کد گرفته‌شده ← `409 DUPLICATE_SKU`. پاسخ `201` با `AdminVariant` ِ همین رنگ و `ETag` ِ تازه‌ی **محصول**.
- `PUT …/variants/{variantId}`: کل فرم رنگ. `colorKey` و `sku` عوض نمی‌شن، `stockQuantity` نادیده. ترتیب اعمال: فعال کردن، قیمت/ترتیب/پیش‌فرض/بارکد، گالری، غیرفعال کردن؛ پس «این رنگ غیرفعال رو فعال و پیش‌فرض کن» یه درخواسته. `isDefault: false` پیش‌فرض بودن رو برنمی‌داره (یه رنگ دیگه رو پیش‌فرض کن). `isDefault: true` با `isActive: false` ← `422 INACTIVE_VARIANT_CANNOT_BE_DEFAULT`. `isActive` ِ نیومده یعنی `true` و `barcode` ِ نیومده یعنی بدون بارکد (فرم کامله).
- `DELETE …/variants/{variantId}`: غیرفعال (رنگ روی محصول می‌مونه). آخرین رنگ فعال محصول منتشرشده ← `422 LAST_ACTIVE_VARIANT`: «اول محصول رو از انتشار دربیار». اگه پیش‌فرض بود رنگ فعال بعدی پیش‌فرض می‌شه.
- رنگ محصول دیگه یا شناسه‌ی بدشکل ← `404 VARIANT_NOT_FOUND`.

## ۵. موجودی

```js
POST /api/v1/admin/inventory/adjustments   { "variantId": 101, "delta": 10, "reason": "RESTOCK", "note": "بارنامه ۲۳۴" }
201  { "id": 812, "variantId": 101, "sku": "KV-1001-LILAC", "delta": 10, "reason": "RESTOCK", "note": "بارنامه ۲۳۴",
       "stockAfter": 22, "createdAt": "…", "createdBy": "لیلا احمدی" }
GET  /api/v1/admin/inventory/adjustments?variantId=101&page=1&size=20      ← تازه‌ترین اول
```

- `delta` همیشه **تفاوت**ه، حتی برای `STOCKTAKE`: پنل خودش «عدد شمارش − موجودی فعلی» رو حساب می‌کنه. صفر یا بیشتر از ±۱۰٬۰۰۰ ← `400 STOCK_DELTA_INVALID`.
- `RESTOCK` و `RETURNED` فقط مثبت، `DAMAGED` فقط منفی، `STOCKTAKE` و `MANUAL_CORRECTION` هر دو؛ وگرنه `400 ADJUSTMENT_DIRECTION_INVALID`.
- کم کردن بیشتر از آزاد (رزروشده‌ها مال مشتری‌ان) ← `422 QUANTITY_EXCEEDS_STOCK` با `meta.availableQuantity` (حداکثر چقدر می‌شه کم کرد).
- تاریخچه فقط تغییرهای دستی (و موجودی اولیه‌ی رنگ‌ها) رو داره؛ رزرو و ارسال سفارش‌ها نه. `stockAfter` موجودی فیزیکی بعد از همون تغییره.
- چند نفر همزمان روی یه رنگ: همه ثبت می‌شن، پشت سر هم؛ هیچ تغییری گم نمی‌شه.

## ۶. لاگ تغییرات

`GET /admin/audit-log` (فقط `ADMIN`) با `entityType` و `entityId` (مثلاً تاریخچه‌ی یه محصول: `entityType=PRODUCT&entityId=7`؛ رنگ: `VARIANT` و شناسه‌ی رنگ؛ موجودی: `INVENTORY`). هر ردیف `action`، `summary` ِ فارسی، `actor` (اسم و موبایل کامل، چون فقط ADMIN لاگ رو می‌بینه؛ برای حساب حذف‌شده null)، `ip` و `changes` (فیلد، از، به) داره. متن‌های بلند (توضیح محصول، مشخصات) فقط «عوض شد» دارن، بدون متن. ردیف‌ها ۹۰ روز می‌مونن.

| کار | `action` |
|---|---|
| ساخت / ویرایش محصول | `PRODUCT_CREATED` / `PRODUCT_UPDATED` (فقط قیمت ← `PRODUCT_PRICE_CHANGED`) |
| وضعیت | `PRODUCT_PUBLISHED`، `PRODUCT_UNPUBLISHED`، `PRODUCT_ARCHIVED`، `PRODUCT_PUBLISH_SCHEDULED`، `PRODUCT_PUBLISH_UNSCHEDULED` |
| رنگ | `VARIANT_ADDED`، `VARIANT_UPDATED`، `VARIANT_DEACTIVATED` |
| موجودی | `INVENTORY_ADJUSTED` (`stockOnHand` از چند به چند، `reason`) |
| دسته/رنگ پالت/متریال | `CATEGORY_*`، `COLOR_*`، `MATERIAL_*` |

انتشار خودکار ِ زمان‌بندی‌شده کار سیستمه و ردیف لاگ نداره.

## ۷. اشتباه‌های راحت

- `variants` رو تو `PUT /admin/products/{id}` نفرست به این امید که رنگ‌ها عوض بشن: نادیده گرفته می‌شه. از endpointهای رنگ استفاده کن.
- `ETag` رو از پاسخ هر نوشتن (محصول یا رنگ) بگیر، نه فقط از `GET`.
- `If-Match` رو نفرستادن یعنی بدون محافظت؛ پنل همیشه بفرسته.
- عکسی که آپلود کردی ولی فرم رو ذخیره نکردی، ۴۸ ساعت بعد پاک می‌شه. اگه کاربر فرم رو بعداً ذخیره کنه، `MEDIA_NOT_FOUND` می‌گیره: دوباره آپلود کن.
- لینک نسبی (`/faq#reserve`) در توضیح محصول فعلاً `href` ِ خودش رو از دست می‌ده؛ تا گام ۵.۱ فقط آدرس کامل `https://` بذار.
- `productCount` ِ `409 IN_USE` همه‌ی محصول‌هاست، حتی آرشیو؛ «این دسته محصول فعال نداره» دلیل نمی‌شه که حذف بشه.
