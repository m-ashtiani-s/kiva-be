# بازاریابی و صفحه‌ی خانه: راهنمای فرانت (kiva-fe و پنل)

> بک‌اند: برنچ `marketing`، فاز ۱۱ ِ `COMPLETION_ROADMAP.md` (گام‌های ۱۱.۰ تا ۱۱.۷)، ۱ آبان ۱۴۰۵. مرجع دقیق شکل درخواست‌ها و پاسخ‌ها کانترکت `api/kiva-openapi.yml` (نسخه‌ی 1.17.0) است؛ بخش ۱۶.۷ ِ نقشه‌ی راه تغییرات کانترکت این فاز رو فهرست کرده، و تصمیم‌های بخش ۱۶.۲ دلیل هر قانون رو.

## خلاصه

- صفحه‌ی خانه با **یه** درخواست: `GET /home`. پاسخ ۵ دقیقه کش می‌شه (`ETag`؛ با `If-None-Match` ← `304`)، پس روی کارت‌هاش **هیچ‌وقت قلب نیست** (`isWishlisted = null`): قلب‌ها رو از `GET /me/wishlist/ids` روشن کن.
- **شمارنده‌ی جشنواره رو با `serverTime` ِ `GET /campaigns/active` همگام کن**، نه با `/home` (که تا ۵ دقیقه کهنه‌ست). اون endpoint کش نمی‌شه.
- بنرهای `HOME_HERO` جزو خانه نیستن: `GET /banners?placement=HOME_HERO`.
- کمپین **هیچ قیمتی رو عوض نمی‌کنه**؛ تخفیف هر محصول همون `compareAtPrice` ِ خودشه و قیمت‌ها قبل، وسط و بعد از کمپین یکی‌ان.
- آمار «درباره ما» و خلاصه‌ی رضایت **دستی‌ان** (پنل ← تنظیمات ← `siteStats`) و از سفارش یا نظر حساب نمی‌شن.

## ۱. صفحه‌ی خانه

```js
GET /home      200 HomePage (ETag)   |   304 با If-None-Match
{ hero: { eyebrow, title, text, primaryCta, secondaryCta, showcaseProduct, showcaseMessenger },
  features, categories, newArrivals, promos, sale, testimonials, howItWorks }
```

| بخش | از کجا | نکته |
|---|---|---|
| `hero.showcaseProduct` | محصول انتخاب‌شده‌ی محتوا اگه منتشرشده، وگرنه تازه‌ترین محصول | فروشگاه خالی ← `null`: کارت «روی سایت دیدی = …» رو بدون محصول نشون بده |
| `features`، `howItWorks` | محتوای ثابت سایت | عددها (رزرو، مهلت مرجوعی) از تنظیمات پر شدن |
| `categories` | همون `GET /categories` | `showInMenu = false` ها رو خودت کنار بذار |
| `newArrivals` | ۸ تازه‌ترین محصول منتشرشده | ناموجودها ته لیست |
| `promos` | بنرهای دیده‌شده‌ی `HOME_PROMO`، حداکثر ۶ | |
| `sale` | کمپین فعال با ۴ محصول، یا `null` | `null` ← کل بخش جشنواره رو پنهان کن |
| `testimonials` | `summary` (یا `null`) و ۶ پیام اول | |

## ۲. جشنواره (کمپین)

```js
GET /campaigns/active       200 Campaign   |   204 (کمپینی نیست)
{ id, slug, title, subtitle, icon, startsAt, endsAt, serverTime, products, seeAllUrl, isActive }
```

- شمارنده: `endsAt - serverTime` موقع گرفتن پاسخ، بعد با ساعت مرورگر کم کن. به صفر رسید ← دوباره بخون (احتمالاً `204`).
- `products`: تخفیف‌دارهای موجود، بیشترین درصد اول، حداکثر ۲۴.
- «همه رو ببین» ← `seeAllUrl` (`/products?campaign=paeez-1405`). `GET /products?campaign=…` همون تخفیف‌دارها رو با ناموجودها ته لیست می‌ده؛ `title` ِ لیست عنوان کمپینه و یه چیپ `campaign` داره. کمپین تموم‌شده ← لیست خالی (پیام «این جشنواره تموم شده»).
- صفحه‌ی محصول: `campaign` = `{slug, title, endsAt}` وقتی محصول تخفیف‌دار ِ کمپین فعاله؛ شمارنده‌ی کوچیک کنار قیمت. وگرنه `null`.

