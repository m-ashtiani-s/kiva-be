# ریویوی تنظیمات، کد تخفیف، مشتری و گزارش: فاز ۴ (گام‌های ۴.۰ تا ۴.۵)

> برنچ `panel-settings`، ۲۹ مهر ۱۴۰۵. نقشه‌ی راه: `COMPLETION_ROADMAP.md`، فاز ۴. راهنمای فرانت: `docs/FRONTEND_PANEL_SETTINGS.md`.
> برای هر ردیف بخش ۳ ِ نقشه‌ی راه که مال این فازه: دفاع کجاست، کدوم تست نگهبانشه، و نتیجه‌ی خرابکاری عمدی (نگهبان یه بار برداشته شد، تست قرمز شد، برگردونده شد).

---

## خلاصه

| چی بررسی شد | نتیجه |
|---|---|
| `./gradlew test --rerun-tasks` | ✅ **۲۶۲۴ تست** (common ۲۱۷، domain ۱۱۱۹، application ۲۲۳، اصلی ۱۰۶۵)؛ یکی قرمز بود: یه تست فاز ۳ که ترتیب ردیف‌ها رو بدون `ORDER BY` می‌خوند (پایین‌تر)، درست شد و دوباره سبز. تست همزمانی ِ تازه‌ی کد تخفیف بعد از این اجرا اضافه شد و جدا سبزه. ۴ تست skip: ابزار واقعی ِ مدیا (`vips`، `ffmpeg`) روی این ماشین نیست |
| هشدار کامپایلر | ✅ صفر (یکی از گام ۴.۲ بود و درست شد، پایین‌تر) |
| قوانین لایه‌ها | ✅ `domain`، `common` و `application` هیچ import از Ktor، Exposed یا Koin ندارن (پورت تازه‌ی `ReportQmRepository` فقط `java.time` و VOها) |
| کانترکت | ✅ 1.11.0 (گام ۴.۰). پاسخ گزارش با `ContractSchemas` مقایسه می‌شه (`AdminReportOverview` و `ProductRef`)؛ یه ناهمخونی پیدا شد و با 1.11.1 حل شد (پایین‌تر) |
| `RouteGuardsTest` | ✅ هر ۸ endpoint ِ این فاز با `x-roles` ِ کانترکت می‌خونه (`/admin/reports/overview` هم) |
| دور با پنل | ⬜ مونده برای تو (پایین‌تر) |
| merge به `main` | ⬜ منتظر تأیید تو (فازهای ۱ تا ۳ هم هنوز merge نشدن؛ این برنچ از سر فاز ۳ ساخته شده) |

---

## ردیف‌های بخش ۳

