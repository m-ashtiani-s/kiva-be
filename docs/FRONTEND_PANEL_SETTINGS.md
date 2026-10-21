# تنظیمات، کد تخفیف، مشتری و داشبورد در پنل: راهنمای فرانت

> بک‌اند: برنچ `panel-settings`، فاز ۴ ِ `COMPLETION_ROADMAP.md` (گام‌های ۴.۰ تا ۴.۵)، ۲۹ مهر ۱۴۰۵. مرجع دقیق شکل درخواست‌ها و پاسخ‌ها کانترکت `api/kiva-openapi.yml` (نسخه‌ی 1.11.1) است؛ بخش ۹.۷ ِ نقشه‌ی راه هر تغییر کانترکت این فاز رو فهرست کرده، و تصمیم‌های ۱ تا ۳۸ ِ بخش ۹.۲ دلیل هر قانون رو.
> این فایل می‌گه پنل (kiva-backoffice-fe) صفحه‌ی تنظیمات، کدهای تخفیف، صفحه‌ی مشتری و نقش‌ها، و داشبورد رو چطور کار می‌کنه، و کجاها راحت می‌شه اشتباه کرد.

## خلاصه

- همه‌ی endpointهای این فاز فقط `ADMIN` ـن، جز **لیست و صفحه‌ی مشتری** (`GET /admin/customers`، `GET /admin/customers/{id}`) که `SUPPORT` هم می‌بینه. نقش ناکافی ← `403`، بدون توکن ← `401`.
- هر نوشتن یه ردیف تو لاگ تغییرات می‌ذاره (`SETTINGS_UPDATED`، `DISCOUNT_CODE_*`، تغییر نقش)؛ ذخیره‌ی بی‌تغییر هیچ ردیفی نمی‌ذاره. خوندن‌ها ثبت نمی‌شن.
- خطای فیلد همیشه `400 VALIDATION_ERROR` با `errors[]` ـه: `field` مسیر کامل (`shippingMethods.1.maxDays`، `categoryIds.0`)، `code` و `message` ِ فارسی. **همه‌ی** مشکل‌های فرم با هم میان؛ هر کدوم رو زیر فیلد خودش نشون بده. هر خطا `meta` ِ خودش رو هم داره (از 1.11.1): همون عددهایی که جمله ازشون ساخته شده، مثل `{ "min": 0, "max": 30 }` برای `NUMBER_OUT_OF_RANGE`، `maxLength`، `maxItems` یا `maxDays`؛ خطای بی‌داده `meta: null`. برای نمایش `message` کافیه؛ `meta` برای وقتیه که بخوای سقف رو کنار input بنویسی.
- دو ادمین همزمان روی یه فرم: دومی `409 CHANGED_BY_SOMEONE_ELSE` می‌گیره و هیچ‌چیزش ذخیره نمی‌شه. فرم رو دوباره بخون و به کاربر بگو «یکی دیگه همین الان عوضش کرد».

## ۱. تنظیمات فروشگاه

```js
GET /admin/settings          200 AdminSettings   (همیشه کل سند، همه‌ی فیلدها)
PUT /admin/settings          AdminSettings ← 200 AdminSettings ِ تازه
```

