# ریویوی مرجوعی: فاز ۷ (گام‌های ۷.۰ تا ۷.۶)

> برنچ `returns`، ۳۰ مهر ۱۴۰۵. نقشه‌ی راه: `COMPLETION_ROADMAP.md`، فاز ۷. راهنمای فرانت: `docs/FRONTEND_RETURNS.md`.
> برای هر ردیف بخش ۳ ِ نقشه‌ی راه که مال این فازه، و خطرهای خود این فاز: دفاع کجاست، کدوم تست نگهبانشه، و نتیجه‌ی خرابکاری عمدی (نگهبان یه بار برداشته شد، تست قرمز شد، برگردونده شد).

---

## خلاصه

| چی بررسی شد | نتیجه |
|---|---|
| `./gradlew test --continue --rerun-tasks` | ✅ **۲۸۵۴ تست** سبز. ۵ تست skip: تست‌های ابزار واقعی (`vips` و `ffmpeg` روی این ماشین نیستن) |
| هشدار کامپایلر | ✅ صفر |
| قوانین لایه‌ها | ✅ `domain`، `common` و `application` هیچ import از Ktor، Exposed یا Koin ندارن |
| کانترکت | ✅ 1.13.0 (گام ۷.۰). هر پاسخ ِ مرجوعی در تست‌های route با شِمای `ReturnRequest` و `AdminReturnRequest` مقایسه می‌شه (`ContractSchemas`) |
| اسکن لاگ | ✅ `ReturnsLogScanOnDatabaseTest` (تازه): کل مسیر سه مرجوعی (استرداد، رد، بستن) |
| دور واقعی روی سرور با پنل | ⬜ مونده برای تو (پایین‌تر) |
| الگوهای پیامک در فراز | ⬜ مونده برای تو: چهار الگو (README، `LAUNCH.md`، `kiva.env.example`) |
| merge به `main` و deploy | ⬜ منتظر تأیید تو (فازهای قبل هم هنوز merge نشدن؛ این برنچ از سر فاز ۶ ساخته شده) |

---

## ردیف‌های بخش ۳ و خطرهای این فاز

