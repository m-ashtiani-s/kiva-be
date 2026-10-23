# ریویوی بازاریابی و صفحه‌ی خانه: فاز ۱۱ (گام‌های ۱۱.۰ تا ۱۱.۷)

> برنچ `marketing`، ۱ آبان ۱۴۰۵. نقشه‌ی راه: `COMPLETION_ROADMAP.md`، فاز ۱۱. راهنمای فرانت: `docs/FRONTEND_MARKETING.md`.
> این فاز ردیف تازه‌ای در بخش ۳ ِ نقشه‌ی راه نداره؛ خطرهای خودش: دو کمپین فعال همزمان، کمپینی که قیمت رو عوض کنه، بنر یا پیام پنهان که دیده بشه، فایل یا لینک ناامن در بنر، پاسخ کش‌شده‌ی شخصی، و شماره‌ی عمومی‌ای که از داده حساب بشه. برای هر کدوم: دفاع، تست نگهبان، و نتیجه‌ی خرابکاری عمدی (نگهبان یه بار برداشته شد، تست قرمز شد، برگردونده شد).

---

## خلاصه

| چی بررسی شد | نتیجه |
|---|---|
| `./gradlew test --continue --rerun-tasks` | ✅ **۲۹۹۹ تست** سبز (بعد از گام ۱۱.۶؛ این گام کدی عوض نکرد). ۵ تست skip: تست‌های ابزار واقعی (`vips` و `ffmpeg` روی این ماشین نیستن) |
| هشدار کامپایلر | ✅ صفر |
| قوانین لایه‌ها | ✅ `domain`، `common` و `application` هیچ import از Ktor، Exposed یا Koin ندارن |
| کانترکت | ✅ 1.17.0 (گام ۱۱.۰). پاسخ‌های `Campaign`، `AdminCampaign`، `Banner`، `Testimonial`، `AdminTestimonial`، `TestimonialListResponse`، `SiteStats` و `HomePage` در تست‌های route با شِماهای کانترکت مقایسه می‌شن؛ `RouteGuardsTest` نقش‌های دوازده route ِ پنل (`[ADMIN, CONTENT_EDITOR]`) رو با کانترکت چک می‌کنه |
| حذف حساب (بخش ۴.۱۰) | ✅ هیچ‌کدوم از سه جدول تازه داده‌ی یه حساب رو نگه نمی‌داره (اسم پیام رضایت رو پنل از اسکرین‌شات تایپ می‌کنه و به هیچ کاربری وصل نیست)، پس خطی در `AccountEraser` ِ فاز ۱۴ نمی‌خوان |
| دور واقعی روی سرور با پنل و kiva-fe | ⬜ مونده برای تو (پایین‌تر) |
| merge به `main` و deploy | ⬜ منتظر تأیید تو (فازهای قبل هم هنوز merge نشدن؛ این برنچ از سر فاز ۱۰ ساخته شده) |

---

## خطرهای این فاز