| # | خطر | دفاع | تست‌ها |
|---|---|---|---|
| 1 | بالا رفتن دسترسی | هر route ِ این فاز با `requireRoles` و دقیقاً نقش‌های `x-roles`: تنظیمات، کد تخفیف، نقش‌ها و گزارش فقط `ADMIN`؛ لیست و صفحه‌ی مشتری `ADMIN` و `SUPPORT` | `RouteGuardsTest` (هر چهار تست، روی routing ِ واقعی ِ برنامه)؛ صریح: `WebV1AdminSettingsOnDatabaseTest` › `SUPPORT and OPERATOR are 403, for reading and for saving`؛ `WebV1AdminDiscountCodesOnDatabaseTest` › `OPERATOR and CONTENT_EDITOR are 403 on every discount code endpoint`؛ `WebV1AdminCustomerDetailOnDatabaseTest` › `a customer nobody has, or an id that isn't one, is 404 - and only ADMIN and SUPPORT see the page`؛ `WebV1AdminReportsOnDatabaseTest` › `SUPPORT and OPERATOR are 403` |
| 2 | آخرین ADMIN حذف بشه | `SetUserRolesUc`: قفل advisory ِ `roles` (`UserRepository.lockRoleChanges`)، داخل قفل نقش‌های **فعلی** ِ انجام‌دهنده دوباره خونده می‌شه (دیگه ADMIN نیست ← `403`)، و تغییری که ADMINها رو صفر کنه ← `422 ROLE_CHANGE_NOT_ALLOWED` با `meta.reason = LAST_ADMIN` (دیوار دوم، تصمیم ۳۳) | `WebV1AdminCustomerDetailOnDatabaseTest` › `two admins taking ADMIN from each other at once - one is done, the other is 403, and an ADMIN is always left, 10 times`؛ `SetUserRolesLastAdminTest` (قانون‌های تک‌درخواستی) |
| 5 | بازنویسی همزمان (تنظیمات و کد تخفیف) | تنظیمات: ردیف فقط روی نسخه‌ی خونده‌شده نوشته می‌شه (`shopsettings_tbl.version`)؛ ذخیره‌ی دوم صبر می‌کنه، نسخه رو رفته می‌بینه ← `409 CHANGED_BY_SOMEONE_ELSE` و هیچ‌چیزش ذخیره نمی‌شه. کد تخفیف: `PUT`/`DELETE` زیر قفل ردیف کد، و نسخه‌ی کد؛ متن تکراری: چک قبل از ذخیره، و قید یکتای `code` دیوار دوم (← همون `409 DUPLICATE_CODE`) | `ShopSettingsRepositoryImplTest` › `a save over a version another save already moved writes nothing at all`، `two saves at the same moment over one version - one is written, the other is refused, 10 times`؛ `WebV1AdminSettingsOnDatabaseTest` › `two saves at the same moment over one version - one is 200, the other 409 CHANGED_BY_SOMEONE_ELSE, and only the first is kept`؛ `DiscountCodeRepositoryImplTest` › `a save over a version another save moved writes nothing`، `a text another code has is a UniqueTakenException, for a new code and for an edit`؛ `WebV1AdminDiscountCodesOnDatabaseTest` › `two codes with one text at the same moment, 10 times - one 201, the other 409 DUPLICATE_CODE, never a 500, one row` (**تازه**) |
| 15 | داده‌ی شخصی در لاگ | `GetCustomerDetailUc` فقط شناسه لاگ می‌کنه؛ DTOها `toString` ِ بی‌داده دارن؛ لاگ تغییرات تنظیمات شماره و ایمیل پشتیبانی رو ثبت می‌کنه، که عمومی‌ان (تصمیم گام ۴.۱) | `PanelOrdersLogScanOnDatabaseTest` › `a customer's page read in the panel leaves no whole phone, name or address in the log`، `the scan finds a whole number, a name and a reference, so a pass means something` |
| 19 | خطای منطقه‌ی زمانی (گزارش) | بازه‌ی گزارش از `ShopCalendar.ZONE` (`from` ساعت ۰۰:۰۰ ِ تهران تا ۰۰:۰۰ ِ بعد از `to`، نابسته)؛ روزهای `dailySeries` با `AT TIME ZONE 'Asia/Tehran'` در خود SQL | `ReportOverviewOnDatabaseTest` › `twelve orders - every field is the number worked out by hand` (سفارش ۲۱:۰۰ ِ UTC ِ ۲۸ فوریه تو روز اول، سفارش ۲۱:۰۰ ِ UTC ِ ۲ مارس تو روز سوم، روز وسط خالی)، `a day further - order 10, paid at 00 00 of 03-04 in Tehran, is in it`؛ `GetReportOverviewUcTest` › `from and to are whole Tehran days - …` |

---

## خرابکاری عمدی