## ۳. بنرها

```js
GET /banners?placement=SHOP_TOP      200 [Banner]     // placement اجباری؛ غلط ← 400
```

- فقط بنرهای دیده‌شده (`startsAt ≤ الان < endsAt`)، به ترتیب `sortOrder`، حداکثر ۱۰. کش نمی‌شه.
- `tag`، `cta` و `overlay` ممکنه `null` باشن (بنر بی‌دکمه، بی‌برچسب). `cta.url` یا `https://` ِ کامله یا مسیر همین سایت (`/faq#reserve`): مسیر رو با router باز کن.
- `image` شکل کامل `MediaAsset` با `srcSet` و `blurhash` داره.

## ۴. پیام‌های رضایت و آمار

```js
GET /testimonials?page=1&size=20     200 { summary, items: [Testimonial], meta }
GET /site/stats                      200 SiteStats
```

- `summary` تا پنل تنظیمش نکرده `null`ـه: بخش «۴٫۹ از ۵» رو پنهان کن.
- `averageRating` یه رقم اعشار داره (`4.9`). `sourceName` اسم فارسی پیام‌رسانه. `screenshotUrl` (یا `null`) عکس واقعی پیامه.
- `message` متن ساده‌ست (نه HTML)؛ خط تازه داره.

## ۵. پنل: کمپین، بنر، پیام رضایت (`ADMIN`، `CONTENT_EDITOR`)

```js
GET /admin/campaigns          POST /admin/campaigns          PUT|DELETE /admin/campaigns/{id}
GET /admin/banners            POST /admin/banners            PUT|DELETE /admin/banners/{id}
GET /admin/testimonials?published=true&page=1    POST /admin/testimonials    PUT|DELETE /admin/testimonials/{id}
```

- **`PUT` همه‌جا جایگزینی کامله**: فرم رو از پاسخ `GET` پر کن و همه‌ی فیلدها رو بفرست؛ فیلد اختیاری ِ نیومده خالی یا پیش‌فرض می‌شه.
- همه‌ی خطاهای فرم با هم میان (`400 VALIDATION_ERROR` با `errors[]` و مسیر هر فیلد، مثل `productIds.2`). `endsAt` قبل از `startsAt` ← `DATE_RANGE_INVALID` روی `endsAt`.
- **کمپین:** `slug` تکراری ← `409 DUPLICATE_SLUG`؛ دو کمپین **فعال** با بازه‌ی هم‌پوشان ← `409 CAMPAIGN_OVERLAP` با `meta.campaignId` (پیام: «با کمپین X هم‌پوشانی داره»؛ یکی رو غیرفعال کن). `productIds` خالی = همه‌ی تخفیف‌دارها؛ `products` ِ پاسخ نتیجه‌ی محاسبه‌ست (حتی برای کمپین آینده). محصول بایگانی‌شده ← `400 PRODUCT_NOT_FOUND` روی `productIds.i`.
- **بنر:** عکس با `POST /admin/media` و `purpose = BANNER` آپلود بشه (عکس محصول ← `MEDIA_PURPOSE_MISMATCH`)؛ بدون عکس ← `IMAGE_REQUIRED`. متن و لینک دکمه با هم (`CTA_INCOMPLETE` روی نیمه‌ای که خالیه). بنر سوییچ «فعال» نداره: برای پنهان کردن `endsAt` رو گذشته بذار یا حذفش کن. لیست پنل زمان‌بندی‌شده و تموم‌شده‌ها رو هم داره.
- **پیام رضایت:** اسکرین‌شات با `purpose = TESTIMONIAL`؛ `colorKey` باید رنگی از همون محصول باشه (`COLOR_NOT_FOUND`)؛ `messageTime` مثل `10:12`؛ `receivedAt` نه بعد از الان (`DATE_IN_FUTURE`). `isPublished = false` ← تو سایت و خانه نیست.
- رنگی که پیام رضایت داره قابل حذف نیست (`409 IN_USE` با `meta.testimonialCount`).
- هر ذخیره‌ی تغییردهنده یه ردیف لاگ تغییرات می‌ذاره (`CAMPAIGN_*`، `BANNER_*`، `TESTIMONIAL_*`)؛ ذخیره‌ی بی‌تغییر نه.
