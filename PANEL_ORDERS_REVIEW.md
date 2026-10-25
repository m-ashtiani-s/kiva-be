# ریویوی کار روی سفارش: فاز ۲ (گام‌های ۲.۰ تا ۲.۱۱)

> برنچ `panel-orders`، ۲۶ مهر ۱۴۰۵. نقشه‌ی راه: `COMPLETION_ROADMAP.md`، فاز ۲. راهنمای فرانت: `docs/FRONTEND_PANEL_ORDERS.md` (پنل) و بخش ۶ ِ `docs/FRONTEND_PURCHASE.md` (مشتری).
> برای هر ردیف بخش ۳ ِ نقشه‌ی راه که مال این فازه: دفاع کجاست، کدوم تست نگهبانشه، و نتیجه‌ی خرابکاری عمدی (نگهبان یه بار برداشته شد، تست قرمز شد، برگردونده شد).

---

## خلاصه

| چی بررسی شد | نتیجه |
|---|---|
| `./gradlew test --rerun-tasks` | ✅ **۲۳۸۷ تست** سبز (common ۲۰۹، domain ۱۰۰۶، application ۲۰۳، اصلی ۹۶۹). ۴ تست skip: تست‌های ابزار واقعی (`VipsFfmpegMediaProcessorTest`) چون `vips` و `ffmpeg` روی این ماشین نیستن |
| هشدار کامپایلر | ✅ صفر |
| قوانین لایه‌ها | ✅ `domain`، `common` و `application` هیچ import از Ktor، Exposed یا Koin ندارن |
| کانترکت | ✅ 1.9.0 (گام ۲.۰)؛ ردیف‌های ۱۷ و ۱۸ ِ ۷.۱۰ وسط کار اضافه شدن. تست کل مسیر کلیدهای هر شیء صفحه‌ی سفارش رو با شِمای کانترکت مقایسه می‌کنه (`ContractSchemas`) |
| اسکن لاگ | ✅ `PanelOrdersLogScanOnDatabaseTest`، با تست همراه ِ نشت عمدی و یه خرابکاری |
| دور با پنل | ⬜ مونده برای تو (پایین‌تر) |
| merge به `main` | ⬜ منتظر تأیید تو (فاز ۱ هم هنوز merge نشده؛ این برنچ از سر فاز ۱ ساخته شده) |

---

## ردیف‌های بخش ۳

