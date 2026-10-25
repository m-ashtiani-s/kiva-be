# ریویوی کاتالوگ پنل: فاز ۱ (گام‌های ۱.۰ تا ۱.۱۰)

> برنچ `claude/sweet-lamport-v3gy0d`، ۲۶ مهر ۱۴۰۵. نقشه‌ی راه: `COMPLETION_ROADMAP.md`، فاز ۱. راهنمای فرانت: `docs/FRONTEND_PANEL_CATALOG.md`.
> برای هر ردیف بخش ۳ ِ نقشه‌ی راه که مال این فازه: دفاع کجاست، کدوم تست نگهبانشه، و نتیجه‌ی خرابکاری عمدی (نگهبان یه بار برداشته شد، تست قرمز شد، برگردونده شد).

---

## خلاصه

| چی بررسی شد | نتیجه |
|---|---|
| `./gradlew test --rerun-tasks` | ✅ **۲۲۶۳ تست** سبز (common ۲۰۹، domain ۹۵۳، application ۲۰۱، اصلی ۹۰۰). ۴ تست skip: تست‌های ابزار واقعی (`VipsFfmpegMediaProcessorTest`) چون `vips` و `ffmpeg` روی این ماشین نیستن |
| هشدار کامپایلر | ✅ صفر |
| قوانین لایه‌ها | ✅ `domain`، `common` و `application` هیچ import از Ktor، Exposed یا Koin ندارن |
| کانترکت | ✅ 1.8.0؛ ۲ ردیف تازه در ۶.۸ (ردیف ۲۰: `SearchQuery` با کد محصول، گام ۱.۴؛ ردیف ۲۱: `OrderItem.image` رشته، گام ۱.۹) |
| اسکن لاگ | ✅ `PanelCatalogLogScanOnDatabaseTest`، با تست همراه ِ نشت عمدی |
| دور با پنل | ⬜ مونده برای تو (پایین‌تر) |

---

## ردیف‌های بخش ۳

| # | خطر | دفاع | تست‌ها |
|---|---|---|---|
| 1 | بالا رفتن دسترسی | هر route ِ `/admin` با `requireRoles` و دقیقاً `x-roles` ِ کانترکت | `RouteGuardsTest` › `every admin endpoint takes exactly the roles of its x-roles in the contract`؛ `WebV1AdminProductWritesOnDatabaseTest` › `an OPERATOR is 403 on both writes, and nothing is stored`؛ همین تست برای رنگ‌ها، موجودی، وضعیت و کاتالوگ |
| 3 | فایل مخرب | نوع از محتوا (`MediaProbe`)، فقط jpeg/png/webp و mp4 (H.264)، سقف حجم موقع خوندن جریان، اسم تصادفی روی دیسک، خروجی دوباره انکودشده | `WebV1AdminMediaOnDatabaseTest` › `an HEVC video is 415 with the hint H264 - a GIF, an SVG or a program named jpg too`، `16 MB for a category is 413 with the cap, and nothing is kept`، `the client's file name is nowhere - not on the disk, not in the row`؛ `MediaInfrastructureTest` › `a file over the cap is cut off the moment it passes it - never read whole`، `a path that would leave the media root is refused, not followed`؛ `MediaLimitsPolicyTest`؛ پاک شدن EXIF/GPS: `VipsFfmpegMediaProcessorTest` (اینجا skip) |
| 4 | XSS ِ ذخیره‌شده | `HtmlSanitizer` (jsoup، allowlist ِ کانترکت) قبل از ذخیره، در `ProductInput` | `WebV1AdminProductWritesOnDatabaseTest` › `a description is kept without script, on-handlers or javascript links - a full https link stays`؛ `CreateProductUcTest` › `the description is kept as the sanitizer made it, never as it was typed`؛ `JsoupHtmlSanitizerTest` |
| 5 | بازنویسی همزمان | `If-Match` ← `412` قبل از هر قانون دیگه؛ دو ذخیره در یک لحظه ← قید یکتای event store ← `409` | `UpdateProductUcTest` › `a stale version is 412 CHANGED_BY_SOMEONE_ELSE with the current ETag, and nothing is recorded`، `a stale version comes before every other rule, even a wrong form or a changed slug`؛ `WebV1AdminProductWritesOnDatabaseTest` › `PUT with the current If-Match is 200 and one step up - a stale one is 412 with nothing saved - none is 200`؛ `UpdateProductUcOnDatabaseTest` › `two saves of one version at the same moment, 10 times - always one saved and one refused, and only the winner's change stored` |
| 15 | داده‌ی شخصی در لاگ | لاگ برنامه بدون IP، موبایل کامل و اسم فایل کلاینت؛ لاگ تغییرات موبایل رو نگه نمی‌داره و ۹۰ روز بعد پاک می‌شه | `PanelCatalogLogScanOnDatabaseTest` › `an upload, a category, a product with its colors and stock, its edits and the change log leave nothing personal in the log` (و `the scan finds a whole number, an IP and a file name, so a pass means something`)؛ لاگ تغییرات: `the log is newest first, with the actor as they are now and a Persian sentence`، `the job removes the rows of 91 days and keeps those of 89 - and a second run removes nothing` |
| 16 | دستکاری لاگ تغییرات | trigger: `UPDATE` هیچ‌وقت، `DELETE` فقط قدیمی‌تر از ۹۰ روز | `a row is never changed - not even an old one`، `a row of 89 days is never removed - one of 91 days may be` |
| 18 | فایل یتیم | job ِ روزانه: وصل‌نشده‌ی بیشتر از ۴۸ ساعت پاک؛ وصل‌شده هیچ‌وقت | `MediaOnDatabaseTest` › `the clean-up removes an upload of 49 hours with its folder - one of 47 hours and an attached one stay`، `an upload attached after it was found stays - the delete itself checks`؛ هر نوشتن محصول/رنگ/دسته فایل‌هاش رو «وصل» می‌کنه (`a gallery is kept in its final order with each file's own type, and its files are marked used`) |
| 20 | فرم پنل کار مشتری رو خراب کنه | ترتیب قفل ۷ (ردیف محصول، بعد رنگ‌ها) برای هر نویسنده‌ی تازه؛ `holdForChange` برای تغییرهای همزمان موجودی | `ProductLockOrderOnDatabaseTest` › `a color change waits at its product's row - it never holds the color's row first`؛ `WebV1OrdersOnDatabaseTest` › `no deadlock - a color's price changed in the panel while it is being bought, at once, 20 times`؛ `WebV1AdminInventoryOnDatabaseTest` › `20 changes at once on one color - the stock is their sum, with 20 rows of consecutive stock after` |

