# ریویوی حذف حساب و رسید خرید: فاز ۱۴ (گام‌های ۱۴.۰ تا ۱۴.۴)

> برنچ `account-closure`، ۲ آبان ۱۴۰۵. نقشه‌ی راه: `COMPLETION_ROADMAP.md`، فاز ۱۴. راهنمای فرانت: `docs/FRONTEND_ACCOUNT.md`.
> ردیف ۱۷ ِ بخش ۳ ِ نقشه‌ی راه (حذف حساب با سفارش باز)، و خطرهای خود این فاز: توکنی که بعد از حذف کار کنه، داده‌ای که بعد از حذف بمونه، سفارشی که وسط حذف ثبت بشه و بی‌صاحب بمونه، رسید سفارش دیگران، و PDF ِ فارسی ِ خراب. برای هر کدوم: دفاع، تست نگهبان، و نتیجه‌ی خرابکاری عمدی.

---

## خلاصه

| چی بررسی شد | نتیجه |
|---|---|
| `./gradlew test --continue` | ✅ **۳۱۴۲ تست** سبز (اجرای کامل، و دو کلاسی که در اون اجرا قرمز شدن بعد از اصلاح دوباره اجرا شدن؛ پایین‌تر). ۵ تست skip: تست‌های ابزار واقعی (`vips` و `ffmpeg` روی این ماشین نیستن) |
| قوانین لایه‌ها | ✅ `domain`، `common` و `application` هیچ import از PDFBox، ICU4J، Ktor یا Exposed ندارن (پورت `InvoiceRenderer` در application؛ `PdfBoxInvoiceRenderer`، `PersianShaping` و `RtlText` در infrastructure) |
| کانترکت | ✅ 1.20.0 (گام ۱۴.۰)، و در ۱۴.۱ دو فیلد جاافتاده (`AdminOrderListItem.customerName/customerPhone`، `PreShipmentMedia.phone`) و `PAYMENT_FAILED` در «سفارش جاری» (ردیف ۸ ِ ۱۹.۷) |
| migration | ✅ `V39__account_deletion.sql` (`deletedOn`، `phone` ِ `NULL`‌پذیر با `user_deleted_phone_check`، `accountdeletionreason_tbl` ِ بی‌شناسه) |
| وابستگی‌های تازه | ✅ PDFBox `3.0.8`، ICU4J `79.1`، فونت Vazirmatn `v33.003` (OFL، با `OFL.txt`) |
| رسید با نمایشگر واقعی | ⬜ تست طلایی هر بار `build/golden/invoice.pdf` رو می‌نویسه؛ باز کردنش با یه نمایشگر PDF و با گوشی (روی سرور) با تو |
| دور واقعی روی سرور با kiva-fe | ⬜ مونده برای تو (پایین‌تر) |
| merge به `main` و deploy | ⬜ منتظر تأیید تو (این برنچ از سر فاز ۱۳ ساخته شده) |

---

## خطرهای این فاز