- **`PUT` کل سنده.** هر فیلد سطح اول لازمه؛ نیومده ← `400 FIELD_REQUIRED` روی همون فیلد. لیست‌ها کامل جایگزین می‌شن. الگو: فرم رو از `GET` پر کن و موقع ذخیره **کل** شیء رو با تغییرها بفرست؛ هیچ‌وقت فقط فیلدهای عوض‌شده رو نفرست.
- فیلدهای فقط‌خوندنی رو هر چی بفرستی نادیده گرفته می‌شن: `shippingMethods[].description`، `paymentGateways[].name` و `available`، `siteStats.satisfaction.scale` و `metrics[].label`.
- `supportPhone` و `supportEmail` لازم‌ان ولی `null` می‌گیرن: `null` یا `""` یعنی پاک. نفرستادنشون `400` ـه، نه پاک.
- **روش‌های ارسال:** لیست دقیقاً `TIPAX` و `POST`، هر کدوم یه بار (کم یا تکراری ← `SHIPPING_METHOD_LIST_INVALID`)؛ ترتیب لیست = ترتیب نمایش در سبد. `COURIER` ← `400 SHIPPING_METHOD_NOT_SUPPORTED` (پیک هنوز تصمیمش گرفته نشده؛ تو فرم نشونش نده). حداقل یکی فعال (`NO_ACTIVE_SHIPPING_METHOD`)؛ `defaultShippingMethod` باید فعال باشه (`DEFAULT_SHIPPING_METHOD_INVALID`)؛ `freeShippingMethods` فقط از همین دو.
- **درگاه‌ها:** لیست دقیقاً سه درگاه؛ ترتیب = ترتیب در صفحه‌ی تسویه؛ حداقل یکی فعال (`NO_ACTIVE_GATEWAY`)؛ دقیقاً یه پیش‌فرض و فعال (`DEFAULT_GATEWAY_INVALID`). `available` یعنی «فعال **و** وصل‌شده به سرور»: درگاهی که فعاله ولی `available: false` ـه تو تسویه نمیاد؛ کنارش یه برچسب «هنوز وصل نیست» بذار.
- **سقف‌ها** (`NUMBER_OUT_OF_RANGE`): `freeShippingThreshold` ۰ تا ۱۰۰٬۰۰۰٬۰۰۰؛ `baseCost` ۰ تا ۱۰٬۰۰۰٬۰۰۰؛ `minDays` ۰ تا ۳۰ و `maxDays` از `minDays` تا ۳۰؛ `reservationHoldDays` ۱ تا ۱۴؛ `returnWindowDays` ۰ تا ۶۰؛ `lowStockThreshold` ۱ تا ۲۰؛ `preShipmentFeedbackHours` ۱ تا ۱۶۸؛ `paymentPendingMinutes` ۵ تا ۶۰. `nonShippingWeekdays` کمتر از ۷ روز؛ `supportHours` حداکثر ۷ ردیف؛ `social` حداکثر ۱۰. همین سقف‌ها رو تو `min`/`max` ِ input بذار.
- `trackingUrlTemplate` با `https://` و شامل `{code}`. `supportHours[i]`: `from`/`to` به شکل `HH:mm` و `from < to`، یا هر دو `null` برای «تعطیل».
- `features`: فقط `wishlistShare`، `reviewMedia`، `giftWrap`؛ `giftWrap: true` ← `400 FEATURE_NOT_AVAILABLE` (فعلاً همیشه خاموش). کلید دیگه ← `FEATURE_UNKNOWN`.
- `siteStats` عددهای بازاریابیه که **دستی** وارد می‌شه (هر چی بنویسی همون تو سایت نشون داده می‌شه). `satisfaction.averageRating` ۰ تا ۵ با حداکثر یه رقم اعشار (`RATING_INVALID`).
- **از کِی اثر داره:** فقط برای آینده. مهلت پرداخت سفارش‌های تازه، مهلت رزرو گروه‌های تازه، مهلت مرجوعی سفارش‌هایی که از این به بعد تحویل می‌شن، مهلت جواب عکس‌هایی که از این به بعد فرستاده می‌شن؛ هزینه‌ها و آستانه از سبد بعدی. دو استثنا که همون لحظه همه‌جا عوض می‌شن: **اسم روش ارسال** (روی سفارش‌های قبلی هم) و **`lowStockThreshold`** (برچسب «موجودی کم» کارت‌ها و فیلتر پنل). این رو زیر فرم بنویس.

## ۲. کدهای تخفیف

