# ریویوی «موجود شد خبرم کن» و «سؤال داری؟»: فاز ۱۰ (گام‌های ۱۰.۰ تا ۱۰.۴)

> برنچ `stock-alerts`، ۱ آبان ۱۴۰۵. نقشه‌ی راه: `COMPLETION_ROADMAP.md`، فاز ۱۰. راهنمای فرانت: `docs/FRONTEND_STOCK_ALERTS.md`.
> برای ردیف ۱۲ ِ بخش ۳ ِ نقشه‌ی راه (اسپم پیامک) و خطرهای خود این فاز (پیامک تکراری، پیامک نیمه‌شب، مصرف بی‌صدای اشتراک‌ها، لو رفتن شماره در پنل): دفاع کجاست، کدوم تست نگهبانشه، و نتیجه‌ی خرابکاری عمدی (نگهبان یه بار برداشته شد، تست قرمز شد، برگردونده شد).

---

## خلاصه

| چی بررسی شد | نتیجه |
|---|---|
| `./gradlew test --continue --rerun-tasks` | ✅ **۲۹۶۷ تست** سبز. ۵ تست skip: تست‌های ابزار واقعی (`vips` و `ffmpeg` روی این ماشین نیستن) |
| هشدار کامپایلر | ✅ صفر |
| قوانین لایه‌ها | ✅ `domain`، `common` و `application` هیچ import از Ktor، Exposed یا Koin ندارن |
| کانترکت | ✅ 1.16.0 (گام ۱۰.۰). پاسخ‌های `StockAlert`، `AdminStockAlertGroup`، `ProductInquiry` و `MessengerLink` در تست‌های route با شِماهای کانترکت مقایسه می‌شن (`ContractSchemas`)؛ `RouteGuardsTest` ورود ِ ثبت و `/me/stock-alerts*` و نقش‌های `GET /admin/stock-alerts` رو با کانترکت چک می‌کنه |
| الگوی پیامک | ⬜ `KIVA_FARAZ_BACK_IN_STOCK_PATTERN` باید در پنل فراز ساخته و تأیید بشه و در `kiva.env` بیاد (README، `LAUNCH.md`). تا اون موقع job اجرا نمی‌شه و اشتراک‌ها منتظر می‌مونن (هر ساعت یه WARN) |
| دور واقعی روی سرور با kiva-fe و پنل | ⬜ مونده برای تو (پایین‌تر) |
| merge به `main` و deploy | ⬜ منتظر تأیید تو (فازهای قبل هم هنوز merge نشدن؛ این برنچ از سر فاز ۹ ساخته شده) |

---

## ردیف‌های بخش ۳ و خطرهای این فاز