| نگهبان | چی برداشته شد | تست(هایی) که قرمز شد |
|---|---|---|
| نسخه‌ی تنظیمات (ردیف ۵) | شرط `version eq settings.version` از `ShopSettingsRepositoryImpl.save` (هر ذخیره روی هر نسخه‌ای می‌نوشت) | `ShopSettingsRepositoryImplTest` › `a save over a version another save already moved writes nothing at all`، `two saves at the same moment over one version - one is written, the other is refused, 10 times`؛ `WebV1AdminSettingsOnDatabaseTest` › `two saves at the same moment over one version - one is 200, the other 409 …` |
| قفل نقش‌ها (ردیف ۲) | `pg_advisory_xact_lock` از `UserRepositoryImpl.lockRoleChanges` (به‌جاش یه `SELECT` ِ بی‌اثر) | `WebV1AdminCustomerDetailOnDatabaseTest` › `two admins taking ADMIN from each other at once - …, 10 times` (هر دو ۲۰۰، هیچ ADMIN ای نموند). خوندن دوباره‌ی نقش‌ها در گام ۴.۳ جدا خرابکاری شده بود |
| چک متن تکراری ِ کد، تنها (ردیف ۵) | `findByCode` ِ قبل از ذخیره در `CreateDiscountCodeUc` | **هیچ تستی قرمز نشد، و درسته:** قید یکتای `code` دیوار دومه و همون `409 DUPLICATE_CODE` رو می‌ده. این خرابکاری نشون می‌ده دیوار دوم تنها هم کافیه |
| گرفتن قید یکتا (ردیف ۵) | `catch (e: UniqueTakenException)` در `CreateDiscountCodeUc` | `two codes with one text at the same moment, 10 times - one 201, the other 409 DUPLICATE_CODE, never a 500, one row` (**تازه**؛ دومی `500` می‌گرفت) |
| هر دو با هم (ردیف ۵) | چک قبل از ذخیره و گرفتن قید | همون تست همزمانی، و `a code made with its text normalised is 201 with every field - the same text again is 409 DUPLICATE_CODE` (کد دوم `500`) |
| شرط لغو در «سفارش دوره» (ردیف ۱۹، گام ۴.۴) | `status <> 'CANCELLED'` از `ReportQmRepositoryImpl` | `ReportOverviewOnDatabaseTest` › `twelve orders - every field is the number worked out by hand` |
| روز تهران (ردیف ۱۹، گام ۴.۴) | منطقه‌ی زمانی ِ `dailyTotals` به `UTC` | `twelve orders - …`، `a day further - order 10, paid at 00 00 of 03-04 in Tehran, is in it` |
| «اولین» عکس (گام ۴.۴) | شرط `firstPhotosSentOn == null` در Projector | `OrderQmReportColumnsProjectorTest` › `the first photos sent set when - …`؛ `twelve orders - …` |

---

## چیزهایی که موقع کار پیدا شد

- **کانترکت: `meta` ِ خطای فیلد (حل شد، 1.11.1).** کانترکت 1.11.0 در چهار جا می‌گفت کد فیلدی با `meta` میاد (`meta.min`/`meta.max`، `maxLength`، `maxItems`، `maxDays`)، ولی شِمای `FieldError` `meta` نداشت و سرور اون رو فقط برای ساختن `message` استفاده می‌کرد. تصمیم تو راه الف بود: `FieldError.meta` ِ اختیاری و nullable اضافه شد (1.11.1، سازگار)، و سرور حالا `meta` ِ هر خطای فیلد رو می‌فرسته (بی‌داده ← `null`). هیچ `meta` ای داده‌ی شخصی نداره (فقط سقف‌ها، شناسه‌ها و گزینه‌های مجاز). `HttpEdgeTest` › `a field problem carries its own meta - …`.
- **یه تست فاز ۳ گاهی قرمز می‌شد.** `WebV1ChangeRequestsOnDatabaseTest` › `asked - CHANGE_REQUESTED, …, nothing in the change log` ردیف‌های لاگ تغییرات رو بدون `ORDER BY` می‌خوند و ترتیبشون رو مقایسه می‌کرد؛ تو اجرای کامل این گام برعکس اومد. حالا با `orderBy(AuditLogTbl.id)` می‌خونه. کد اصلی مشکلی نداشت.
- **یه هشدار کامپایلر از گام ۴.۲ مونده بود:** `DiscountTerms` یه `data class` با سازنده‌ی `private` ـه و `copy()` ِ عمومیش اون رو دور می‌زد. `@ConsistentCopyVisibility` گرفت (مثل `OrderTotals`)؛ کسی `copy` ـش رو صدا نمی‌زد.
- **ردیف ۵ برای کد تخفیف تست همزمانی نداشت.** دو ساخت همزمان با یه متن فقط با قید یکتا از هم جدا می‌شن؛ بدون `catch` دومی `500` می‌گرفت و هیچ تستی نمی‌دید. تست تازه (۱۰ بار، با latch بعد از چک) اضافه شد و با خرابکاری قرمز شد.
- **گزارش:** پنج سؤال طراحی پیش اومد و قبل از کد جواب گرفت (تصمیم‌های ۳۴ تا ۳۸): گرد کردن دو نرخ عکس تا جمعشون همیشه ۱۰۰ بشه، `discountTotal` بعد از حذف قلم، لغوشده‌ها در نرخ عکس، `topProducts` از ردیف امروز ِ محصول، و معنی «۳۶۶ روز».
- **backfill ِ `V30` تا حالا تست نداشت** (در گام ۴.۱ نوشته شده بود). `ReportColumnsBackfillTest` دو `UPDATE` ِ خود فایل migration رو روی ردیف‌های خالی‌شده دوباره اجرا می‌کنه.
- **محیط:** Postgres ِ محلی بعد از شب خاموش بود (مثل قبل)؛ با `pg_ctl start` بالا اومد و تست‌ها روی همون `5432` رفتن. Gradle با `-Djdk.net.unixdomain.tmpdir` ِ کوتاه.