| خطر | دفاع | تست‌ها |
|---|---|---|
| دو کمپین فعال در یه لحظه | چک دامنه زیر قفل advisory ِ `campaigns` (با `meta.campaignId`)؛ قید `EXCLUDE` روی `tstzrange` به‌عنوان دیوار آخر | `WebV1AdminCampaignsOnDatabaseTest` › `two active campaigns sharing a moment …`، `two overlapping campaigns made at once, 10 times - one 201 and one 409 every time`؛ `CampaignTest` › `the domain service - …` |
| کمپینی که قیمت رو عوض کنه | کمپین هیچ ستون قیمتی نداره و هیچ job ای براش نیست؛ فقط فیلتر و شمارنده | `WebV1CampaignsInShopOnDatabaseTest` › `a discounted product of the campaign shows its countdown … its prices the same before, during and after` (نگهبانش ساختاریه: کدی نیست که بشه برداشت) |
| کمپین تموم‌شده یا غیرفعال که هنوز دیده بشه | «فعال» = `isActive` و `startsAt ≤ now < endsAt` در SQL؛ `?campaign=` ِ کمپین غیرفعال ← لیست خالی | `WebV1CampaignsInShopOnDatabaseTest` › `204 before it starts and after it ends …`، `GET products with the campaign … after it ends an empty list` |
| بنر یا پیام پنهان که دیده بشه | بنر فقط در بازه‌اش (`OpenWindow`، فیلتر SQL)؛ پیام رضایت فقط `isPublished` | `WebV1BannersOnDatabaseTest` › `the shop - only those in their window …`؛ `WebV1TestimonialsOnDatabaseTest` › `the shop - only the published ones …` |
| فایل یا لینک ناامن در بنر | عکس فقط مدیای `BANNER` (و اسکرین‌شات فقط `TESTIMONIAL`)، فقط عکس؛ لینک دکمه فقط `https://` یا مسیر همین سایت (`SiteLink`، نه `javascript:` و نه `//host`) | `WebV1BannersOnDatabaseTest` › `no picture 400 IMAGE_REQUIRED - a product photo 400 MEDIA_PURPOSE_MISMATCH - … javascript 400 URL_INVALID` |
| پاسخ کش‌شده‌ی شخصی | `/home` توکن رو نمی‌خونه و هیچ قلبی نمی‌ذاره؛ کمپین، بنر، پیام و آمار کش نمی‌شن | `WebV1HomeOnDatabaseTest` › `a logged-in customer with a heart still gets no heart - the same ETag answers 304` |
| کندی خانه با رشد فروشگاه | یه تراکنش با تعداد خوندن ثابت (کارت‌ها، امتیازها، عکس‌ها دسته‌ای) | `WebV1HomeOnDatabaseTest` › `the home page reads as many statements for 30 products as for 3` |
| عدد عمومی ِ ساختگی | آمار و خلاصه‌ی رضایت دستی، از تنظیمات؛ هیچ عددی از سفارش یا نظر حساب نمی‌شه | `WebV1SiteStatsOnDatabaseTest` › `before anything is typed … say the same` |
| داده‌ی شخصی در لاگ تغییرات | پیام رضایت: `entityLabel` = «#شناسه»، اسم مشتری فقط اسمِ فیلد، متن فقط «عوض شد» | `WebV1TestimonialsOnDatabaseTest` › `made, replaced and deleted … the change log keeps neither the name nor the message …` |
| رنگ پیام رضایت که از پالت بره | `DeleteColorUc` پیام‌ها رو هم می‌شمره (`409 IN_USE` با `testimonialCount`)؛ FK دیوار دوم | `WebV1TestimonialsOnDatabaseTest` › `a colour a testimonial names can't leave the palette …` |
| بالا رفتن دسترسی | همه‌ی `/admin/campaigns*`، `/admin/banners*`، `/admin/testimonials*` با `[ADMIN, CONTENT_EDITOR]` | `RouteGuardsTest`؛ تست نقش در هر فایل route |

---

## خرابکاری عمدی

همه با یه اسکریپت (نگهبان برداشته، تست‌های همون فایل‌ها، برگردونده) در گام ۱۱.۷ دوباره رفتن و **هر ۲۲ تا قرمز کردن**:

| نگهبان | چی برداشته شد | تست(هایی) که قرمز شد |
|---|---|---|
| `EXCLUDE` و قفل | قید از `V36` و `lockAll` از `CreateCampaignUc` | `WebV1AdminCampaignsOnDatabaseTest` › `two overlapping campaigns made at once, 10 times …` |
| چک هم‌پوشانی | `findActiveOverlapping` در `CampaignDomainService` | `… two active campaigns sharing a moment …`؛ `CampaignTest` › `the domain service - …` |
| slug یکتا | چک دامنه و قید `campaign_tbl_slug_key` | `… the form - every problem at once - … the slug again 409` |
| محصول بایگانی‌شده | `ARCHIVED` از `CampaignProducts` | همون تست |
| فقط تخفیف‌دارها در `?campaign=` | `onSale` ِ ضمنی از `LiveCampaign.scope` | `WebV1CampaignsInShopOnDatabaseTest` › `GET products with the campaign …` |
| کمپین غیرفعال ← هیچ | `campaignProductIds = emptySet` | همون تست |
| پایان بازه‌ی کمپین | `endsAt > now` از `findLiveAt` | سه تست ِ `WebV1CampaignsInShopOnDatabaseTest` |
| شمارنده فقط برای تخفیف‌دار | شرط تخفیف در `LiveCampaign.showing` | `… a discounted product of the campaign shows its countdown …` |
| فقط موجودها در ویترین کمپین | `inStock` از `campaignShowcase` | `… 204 before it starts …`؛ `WebV1AdminCampaignsOnDatabaseTest` › `made, replaced and deleted …` |
| purpose ِ عکس بنر | `PRODUCT` به‌جای `BANNER` | سه تست ِ `WebV1BannersOnDatabaseTest` |
| دکمه‌ی کامل | `CTA_INCOMPLETE` و `banner_cta_check` | `… no picture 400 IMAGE_REQUIRED - …`؛ `BannerTest` › `a button needs both its parts …` |
| بازه‌ی بنر | فیلتر بازه در `findVisible` | `… the shop - only those in their window …` |
| فقط منتشرشده‌ها | `published = null` در `ListTestimonialsUc` | `WebV1TestimonialsOnDatabaseTest` › `the shop - only the published ones …` |
| رنگ همون محصول | چک در `TestimonialReferences` | `… another product's colour 400 COLOR_NOT_FOUND …` |
| رنگ در استفاده | شمارش پیام‌ها در `DeleteColorUc` | `… a colour a testimonial names can't leave the palette …` |
| نه از آینده | `DATE_IN_FUTURE` در `TestimonialDraft` | `… another product's colour 400 COLOR_NOT_FOUND - … from the future …` |
| آمار دستی | `SiteStats.NONE` به‌جای تنظیمات | `WebV1SiteStatsOnDatabaseTest` › `before anything is typed …` |
| خلاصه‌ی دستی | `summary = null` | همون تست |
| ویترین خانه | «وگرنه تازه‌ترین» | `WebV1HomeOnDatabaseTest` › `every part in the contract's shape …` |
| یه خوندن برای کارت‌ها | کارت‌ها تک‌تک | `… reads as many statements for 30 products as for 3` |
| کش خانه | `respondResult` به‌جای `respondResultCacheable` | `… the same ETag answers 304`، `every part in the contract's shape …` |
| بدون قلب در خانه | کارت‌ها با `isWishlisted = false` | `… a logged-in customer with a heart still gets no heart …` |