| # | خطر | دفاع | تست‌ها |
|---|---|---|---|
| 6 | استرداد بیشتر از پرداخت | قفل ردیف سفارش (۹) در `CreateRefundUc`؛ «جمع استردادهای غیر `FAILED` ≤ پرداخت‌شده» در `RefundDomainService`؛ `CHECK` ِ `orderqm_refunded_check` دیوار دوم | `WebV1AdminRefundsOnDatabaseTest` › `two refunds that fit alone but not together, opened at once 20 times - one is 201, the other 400, never more than paid`، `a refund opens PENDING, expected in 72 hours - more than is refundable is 400 REFUND_EXCEEDS_PAID …`، `a failed refund gives its amount back to what is refundable …`؛ `RefundDomainServiceTest` › `paid 2,000,000 with a PENDING refund of 500,000 and a FAILED one of 300,000 - 1,500,000 is still refundable` |
| 7 | ارسال سفارش بدهکار یا لغوشده | `Order.ship` فقط از `PHOTO_SENT` و بعد از تأیید یا مهلت؛ لغو و ارسال هر دو قفل ۹ رو می‌گیرن و قید یکتای stream دیوار دومه. (`balanceDue` با فاز ۳) | `WebV1ShipmentOnDatabaseTest` › `an operator cancels while another ships, 20 times - one of them, never a cancelled order shipped, the stock right`، `what can't ship - …`؛ `OrderAfterPaymentTest` › `only a PHOTO_SENT order ships - PROCESSING and SHIPPED are 422 with what is allowed`، `before approval and before the deadline it is AWAITING_CUSTOMER_FEEDBACK - after the deadline without an answer it ships`؛ گروه: `a group ships as one parcel - not while it holds, not while one isn't approved, …` |
| 9 | دو بار برگردوندن موجودی | `releaseForOrder` بی‌اثر بار دوم؛ `Order.cancel` فقط یه بار؛ لغو مشتری و اپراتور هر دو از `OrderCancellation` با قفل‌های ۵، ۶، ۹ | `WebV1OrderCancellationOnDatabaseTest` › `the customer and an operator cancel at once, 20 times - one OrderCancelled, one refund, the stock back once`؛ `after a photo the customer can't cancel …` (لغو دوباره ← ۴۲۲) |
| 10 | پیدا کردن سفارش دیگران با رهگیری مهمان | کد و موبایل (خریدار یا گیرنده) با هم؛ هر «پیدا نشد» همون `404 TRACKING_NOT_FOUND`؛ ۱۰ در ۱۰ دقیقه برای هر IP | `WebV1TrackingOnDatabaseTest` › `a guest follows a paid order with the recipient's or the buyer's phone - no address, prices or names, and anything else is the same 404`، `the eleventh lookup in 10 minutes from one address is 429 with Retry-After - another address is still let through` |
| 11 | لو رفتن داده در رهگیری روزانه | ماسک در application (`DailyShipmentMask`)، فقط `showInDailyList`، فقط ۳۰ روز، یه ردیف برای هر کد | `WebV1TrackingOnDatabaseTest` › `the daily list - masked only, a group one row, not what isn't listed, q by phone, letter and city, and the tabs` (روی بدنه‌ی خام)؛ `DailyShipmentMaskTest` |
| 15 | داده‌ی شخصی در لاگ | لاگ برنامه بدون موبایل کامل، اسم گیرنده، شماره‌ی پیگیری بانک، متن یادداشت‌ها و پیام عکس، IP؛ هر `Cmd` و درخواستِ شخصی `toString` ِ خودش رو داره | `PanelOrdersLogScanOnDatabaseTest` › `an order paid, photographed, noted, shipped, followed and delivered, and one cancelled and given back, leave nothing personal in the log` (و `the scan finds a whole number, a name and a reference, so a pass means something`)؛ لاگ تغییرات: `an OPERATOR can do neither - the change log has both, the bank's reference by name only`، `the customer's cancel leaves nothing in the change log - the panel's leaves ORDER_STATUS_CHANGED` |
| 19 | خطای منطقه‌ی زمانی | همه‌ی «روز»های رهگیری با `ShopCalendar.ZONE` (تهران) | `WebV1TrackingOnDatabaseTest` › `a parcel handed over at a quarter to nine UTC is on the next day's list - Tehran's day, not UTC's` (۲۰:۴۵ ِ UTC = ۰۰:۱۵ ِ تهران)؛ فیلتر `from`/`to` ِ صف: `WebV1AdminOrdersOnDatabaseTest` › `from and to are Tehran days - an order at 22h00 UTC on Thursday was placed on Friday` |

---

## خرابکاری عمدی

| نگهبان | چی برداشته شد | تست(هایی) که قرمز شد | گام |
|---|---|---|---|
| قفل ۹ در استرداد | `lockOrder` از `CreateRefundUc` | `two refunds that fit alone but not together, opened at once 20 times …` | ۲.۶ |
| چک «غیر `FAILED`» | استرداد `FAILED` هم شمرده شد | `RefundDomainServiceTest` › `… 1,500,000 is still refundable`؛ `a failed refund gives its amount back …` | ۲.۶ |
| ترتیب قفل در ارسال گروهی | مرتب‌سازی `(productId, variantId)` از `InventoryDomainService.shipForOrders` | `A (color 1, color 2) and B (color 2, color 1) ship at once, 50 times - never a deadlock` (با `deadlock detected` ِ Postgres) | ۲.۸ |
| چک تأیید/مهلت قبل از ارسال | شرط `AwaitingFeedback` در `Order.ship` | `OrderAfterPaymentTest` › `before approval and before the deadline …`؛ `what can't ship - …`؛ `a group ships as one parcel - …` | ۲.۱۱ |
| چک وضعیت در ارسال | `Order.ship` هر وضعیتی جز `SHIPPED` رو قبول کرد | `an operator cancels while another ships, 20 times …`؛ `only a PHOTO_SENT order ships …` | ۲.۱۱ |
| چک مالکیت در `media` | `GetMyOrderMediaUc` سفارش رو بدون کاربر پیدا کرد | `the customer approves - … someone else is 404 …` | ۲.۱۱ |
| چک مالکیت در `cancel` | `CancelMyOrderUc` سفارش رو بدون کاربر پیدا کرد | `the panel moves only where allowed - …` (**بعد از تقویت تست**: پایین‌تر) | ۲.۱۱ |
| چک مالکیت در `media-feedback` | `SubmitMediaFeedbackUc` سفارش رو بدون کاربر بار کرد | `the customer approves - …` (**بعد از تقویت تست**) | ۲.۱۱ |
| یکسانی ۴۰۴ ِ رهگیری مهمان | موبایل نادرست جواب دیگه‌ای گرفت | `a guest follows a paid order …` | ۲.۱۱ |
| ماسک روزانه | `recipientMasked = name` ِ کامل | `the daily list - masked only, …` | ۲.۱۱ |
| سقف درخواست رهگیری | `rateLimit(TRACKING_LOOKUP_RATE_LIMIT)` از route | `the eleventh lookup in 10 minutes …` | ۲.۱۱ |
| لاگ برنامه | متن یادداشت به خط لاگ `AddOrderNoteUc` اضافه شد | `PanelOrdersLogScanOnDatabaseTest` › `an order paid, photographed, …` | ۲.۱۱ |