---

## دور با پنل (مونده برای تو)

اینجا پنل و kiva-fe در دسترس نبودن؛ همین مسیرها با تست‌های route روی routeهای واقعی رفته شدن. دور واقعی:

1. **تنظیمات:** پنل ← تنظیمات ← هزینه‌ی پست و آستانه‌ی ارسال رایگان رو عوض کن و ذخیره کن. kiva-fe: سبد تازه با عددهای تازه. پنل ← لاگ تغییرات: یه ردیف `SETTINGS_UPDATED` فقط با همون دو فیلد.
2. همون فرم رو تو دو تب باز کن، تو هر دو یه چیز عوض کن و پشت سر هم ذخیره کن: دومی پیام «یکی دیگه همین الان عوضش کرد» (`409`).
3. `lowStockThreshold` رو ۳ کن: کارت محصولی با ۴ عدد تو kiva-fe دیگه «موجودی کم» نیست.
4. **کد تخفیف:** پنل ← کد تازه ` paeez۱۵ ` (۱۵٪، حداقل خرید ۲٬۰۰۰٬۰۰۰) ← تو لیست `PAEEZ15`. kiva-fe: تو سبد بزن ← تخفیف. پنل: غیرفعال کن ← kiva-fe: سبد رو تازه کن ← «کد تخفیف حذف شد». یه سفارش با کد بخر و بپرداز ← `usedCount` و `totalDiscountGiven` تو پنل.
5. **مشتری:** پنل ← مشتری‌ها ← یه مشتری با چند آدرس و سفارش ← آدرس پیش‌فرض اول، ۵ سفارش آخر (پرداخت‌نشده هم). فیلتر نقش `OPERATOR`.
6. **آخرین ADMIN:** با یه حساب ADMIN، نقش‌های خودت رو عوض کن ← `422` («نقش‌های خودت رو نمی‌تونی عوض کنی»). یه ADMIN ِ دوم بساز و از اون، ADMIN ِ اولی رو بردار ← موفق، و نشست‌های اولی باطل می‌شن. پیام `LAST_ADMIN` با یه درخواست تنها نمی‌رسه (تصمیم ۳۳)؛ فقط چک کن پنل `meta.reason` رو بشناسه.
7. **داشبورد:** بازه‌ی این ماه ← عددها با سفارش‌هایی که تو مرحله‌ی ۴ خریدی؛ نمودار روزانه همه‌ی روزها رو داره؛ بازه‌ی ۴۰۰ روزه ← پیام خطا زیر «تا».