| # | خطر | دفاع | تست‌ها |
|---|---|---|---|
| 3 | فایل مخرب در آپلود مشتری | همون `UploadMediaUc` ِ پنل (نوع از روی محتوا، سقف حجم موقع خوندن، دوباره انکود و پاک شدن metadata)، با سقف‌های مشتری (۸ مگ عکس، ۵۰ مگ و ۶۰ ثانیه ویدیو) و فقط دو purpose ِ مشتری؛ حداکثر ۲۰ آپلود در ساعت با شمارش دوم زیر قفل advisory | `WebV1MyUploadsOnDatabaseTest` › `a photo of 9 MB is 413 …`، `a video of 40 MB and 50 seconds is 201 - one of 70 seconds is 400 VIDEO_TOO_LONG on file`، `a staff purpose from a customer is 400 MEDIA_PURPOSE_INVALID - no token is 401`، `the 21st upload of the hour is 429 RATE_LIMITED with Retry-After …`، `25 uploads of one customer at the same moment leave exactly 20 rows …`، `with the real tools, a customer's photo with GPS is stored without any metadata` (skip بدون ابزار) |
| 15 | داده‌ی شخصی در لاگ | `Cmd` و DTO ها و پاسخ‌ها `toString` ِ بدون متن (توضیح مشتری، دستورالعمل، دلیل‌ها، یادداشت‌ها)؛ لاگ تغییرات فقط وضعیت و عددها | `ReturnsLogScanOnDatabaseTest` › `a return asked, approved, collected, received and refunded, one turned down and one closed, leave nothing personal in the log` (**تازه**) و تست همراهش |
| — | مدیای کاربر دیگه | `ReturnDomainService.open` فقط `RETURN_EVIDENCE` ِ خود کاربر؛ مال دیگری، purpose ِ دیگه و نبودن همه یه جواب (`400 MEDIA_NOT_FOUND`)؛ پاک شدن وسط کار هم همون | `WebV1MyReturnsOnDatabaseTest` › `someone else's photo is the same MEDIA_NOT_FOUND as one that isn't there …`، `a photo uploaded for something else is MEDIA_NOT_FOUND too`؛ `ReturnDomainServiceTest` |
| — | دو مرجوعی برای یه سفارش | قفل ۹ (ردیف سفارش) قبل از هر چیز؛ `Order.returnRefusal` (`RETURN_REQUESTED` یا `returnSettled`)؛ دیوار دوم: `returnrequest_one_per_order_idx` | `WebV1MyReturnsOnDatabaseTest` › `two returns of one order asked at the same moment, 20 times - always exactly one`؛ `ReturnRequestRepositoryImplTest` › `a second return of an order that isn't REJECTED is refused by returnrequest_one_per_order_idx …`؛ `OrderReturnTest` › `after a return was closed or refunded it is never returned again …` |
| 6 | استرداد دوباره برای یه مرجوعی | `CreateRefundUc` زیر قفل‌های ۹ و ۱۰: فقط `RECEIVED` و بدون استرداد ِ غیر `FAILED` (`422 RETURN_REFUND_EXISTS`)؛ دیوار دوم: `refund_one_live_per_return_idx`؛ سقف «قابل استرداد» ِ فاز ۲ سر جاشه | `WebV1AdminReturnReceiptOnDatabaseTest` › `a return's refund - another order's return 400, before RECEIVED 422, a second one 422 RETURN_REFUND_EXISTS, after the first FAILED another 201`؛ `ReturnsMigrationTest` › `one refund of a return that isn't FAILED at a time …` |
| 9 | موجودی دوباره برمی‌گرده | رسید فقط از `APPROVED`/`PICKUP_SCHEDULED` و زیر قفل‌های ۹ و ۱۰؛ رسید دوم ← `422`؛ `restockForReturn` به ترتیب قفل ۷ | `WebV1AdminReturnReceiptOnDatabaseTest` › `two operators record one parcel at the same moment, 10 times - one 200, one 422, the stock up once` |
| — | مهلت ۷ روزه | `Order.returnRefusal`: `now ≤ returnableUntil` (snapshot ِ لحظه‌ی تحویل) | `OrderReturnTest` › `at the very end of the window it is still asked - a nanosecond later RETURN_WINDOW_EXPIRED …`؛ `WebV1MyReturnsOnDatabaseTest` › `a second return is 422 ORDER_NOT_RETURNABLE - and on day 8 RETURN_WINDOW_EXPIRED with the window's end` |
| 1 | بالا رفتن دسترسی | پنج route ِ پنل `[ADMIN, OPERATOR]`، استرداد `[ADMIN]` | `RouteGuardsTest` (هر route با کانترکت)؛ `WebV1AdminReturnsOnDatabaseTest` › `a customer and a catalog manager are 403 on the queue, the decision and the pickup …`؛ `WebV1AdminReturnReceiptOnDatabaseTest` › `the operator records the receipt though the stock's form is not theirs …` |

---

## خرابکاری عمدی