```js
GET    /admin/discount-codes?active=true|false&page=&size=     صفحه‌ی DiscountCode، تازه‌ترین ساخته‌شده اول
POST   /admin/discount-codes            DiscountCodeUpsertRequest ← 201 DiscountCode
PUT    /admin/discount-codes/{id}       DiscountCodeUpsertRequest ← 200 DiscountCode
DELETE /admin/discount-codes/{id}       204 (غیرفعال می‌شه، پاک نمی‌شه؛ دوباره هم 204)
```

- **متن کد:** سرور trim می‌کنه، حروف رو بزرگ و رقم فارسی رو لاتین (` paeez۱۵ ` ← `PAEEZ15`)، بعد `^[A-Z0-9_-]{3,32}$`. تکراری ← `409 DUPLICATE_CODE` (روی `code`). کدهای دوحرفی ِ قدیمی معتبر می‌مونن و ویرایش می‌شن، ولی کد تازه حداقل ۳ حرف.
- `PERCENT`: `value` ۱ تا ۱۰۰ و `maxDiscount` (اختیاری) ۱٬۰۰۰ تا ۱۰۰٬۰۰۰٬۰۰۰. `FIXED`: ۱٬۰۰۰ تا ۱۰۰٬۰۰۰٬۰۰۰ و **بدون** `maxDiscount` (`400 MAX_DISCOUNT_NOT_ALLOWED`). `endsAt > startsAt`. `usageLimit`/`perUserLimit` ۱ تا ۱٬۰۰۰٬۰۰۰ یا `null` (= بی‌سقف؛ پیش‌فرض «۱» نداره).
- `categoryIds` (حداکثر ۵۰) و `productIds` (حداکثر ۲۰۰) «اجتماع»‌ان: ردیفی مشموله که محصولش تو لیست یا دسته‌اش تو لیست باشه. ناموجود ← `400 CATEGORY_NOT_FOUND` روی `categoryIds.i` / `PRODUCT_NOT_FOUND` روی `productIds.i`.
- `requiresLogin` ِ پاسخ همیشه مقدار **مؤثره**: با `firstOrderOnly` یا `perUserLimit` خودش `true` می‌شه. تیک رو از پاسخ بخون، نه از چیزی که فرستادی.
- `PUT` کل کد رو جایگزین می‌کنه (فیلد نیومده = پیش‌فرض کانترکت). ویرایش کد استفاده‌شده مجازه: سفارش‌های قبلی مبلغ خودشون رو دارن و سبدها با همگام‌سازی بعدی دوباره چک می‌شن (کد غیرفعال‌شده از سبد می‌افته با `DISCOUNT_REMOVED`).
- `usedCount` و `totalDiscountGiven` فقط سفارش‌های پرداخت‌شده‌ی برنگشته رو می‌شمرن؛ سفارش لغوشده از شمارش بیرون می‌ره. `updatedAt` آخرین ویرایشه.

## ۳. مشتری‌ها و نقش‌ها

```js
GET /admin/customers?q=&role=OPERATOR&page=&size=      (ADMIN، SUPPORT)
GET /admin/customers/{id}                               AdminCustomerDetail (ADMIN، SUPPORT)
PUT /admin/customers/{id}/roles   { "roles": ["OPERATOR"] }   (ADMIN)
```

- `role` تازه‌ست: صفحه‌ی «کارمندها» با `role=ADMIN`، `OPERATOR`، … ساخته می‌شه. `CUSTOMER` یعنی همه. ناشناخته ← `400 ROLE_INVALID`.
- صفحه‌ی مشتری: پروفایل کامل، **همه‌ی** آدرس‌ها (پیش‌فرض اول، `fullText` مثل دفترچه)، و `recentOrders` = ۵ سفارش آخر **در هر وضعیتی** (پرداخت‌نشده و منقضی هم)، تازه‌ترین اول. بقیه: لینک به `GET /admin/orders?q=<موبایل>`. شناسه‌ی ناموجود یا بدشکل ← `404`.
- **نقش‌ها:** نقش‌های خودت رو نمی‌تونی عوض کنی (`422 ROLE_CHANGE_NOT_ALLOWED`). **آخرین ADMIN** هیچ‌وقت ADMIN ـش رو از دست نمی‌ده: همون `422` با `meta.reason = LAST_ADMIN`؛ جمله‌ی خودت رو نشون بده («فروشگاه باید حداقل یه مدیر داشته باشه»). اگه همین الان یکی دیگه ADMIN ِ خودت رو گرفته باشه ← `403`. بعد از تغییر نقش، نشست‌های اون کاربر باطل می‌شن و باید دوباره وارد شه.