| خطر | دفاع | تست‌ها |
|---|---|---|
| حذف حساب با سفارش باز (ردیف ۱۷ ِ بخش ۳) | «سفارش جاری» (تصمیم‌های ۵ و ۱۷: ۹ وضعیت، مانده، استرداد باز) یه بار قبل از پیامک و دوباره **بعد از قفل‌ها** در `DELETE /me`؛ کدی که به‌خاطرش رد شد مصرف نمی‌شه | `ActiveOrdersCheckTest` (۱۸)؛ `WebV1AccountDeletionOnDatabaseTest` › `a customer with a SHIPPED order gets 422 …`، `an order placed and not paid is active too …`، `a code refused for an active order is not used up …` |
| سفارشی که همون لحظه‌ی حذف ثبت بشه | `DELETE /me` اول قفل سبد (۲) رو می‌گیره، که ثبت سفارش هم داره؛ ثبت سفارش کاربر رو بلافاصله بعد از همون قفل می‌خونه (تصمیم ۲۰) | `WebV1AccountDeletionOnDatabaseTest` › `placing an order and deleting the account at once - …` (۲۰ دور) |
| توکن حساب حذف‌شده هنوز کار کنه | `configureAuthentication` در هر درخواست واردشده `CheckAccountLiveUc` رو صدا می‌زنه (یه ستون)؛ پارامترش اجباریه، پس جاافتادنش کامپایل نمی‌شه؛ نشست‌ها `ACCOUNT_DELETED`؛ رفرش و پنل حساب حذف‌شده رو «نیست» می‌بینن | `AuthenticationTest` › `a valid token of an account deleted since is 401 …`؛ `AccountErasureOnDatabaseTest` › `the access token of that very moment is refused at once …`؛ `WebV1AccountDeletionOnDatabaseTest` › `without an active order - …` |
| داده‌ای که بعد از حذف بمونه | `AccountEraser`: هشت جدول ِ فقط‌مال‌کاربر پاک، نظرها بی‌نام؛ سفارش‌ها با رویداد `OrderPersonalDataErased` (Aggregate و read model و `searchText`)؛ ردیف کاربر سنگ‌قبر | `AccountErasureOnDatabaseTest` (۶: هر هشت جدول قبلش پر و بعدش خالی، نظر «کاربر کیوا»، سفارش در پنل و جستجو، مرجوعی و استرداد، سنگ‌قبر)؛ `OrderErasureTest`؛ `OrderQmErasureProjectorTest`؛ `UserRepositoryImplTest` |
| شماره‌ی حساب حذف‌شده قفل بمونه یا به حساب قدیمی برگرده | `phone` ِ سنگ‌قبر `NULL` و قید یکتا فقط بین شماره‌ها؛ ورود دوباره حساب تازه با شناسه‌ی تازه | `UserRepositoryImplTest` › `a deleted account's phone finds nobody, and logging in with it makes a new account …`، `any number of deleted accounts …`، `the database refuses a phone on a deleted account …` |
| حدس کد حذف | همون `OtpIssuer` و سقف‌های ورود؛ کد ورود برای حذف و برعکس پذیرفته نمی‌شه (هدف در hash)؛ «تله‌ی rollback»: تلاش غلط commit می‌شه | `WebV1AccountDeletionOnDatabaseTest` › `a login code is no code for the deletion …`، `a wrong code takes a try that stays …` |
| کارمند خودش رو حذف کنه (و «آخرین ADMIN» دور زده بشه) | `422 STAFF_ACCOUNT_CANNOT_BE_DELETED` در هر دو مرحله | `… an OPERATOR gets 422 …` |
| حساب حذف‌شده در پنل | لیست مشتری‌ها `deletedOn IS NULL`؛ جزئیات و نقش‌ها `404`؛ سفارش بی‌نام با `fullName`/`phone` ِ `null` | `WebV1AdminCustomersOnDatabaseTest` › `a deleted account is no customer - …`؛ `AccountErasureOnDatabaseTest` › `the order stays in the panel …` |
| رسید سفارش کس دیگه | `findOfCustomer` (مالکیت با توکن)؛ ناموجود، مال دیگری و کد بدشکل همه `404` و هیچ PDFای ساخته نمی‌شه | `WebV1InvoiceOnDatabaseTest` › `someone else's order, one that isn't there and a code that can't be one are all 404 …` |
| PDF ِ فارسی ِ خراب (حروف جدا، چپ‌به‌راست) | `PersianShaping` (تصمیم ۲۱) + `Bidi` ِ ICU؛ نویسه‌ی بی‌گلیف حذف به‌جای شکست | `RtlTextTest` (۶)؛ `InvoiceRendererGoldenTest` (تصویر طلایی با چشم چک‌شده، تفاوت ≤ ۰٫۵٪) |
| رندر PDF که کانکشن دیتابیس رو نگه داره | رندر بعد از تراکنش ِ خوندن | `WebV1InvoiceOnDatabaseTest` › `the PDF is drawn outside the transaction` |
| رسید در کش مرورگر یا CDN | `Cache-Control: private, no-store` | `WebV1InvoiceOnDatabaseTest` › `a paid order's receipt - …` |