| # | خطر | دفاع | تست‌ها |
|---|---|---|---|
| 12 | اسپم پیامک به شماره‌ی دیگران | ثبت فقط با ورود (OTP ِ همون موبایل)؛ فیلد `phone` حذف شد و پیامک همیشه به موبایل حساب، **موقع فرستادن** خونده‌شده، می‌ره | `WebV1StockAlertsOnDatabaseTest` › `a sold-out color - 201 with the account's phone masked - …`؛ `RouteGuardsTest` |
| 12 | پر کردن صف با اشتراک | حداکثر ۳۰ اشتراک فعال برای هر کاربر (`422 STOCK_ALERT_LIMIT_REACHED`) و ۱۰ ثبت در ساعت (`429` با `Retry-After`)؛ یکی برای هر (کاربر، محصول، رنگ یا «هر رنگی») با ایندکس یکتای جزئی `stockalert_one_active_idx` به‌عنوان دیوار دوم | `WebV1StockAlertsOnDatabaseTest` › `the 31st active one 422 STOCK_ALERT_LIMIT_REACHED - the 11th made in an hour 429 with Retry-After`، `… again 409 …` |
| — | پیامک دوباره | job هر کاندید رو زیر قفل ردیف دوباره چک می‌کنه (هنوز `ACTIVE`، محصول هنوز در فروشگاه، رنگ هنوز موجود)، `NOTIFIED` می‌کنه، commit، **بعد** پیامک؛ شکست پیامک فقط WARN و اشتراک `NOTIFIED` می‌مونه | `EngagementJobsOnDatabaseTest` › `stock back by an adjustment - the next run tells them once … a second run tells nobody`، `found due, then called off or sold again before its turn - checked again under its lock, nobody told` |
| — | پیامک نیمه‌شب | بین ۲۲ تا ۸ تهران job هیچی نمی‌فرسته و اشتراک‌ها منتظر می‌مونن | `EngagementJobsOnDatabaseTest` › `23-00 in Tehran nobody is told - 08-05 they are` |
| — | مصرف بی‌صدای اشتراک‌ها بدون الگو | `SmsSender.canSend(BACK_IN_STOCK)`: بدون الگو job اصلاً اجرا نمی‌شه (هر ساعت یه WARN) | `EngagementJobsOnDatabaseTest` › `without a pattern the job doesn't run - the subscription stays ACTIVE for when it is set`؛ `FarazSmsSenderTest` |
| — | فشار روی فراز | ۲۰۰ اشتراک در هر اجرا، قدیمی‌ترین اول | `EngagementJobsOnDatabaseTest` › `250 waiting - the first run tells the 200 oldest, the second the other 50` |
| 15 | لو رفتن شماره در پنل | صف پنل هیچ جدول کاربری نمی‌خونه؛ UseCase هیچ پورت کاربری نداره؛ فقط شمارش | `WebV1AdminStockAlertsOnDatabaseTest` › `the queue - … no phone anywhere` (روی متن خام JSON، هم کامل هم ماسک‌شده) |
| 1 | بالا رفتن دسترسی | صف پنل فقط `[ADMIN, CATALOG_MANAGER]`؛ لغو فقط اشتراک خود کاربر (وگرنه `404`) | `WebV1AdminStockAlertsOnDatabaseTest` › `the queue is ADMIN and CATALOG_MANAGER's - OPERATOR and SUPPORT 403 - no token 401`؛ `WebV1StockAlertsOnDatabaseTest` › `… someone else's 404` |
| — | لینک خراب «سؤال داری؟» | `url` با `?text=` یا (وقتی آدرس `?` داره) `&text=`، متن با UTF-8 و فاصله `%20`؛ رنگی که محصول نداره ← `400`، نه متنی با رنگ اشتباه | `WebV1AdminStockAlertsOnDatabaseTest` › `«سؤال داری؟» - … decoded the same`، `… a link with a query gets &text= …` |

---

## خرابکاری عمدی

همه با یه اسکریپت (نگهبان برداشته، `./gradlew :test --tests '*StockAlert*' --tests '*EngagementJobs*'`، برگردونده) دوباره رفتن:

| نگهبان | چی برداشته شد | تست(هایی) که قرمز شد |
|---|---|---|
| ورود لازم | `authenticate(ACCESS)` ← `authenticate(ACCESS, optional = true)` در `webV1StockAlerts.kt` | `WebV1StockAlertsOnDatabaseTest` › `a sold-out color - 201 with the account's phone masked - …` |
| سقف ۳۰ | چک `MAX_ACTIVE` در `StockAlertDomainService` | `WebV1StockAlertsOnDatabaseTest` › `the 31st active one 422 …` |
| سقف ساعتی | چک `MAX_PER_HOUR` در `StockAlertDomainService` | همون تست |
| چک دوباره زیر قفل: هنوز `ACTIVE` | `takeIf { it.status == ACTIVE }` از `NotifyStockAlertUc` | `EngagementJobsOnDatabaseTest` › `found due, then called off or sold again before its turn - …` (تست تازه‌ی این گام) |
| چک دوباره زیر قفل: هنوز موجود | `it.available > 0` از `NotifyStockAlertUc` | همون تست، و `«any color» - one of three back - told with that color` |
| `canSend` | چک الگو در `EngagementJobs.notifyBackInStock` | `EngagementJobsOnDatabaseTest` › `without a pattern the job doesn't run - …` |
| ساعت سکوت | `isQuietHour` در `EngagementJobs.notifyBackInStock` | `EngagementJobsOnDatabaseTest` › `23-00 in Tehran nobody is told - 08-05 they are` |
| نبود موبایل در صف پنل | موبایل یه منتظر تو `sku` ِ پاسخ گذاشته شد | `WebV1AdminStockAlertsOnDatabaseTest` › `the queue - … no phone anywhere` |
| فیلتر `inStock` (گام ۱۰.۳) | `WHERE g.available > 0` از `waitingGroups` | `WebV1AdminStockAlertsOnDatabaseTest` › `inStock=false only the sold-out groups - …` |
| `&text=` (گام ۱۰.۳) | همیشه `?text=` | `WebV1AdminStockAlertsOnDatabaseTest` › `… a link with a query gets &text= …` |
| ذخیره‌ی `NOTIFIED` (گام ۱۰.۲) | `save` از `NotifyStockAlertUc` | شش تست ِ `EngagementJobsOnDatabaseTest` (پیامک دوباره) |
| سقف ۲۰۰ (گام ۱۰.۲) | `BATCH = 1000` | `EngagementJobsOnDatabaseTest` › `250 waiting - …` |

---

## چیزهایی که موقع کار پیدا شد

- **چک دوباره‌ی job تست نداشت** (گام ۱۰.۴): همه‌ی تست‌های job پیدا کردن و فرستادن رو در یه اجرا انجام می‌دادن، پس هیچ‌کدوم فاصله‌ی بین این دو رو نمی‌دید. قبل از خرابکاری، تستی اضافه شد که بین `FindDueStockAlertsUc` و `NotifyStockAlertUc` یکی رو لغو می‌کنه و آخرین عدد دیگری رو برمی‌داره.
- **Exposed جواب `WITH … SELECT` رو نمی‌خونه** (گام ۱۰.۳): `exec` نوع دستور رو از کلمه‌ی اول حدس می‌زنه؛ صف پنل با زیرکوئری نوشته شد.
- **«رسیدن مرجوعی با `restock`»** جدا تست نشد: job به وضعیت موجودی نگاه می‌کنه نه به راه برگشتنش، و انقضای سفارش پرداخت‌نشده و تنظیم دستی دو راه متفاوت همین رو نشون می‌دن.
- **تست همزمانی ارسال** (`WebV1ShipmentOnDatabaseTest` › `… 50 times - never a deadlock`) یه بار زیر بار کل مجموعه به timeout ِ یه دقیقه‌ای خورد و تنها سبز شد؛ به این فاز ربطی نداره ولی اگه دوباره دیده شد، timeout ِ تستش رو بالاتر ببر.

---

## دور واقعی روی سرور (مونده برای تو)

1. الگوی «موجود شد» رو در پنل فراز بساز (متن پیشنهادی README: «%product% موجود شد! %link%»)، بعد از تأیید در `kiva.env` بذار و برنامه رو دوباره راه بنداز. `V35` خودش اجرا می‌شه.
2. kiva-fe بدون ورود: روی رنگ ناموجود «موجود شد خبرم کن» ← صفحه‌ی ورود ← برگشت و ثبت ← «بهت خبر می‌دیم» با موبایل ماسک‌شده.
3. پنل: کاتالوگ ← «موجود شد خبرم کن» ← همون رنگ با یه منتظر؛ فرم محصول: شمارنده‌ی همون رنگ.
4. پنل: موجودی همون رنگ رو +۱ کن (بین ۸ تا ۲۲) ← حداکثر ۵ دقیقه بعد پیامک با لینک همون رنگ؛ «اشتراک‌های من»: «موجود شد و بهت خبر دادیم».
5. صفحه‌ی محصول: «سؤال داری؟» ← لینک تلگرام/روبیکا/بله با متن آماده باز می‌شه؛ دکمه‌ی کپی متن.