## ۴. داشبورد

```js
GET /admin/reports/overview?from=2026-10-01&to=2026-10-31     200 AdminReportOverview
```

- `from` و `to` روز **تهران**‌ان و هر دو **شامل** (`from` از ۰۰:۰۰ تا آخر `to`). نیومده ← `FIELD_REQUIRED`؛ بدشکل ← `DATE_INVALID`؛ `from` بعد از `to` ← `DATE_RANGE_INVALID` روی `to`؛ بیشتر از ۳۶۶ روز (دو سر شمرده) ← `DATE_RANGE_TOO_LONG` روی `to`. تاریخ شمسی رو خودت به میلادی تبدیل کن.
- **«سفارش دوره»** = پولش (پرداخت اول) تو بازه رسیده و لغو نشده. `revenue`، `ordersCount`، `averageOrderValue`، `discountTotal`، `reservationShare`، `shippingMix`، `messengerMix`، `topProducts` و `dailySeries` همه از همین سفارش‌هان. `revenue` = پرداخت‌شده (با مانده‌ی بعد از درخواست تغییر) منهای برگشت‌داده‌شده.
- `mediaApprovalRate` و `changeRequestRate` از سفارش‌هایی‌ان که **اولین** عکسشون تو بازه فرستاده شد (لغوشده هم)؛ جمعشون همیشه دقیقاً ۱۰۰ ـه (یا هر دو ۰). `returnRate` تا ساخته شدن مرجوعی همیشه ۰ ـه.
- درصدها عدد با **یه رقم اعشار**ان (`12.5`)؛ همون رو نشون بده، دوباره گرد نکن.
- `shippingMix` همیشه سه کلید `TIPAX`، `POST`، `COURIER` و `messengerMix` همیشه `RUBIKA`، `TELEGRAM`، `BALE` رو داره (صفر هم)؛ رنگ هر کلید رو ثابت نگه دار. `dailySeries` **هر** روز بازه رو به ترتیب داره، روز بی‌سفارش با صفر: نمودار رو بدون پر کردن جای خالی بکش.
- `topProducts` حداکثر ۵، بیشترین تعداد فروخته‌شده اول (ردیف‌های **فعلی** سفارش، یعنی بعد از درخواست تغییر). `product` یه `ProductRef` ـه با اسم و `slug` ِ **امروز** ِ محصول و `imageUrl` (عکس رنگ پیش‌فرض، یا `null`)؛ `colorKey` اینجا همیشه `null` ـه. `revenue` ِ هر محصول قیمت × تعداد ِ ردیف‌هاشه، **قبل** از کد تخفیف، پس جمعشون با `revenue` ِ کل برابر نیست.

## اشتباه‌های رایج

- فرستادن فقط فیلدهای عوض‌شده تو `PUT /admin/settings` ← `400 FIELD_REQUIRED` روی بقیه.
- نشون دادن `COURIER` تو فرم روش‌ها یا تو `defaultShippingMethod`.
- خوندن `requiresLogin` از فرم به‌جای پاسخ.
- ساختن جمله‌ی خطا از `errors[].meta`: جمله‌ی فارسی آماده تو `message` ـه؛ `meta` فقط برای کنار input.
- جمع زدن `topProducts[].revenue` و انتظار برابری با `revenue`.
- کشیدن نمودار روزانه با پر کردن روزهای خالی سمت پنل: سرور خودش همه‌ی روزها رو می‌ده.