---

## خرابکاری عمدی

| نگهبان | چی برداشته شد | تست(هایی) که قرمز شد | گام |
|---|---|---|---|
| چک نقش در route | `Role.OPERATOR` به `requireRoles` ِ `webV1AdminProducts` اضافه شد | `RouteGuardsTest` › `every admin endpoint takes exactly the roles …`؛ `an OPERATOR is 403 on both writes …` | ۱.۱۰ |
| trigger ِ لاگ تغییرات | شرط ۹۰ روز از trigger ِ `DELETE` | `a row of 89 days is never removed - one of 91 days may be` | ۱.۱ |
| چک `If-Match` | خط مقایسه‌ی نسخه در تراکنش `UpdateProductUc` | `a stale version is 412 …`، `a stale version comes before every other rule …`، `PUT with the current If-Match is 200 …` | ۱.۵ |
| sanitize | `HtmlSanitizer { it }` در `ProductInput` | `the description is kept as the sanitizer made it …`، `a description is kept without script …` | ۱.۵ |
| تشخیص نوع از محتوا | `MediaProbe.Unsupported` در `MediaLimitsPolicy` به‌عنوان عکس قبول شد | `MediaLimitsPolicyTest` › `an unsupported file is 415 …`، `the type comes before the size - a huge SVG is 415, not 413`؛ `an HEVC video is 415 … a GIF, an SVG or a program named jpg too` | ۱.۱۰ |
| سقف حجم | شرط `bytes > maxImageBytes` در `MediaLimitsPolicy` | `a photo over 15 MB is 413 with the cap …`؛ `16 MB photo for a product, its purpose sent after the file, is 413 …` | ۱.۱۰ |
| `refreshSearchText` | حذف از `UpdateCategoryUc` | `renaming a category puts its new name in its products' search text - the old name finds nothing` | ۱.۳ |
| قفل ۷ در تغییر رنگ | `ProductQmSummary.lock` برای `VariantChanged` در Projector برداشته شد | `a color change waits at its product's row - it never holds the color's row first` (تست ِ ۲۰ بار همزمانی با خرید به‌تنهایی قرمز **نشد**: بن‌بست به زمان‌بندی بستگی داره؛ برای همین تست قطعی ِ ترتیب قفل در ۱.۱۰ اضافه شد) | ۱.۷، ۱.۱۰ |
| چک «در استفاده» | چک `productCount` قبل از حذف دسته در `DeleteCategoryUc` | `a category with a draft and an archived product is 409 IN_USE with productCount 2 - counted before the delete is tried` (FK دیوار دومه و به‌تنهایی فقط «حداقل ۱» می‌گه؛ برای همین تستش در ۱.۱۰ اضافه شد) | ۱.۱۰ |
| انتشار یه‌باره در job | `catch (ConcurrentChangeException)` در `PublishScheduledProductUc` | `two runs of the job on one product at the same moment publish it once` | ۱.۶ |
| ترتیب اعمال فرم رنگ | «پیش‌فرض کردن» قبل از «فعال کردن» در `UpdateVariantUc` | `PUT an inactive color with isActive and isDefault makes it active and the default …` | ۱.۷ |
| صف تغییرهای موجودی | `holdForChange` از `AdjustInventoryUc` | `20 changes at once on one color …` (۴۰۹ بعد از ۳ تلاش) | ۱.۸ |
| عکس در snapshot ِ سفارش | `imageUrl = null` در `PlaceOrderUc` | `a cart line shows its color's photo, a new order keeps the 480 one …` | ۱.۹ |