| نگهبان | چی برداشته شد | تست(هایی) که قرمز شد |
|---|---|---|
| مالکیت مدیای مرجوعی | `&& it.uploadedBy == userId` از `ReturnDomainService.open` | `WebV1MyReturnsOnDatabaseTest` › `someone else's photo is the same MEDIA_NOT_FOUND as one that isn't there …`؛ `ReturnDomainServiceTest` › `someone else's upload, another purpose's, or one not there are all the same MEDIA_NOT_FOUND on their own field` |
| ایندکس یکتای «یک مرجوعی» | `UNIQUE` از `returnrequest_one_per_order_idx` (خود `V32`، موقتاً) | `ReturnRequestRepositoryImplTest` › `a second return of an order that isn't REJECTED is refused by returnrequest_one_per_order_idx …` |
| چک `RETURN_REFUND_EXISTS` (گام ۷.۵) | `if (live.isNotEmpty()) …` از `CreateRefundUc` | `WebV1AdminReturnReceiptOnDatabaseTest` › `a return's refund - … a second one 422 RETURN_REFUND_EXISTS …` (ایندکس یکتا ۵۰۰ می‌داد) |
| ایندکس یکتای «یه استرداد زنده برای هر مرجوعی» | `UNIQUE` از `refund_one_live_per_return_idx` (خود `V32`، موقتاً) | `ReturnsMigrationTest` › `one refund of a return that isn't FAILED at a time - after a FAILED one another goes in` |
| مهلت ۷ روزه | `now.isAfter(until)` در `Order.returnRefusal` به `false` | `OrderReturnTest` › `at the very end of the window it is still asked - a nanosecond later RETURN_WINDOW_EXPIRED …`؛ `WebV1MyReturnsOnDatabaseTest` › `a second return is 422 ORDER_NOT_RETURNABLE - and on day 8 RETURN_WINDOW_EXPIRED …` |
| سقف ساعتی آپلود | `if (recent.size < CUSTOMER_UPLOADS_PER_WINDOW) return null` از `MediaLimitsPolicy.customerUploadRefusal` (همیشه null) | `WebV1MyUploadsOnDatabaseTest` › `the 21st upload of the hour is 429 RATE_LIMITED …`، `25 uploads of one customer at the same moment leave exactly 20 rows …` |
| قفل ۹ در درخواست (گام ۷.۳) | `lockOrder` از `CreateReturnUc` | `WebV1MyReturnsOnDatabaseTest` › `two returns of one order asked at the same moment, 20 times - always exactly one` |
| رد ← سفارش `DELIVERED` (گام ۷.۴) | `order.endReturn(REJECTED)` از `DecideReturnUc` | `WebV1AdminReturnsOnDatabaseTest` › `turned down without a reason is 400 - with one REJECTED, the order DELIVERED again …` |
| بی‌نامی لاگ | توضیح مشتری به خط اول لاگ `CreateReturnUc` اضافه شد | `ReturnsLogScanOnDatabaseTest` › `a return asked, approved, collected, received and refunded, …` |

---

## چیزهایی که موقع کار پیدا شد

- **تست‌های لاگ تغییرات باید `entityType` رو هم فیلتر کنن.** شناسه‌ی استرداد و مرجوعی هر دو از ۱ شروع می‌شن و جدول لاگ تغییرات هیچ‌وقت پاک نمی‌شه، پس فیلتر فقط با `entityId` ردیف‌های `REFUND_*` رو هم برمی‌داشت. تست‌های این فاز با `entityType = RETURN` می‌خونن.
- **پیامک بستن مرجوعی با ۷.۴ اومد، نه ۷.۵.** مسیر ۹ مرحله‌ای هر چهار پیامک یکیه و جدا کردنش فقط کار تکراری بود.
- **`suggestedRefund` در ۷.۴ ساخته شد** (همون سرویس `AdminReturnRequests` که پاسخ هر قدم رو می‌سازه) و تستش با ۷.۵ اومد.
- **محیط این ماشین:** سرویس Postgres ِ محلی رمز `kiva` رو قبول نمی‌کنه، پس تست‌ها روی یه Postgres ِ موقت (پورت ۵۴۳۳، `KIVA_TEST_DB_URL`) رفتن.

---

## دور واقعی روی سرور (مونده برای تو)

اینجا سرور، پنل و kiva-fe در دسترس نبودن؛ همین مسیرها با تست‌های route روی routeهای واقعی رفته شدن. دور واقعی:

1. چهار الگوی پیامک (`KIVA_FARAZ_RETURN_APPROVED_PATTERN`، `…_REJECTED_…`، `…_PICKUP_…`، `…_CLOSED_…`) رو در پنل فراز بساز، تأیید بگیر و در `kiva.env` بذار (`set-env.sh`؛ `launch-check.sh` حالا چکشون می‌کنه).
2. kiva-fe: یه سفارش بخر، پنل: عکس، ارسال، «تحویل شد».
3. kiva-fe: «مرجوع کردن» ← یه قلم با `NOT_AS_PICTURED` و یه عکس ← «مرجوعی‌های من».
4. پنل: صف مرجوعی‌ها ← تأیید (پیامک) ← جمع‌آوری با تیپاکس (پیامک) ← رسید (یه عدد به موجودی) ← تاریخچه‌ی موجودی رو ببین (`RETURNED`، کد مرجوعی، اسم اپراتور) ← استرداد با `returnId` و مبلغ پیشنهادی ← `COMPLETED` با شماره‌ی پیگیری (پیامک «پولت برگشت»).
5. یه مرجوعی دیگه: تأیید ← بستن با دلیل (پیامک) ← صفحه‌ی سفارش: `DELIVERED` و بدون دکمه‌ی مرجوع.