---

## چیزهایی که موقع کار پیدا شد

- **دو تست مالکیت چیزی رو نمی‌پاییدن.** با برداشتن چک مالکیت از `CancelMyOrderUc` (و `SubmitMediaFeedbackUc`)، سفارش کس دیگه واقعاً لغو (یا تأیید) می‌شد، ولی route بعد از Command سفارش رو با کاربر توکن دوباره می‌خوند و `404` می‌داد، پس تست سبز می‌موند. حالا هر دو تست بعد از `404` وضعیت سفارش رو هم می‌سنجن (هنوز `PROCESSING`، بدون استرداد؛ عکس هنوز `SENT`). این یه درس عمومیه: برای Commandی که route بعدش Query می‌زنه، `404` به‌تنهایی ثابت نمی‌کنه چیزی عوض نشده.
- **سه تست لاگ تغییرات** ردیف‌ها رو بدون `ORDER BY` می‌خوندن و گاهی قرمز می‌شدن (دو ردیف با یه زمان). حالا به ترتیب `id` (گام ۲.۹).
- **ترتیب قفل در `UpdateRefundUc`** از «استرداد، بعد سفارش» به «سفارش، بعد استرداد» عوض شد تا با `CreateRefundUc` بن‌بست نسازه (گام ۲.۶).
- **ارسال گروهی** نمی‌تونست `shipForOrder` رو برای هر سفارش جدا صدا بزنه: دو سفارش با رنگ مشترک یه Aggregate ِ موجودی رو دو بار می‌خوندن و ذخیره می‌کردن، و ترتیب قفل بین سفارش‌ها کلی نبود. `shipForOrders` ِ تازه هر دو رو درست کرد (گام ۲.۸).
- **۴۲۹ ِ plugin ِ RateLimit** (کد ورود هم) `meta.retryAfterSeconds` نداشت؛ حالا از `Retry-After` پر می‌شه (گام ۲.۹).
- **`PAYMENT_IN_PROGRESS`** (لغو وقتی پرداختی در حال تأییده) فقط در کد و بدون تست HTTP ـه: رسوندن پرداخت به `VERIFYING` وسط لغو در تست پایدار نیست.
- **`giftWrapCost = 0` و `gift.wrap = false`** تنها مقدارهای ثابت پاسخ سفارش‌ان که مال فاز بعدی نیستن: بسته‌بندی کادو تا `giftWrap.available = false` ـه وجود نداره.

---

## دور با پنل (مونده برای تو)

اینجا پنل و kiva-fe در دسترس نبودن؛ همین مسیر با تست‌های روی دیتابیس واقعی (routeهای واقعی، پرداخت با درگاه آزمایشی) رفته شد: `WebV1MyOrderJourneyOnDatabaseTest` (پرداخت، عکس، تأیید، ارسال، تحویل، با مقایسه‌ی کانترکت بعد از هر قدم) و `totalSpent leaves out an order cancelled and given back in full`. دور واقعی:

1. یه سفارش از kiva-fe بخر و بپرداز (درگاه آزمایشی).
2. پنل: صف ← سفارش ← آپلود عکس (`PRE_SHIPMENT`) ← پیش‌نویس ← «ارسال شد» (پیامک «عکس کیفت فرستاده شد» تو لاگ `LogSmsSender` یا گوشی).
3. kiva-fe: «همونه!» روی صفحه‌ی سفارش.
4. پنل: ارسال با کد رهگیری (پیامک «ارسال شد»)؛ kiva-fe: رهگیری مهمان با کد و موبایل گیرنده، و لیست روزانه.
5. پنل: «تحویل شد» (پیامک)؛ kiva-fe: `returnableUntil` روی ردیف.
6. یه سفارش دیگه: لغو از kiva-fe ← استرداد `PENDING` ← پنل (ADMIN): `PROCESSING` ← `COMPLETED` با شماره‌ی پیگیری (پیامک «پولت برگشت»)؛ `totalSpent` ِ مشتری کم شد.
7. الگوهای پیامک تازه رو در پنل فراز بساز و در `KIVA_FARAZ_*_PATTERN` بذار (README).