---

## چیزهایی که موقع کار پیدا شد

- **`newUntil` ِ بدشکل بی‌صدا null می‌شد** (گام ۱.۵، درخواست HTTP با `runCatching`). در ۱.۶ با `MomentField` درست شد: `400 DATE_TIME_INVALID` روی همون فیلد، برای `publishAt` هم.
- **`OrderItem.image`** در کانترکت `MediaAsset` بود ولی پیاده‌سازی از اول رشته می‌فرستاد (همیشه null بود و کسی نفهمید). با پر شدنش در ۱.۹، کانترکت با رفتار هم‌خط شد (ردیف ۲۱).
- **۲۰ تغییر همزمان موجودی** با ۳ بار تلاش ۴۰۹ می‌گرفتن؛ `InventoryRepository.holdForChange` قفل ۷ رو قبل از خوندن می‌گیره.
- **لینک نسبی** (`/faq#reserve`) در توضیح محصول هنوز `href` رو از دست می‌ده؛ طبق نقشه‌ی راه کار گام ۵.۱ ـه (تصمیم ۶ ِ اونجا). تو راهنمای فرانت نوشته شد.
- **`MaxPerOrder` تا ۲۰** با سقف ۲۰ ِ ردیف سبد یکی شد؛ دو تست سبد که `maxPerOrder = 30` داشتن ۲۰ شدن.

---

## مونده برای تو

1. **تست‌های ابزار واقعی:** `vips` و `ffmpeg` رو نصب کن (README، بخش Uploads) و `./gradlew test --tests '*VipsFfmpegMediaProcessorTest'`: پاک شدن EXIF/GPS، پنج اندازه‌ی webp، تشخیص HEVC.
2. **دور با پنل:** kiva-backoffice-fe روی `localhost:3200` (`NEXT_PUBLIC_API_URL` به بک‌اند محلی؛ origin از قبل مجازه)، ورود با `CATALOG_MANAGER`، دسته‌ی تازه با عکس، محصول با سه رنگ و گالری و ویدیو، ذخیره در دو تب (باید `412` بیاد)، برنامه‌ی انتشار (یه دقیقه بعد منتشر می‌شه)، تغییر موجودی، آرشیو؛ و دیدن نتیجه در kiva-fe (کارت با عکس، hover، سواچ‌ها). این محیط نه پنل رو داره نه ابزار مدیا رو، پس همه‌ی این جریان‌ها اینجا با HTTP ِ واقعی و `FakeMediaProcessor` تست شدن (`WebV1AdminProductWritesOnDatabaseTest`، `WebV1AdminVariantsOnDatabaseTest`، `WebV1AdminProductStatusOnDatabaseTest`، `WebV1AdminInventoryOnDatabaseTest`، `WebV1ShopImagesOnDatabaseTest`).
3. **merge به `main`.**