---

## خرابکاری عمدی

یه اسکریپت هر نگهبان رو برداشت، تست‌های مربوط رو اجرا کرد و برش گردوند؛ **هر ۱۸ تا قرمز کردن**:

| # | خرابکاری | تستی که قرمز شد |
|---|---|---|
| ۱ | `validate` بدون چک حساب حذف‌شده | `AuthenticationTest` › `a valid token of an account deleted since is 401 …` |
| ۲ | لیست مشتری‌ها بدون `deletedOn IS NULL` | `WebV1AdminCustomersOnDatabaseTest` › `a deleted account is no customer …` (بعد از قوی‌تر شدن: اول فقط تست دیگه‌ای قرمز می‌شد)، `admin and support see the customers …` |
| ۳ | چک سفارش جاری بعد از قفل‌ها برداشته | `WebV1AccountDeletionOnDatabaseTest` › `a code refused for an active order is not used up …` |
| ۴ | قفل سبد (۲) در `DELETE /me` برداشته | `… placing an order and deleting the account at once …` |
| ۵ | یه خط `AccountEraser` (هشدارهای موجودی) | `AccountErasureOnDatabaseTest` › `everything only hers is gone …` |
| ۶ | رسید با `findForPanel` (بی‌مالکیت) | `WebV1InvoiceOnDatabaseTest` › `someone else's order … are all 404 …` |
| ۷ | رندر داخل تراکنش | `… the PDF is drawn outside the transaction` |
| ۸ | استرداد باز حساب نشه | `ActiveOrdersCheckTest` › `a finished order with a refund still on its way is active` (۳ وضعیت) |
| ۹ | `PAYMENT_FAILED` جاری نباشه | `ActiveOrdersCheckTest` › `[2] PAYMENT_FAILED`، `every status is one or the other …` |
| ۱۰ | `searchText` بعد از پاک کردن عوض نشه | `OrderQmErasureProjectorTest` › `the search text is made again …`؛ `AccountErasureOnDatabaseTest` › `the order stays in the panel …` |
| ۱۱ | پاک کردن دوباره رویداد بسازه | `OrderErasureTest` › `erasing an erased order again records nothing` |
| ۱۲ | رسید سفارش پرداخت‌نشده | `WebV1InvoiceOnDatabaseTest` › `an order not paid is 422 …` |
| ۱۳ | کد حذف قبل از چک مانع‌ها | `WebV1AccountDeletionOnDatabaseTest` › `a customer with a SHIPPED order gets 422 … no SMS goes`، `an order placed and not paid is active too …` |
| ۱۴ | شماره‌ی پیام‌رسان سفارش بمونه | `OrderErasureTest` › `the messenger keeps its channel only …` |
| ۱۵ | نظرها بی‌نام نشن | `AccountErasureOnDatabaseTest` › `her review stays on the product's page as «کاربر کیوا» …` |
| ۱۶ | نشست‌ها باطل نشن | `AccountErasureOnDatabaseTest` › `the account is a tombstone, every session ends …` |
| ۱۷ | بدون `PersianShaping` | `InvoiceRendererGoldenTest` › `the receipt's page looks as it was checked …`؛ `RtlTextTest` (۵ تست) |
| ۱۸ | کد غلط با rollback (تله‌ی rollback) | `WebV1AccountDeletionOnDatabaseTest` › `a wrong code takes a try that stays …` |