---

## چیزهایی که موقع کار پیدا شد

- **`V36` در گام اول کامل نوشته شد** (بخش ۲۱): و چون `sitecontent` سخت‌گیرانه خونده می‌شه، کلید `home` همون گام به مدل اضافه شد، وگرنه `/config` می‌شکست (تصمیم ۲۴).
- **`DATE_RANGE_INVALID` به‌جای `ENDS_BEFORE_START`** (تصمیم ۲۰): پروژه از قبل یه کد برای همین قانون داشت.
- **رقابت کمپین‌ها با قفل از اول نمیاد** (تصمیم ۲۱): بعد از رد شدن یه دستور در Postgres، تراکنش دیگه نمی‌تونه بخونه، پس دیوار `EXCLUDE` به‌تنهایی نمی‌تونست `meta.campaignId` رو بده.
- **خلاصه‌ی رضایت و آمار** پاسخ‌هاشون از فاز ۴ آماده بود؛ `summary` با گام ۱۱.۴ اومد و ۱۱.۵ فقط `/site/stats` شد.
- **اسکریپت خرابکاری** یه باگ داشت (چند `--tests` روی یه task فقط آخری رو اجرا می‌کرد)؛ در گام ۱۱.۲ پیدا و درست شد و همه دوباره رفتن.
- **daemon ِ Gradle ِ متوقف‌شده** هنوز تست می‌زد و دیتابیس تست رو پاک می‌کرد؛ اگه دو اجرای تست روی `kiva_test` همزمان شدن، `./gradlew --stop`.

---

## دور واقعی روی سرور (مونده برای تو)

1. `V36` روی سرور اجرا می‌شه (متن‌های خانه از `index.html` ِ پروتوتایپ؛ اگه متن دیگه‌ای می‌خوای، `sitecontent_tbl.document -> 'home'` رو با SQL عوض کن).
2. پنل: یه کمپین با بازه‌ی امروز و چند محصول تخفیف‌دار ← خانه‌ی kiva-fe بخش جشنواره با شمارنده؛ «همه رو ببین» ← لیست؛ صفحه‌ی محصول: شمارنده کنار قیمت. یه کمپین هم‌پوشان ← پیام `CAMPAIGN_OVERLAP`.
3. پنل: بنر `HOME_PROMO` با عکس و دکمه ← خانه؛ `endsAt` ِ گذشته ← ناپدید.
4. پنل: پیام رضایت با اسکرین‌شات ← خانه و `/testimonials`؛ «منتشر نشه» ← ناپدید.
5. پنل ← تنظیمات ← آمار و خلاصه‌ی رضایت ← صفحه‌ی «درباره ما» و بخش رضایت خانه.
6. `curl -I /api/v1/home` دو بار با `If-None-Match` ← `304`.