**دو تست که در اجرای کامل قرمز شدن** (و اصلاح شدن): `WebV1MyOrdersOnDatabaseTest` هنوز `invoiceUrl = null` رو برای سفارش پرداخت‌شده انتظار داشت (قانون تازه‌ی تصمیم ۱۳)؛ و `ReturnsMigrationTest` شناسه‌ی مرجوعی ِ ثابت `12` رو آزاد فرض می‌کرد، در حالی که تست‌های تازه‌ی این فاز مرجوعی ِ واقعی بیشتری می‌سازن و `12` گرفته شده بود (حالا `990012`).

---

## چیزهایی که موقع کار پیدا شد

- **ICU4J حروف خاص فارسی رو شکل نمی‌ده.** `ArabicShaping` (که نقشه‌ی راه گفته بود) `پ چ ژ ک گ ی` رو دست‌نخورده می‌ذاره؛ اولین تصویر رسید «رسید» رو با `ی` ِ جدا نشون داد. یه جدول کوچیک ِ خودمون (`PersianShaping`) همه‌ی حروف فارسی و لام‌الف رو شکل می‌ده و ICU فقط ترتیب راست‌به‌چپ رو می‌سازه (تصمیم ۲۱). هر دو فونت همه‌ی فرم‌های لازم رو دارن (چک با جدول `cmap`).
- **دو فیلد کانترکت جا افتاده بود:** `AdminOrderListItem.customerName/customerPhone` و `PreShipmentMedia.phone` برای سفارش بی‌نام (تصمیم ۱۸).
- **`PAYMENT_FAILED` هم سفارش جاریه** (تصمیم ۱۷): بدون این، حذف وسط یه پرداخت دوباره یه سفارش پرداخت‌شده‌ی بی‌صاحب می‌ساخت.
- **ثبت سفارش و حذف همزمان** جواب `CART_EMPTY` می‌داد (حذف سبد رو پاک می‌کنه)؛ `PlaceOrderUc` حالا کاربر رو زیر قفل سبد و قبل از چک سبد می‌خونه (تصمیم ۲۰).
- **پشته‌های تست حساب‌ها رو با پیشوند شماره پاک می‌کردن**؛ سنگ‌قبر شماره نداره، پس حالا `deletedOn IS NOT NULL` هم پاک می‌شه (فقط تست‌ها در `kiva_test` حساب حذف می‌کنن).
- **متن‌های رسید در لایه‌ی HTTP** (`InvoiceTexts`) و نه در UseCase (تصمیم ۲۲).
- **نشانی فروشگاه** هنوز داده‌ای نداره؛ رسید اسم برند، تلفن و ایمیل پشتیبانی و سایت رو داره (تصمیم ۲۳، سؤال باز ۱۲).
- **یادداشت‌های متنی ِ خود مشتری در تایم‌لاین** (یادداشت لغو، یادداشت درخواست تغییر) جزو فهرست تصمیم ۲ نبودن و بعد از حذف می‌مونن؛ فقط پنل می‌بیندشون. اگه لازمه، یه گسترش کوچیک ِ همون رویداد.

---

## دور واقعی (برای تو)

1. deploy روی سرور (بعد از merge)؛ migration ِ `V39` با بالا اومدن اجرا می‌شه.
2. با یه حساب آزمایشی: سفارش بذار و پرداخت کن، رسیدش رو از صفحه‌ی سفارش دانلود کن و با گوشی باز کن (حروف به هم چسبیده، راست‌به‌چپ، تاریخ شمسی).
3. «حذف حساب» رو بزن: باید `422` با کد همون سفارش بیاد و پیامکی نیاد. سفارش رو در پنل «تحویل شد» کن، دوباره بزن: پیامک کد میاد؛ حذف ← خروج از همه‌جا.
4. در پنل: مشتری در لیست نیست؛ سفارشش «حساب حذف‌شده» با استان و شهر.
5. دوباره با همون شماره وارد شو: حساب تازه‌ی خالی، بی‌سفارش.
