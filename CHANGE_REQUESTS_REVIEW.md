# ریویوی درخواست تغییر: فاز ۳ (گام‌های ۳.۰ تا ۳.۵)

> برنچ `change-requests`، ۲۷ مهر ۱۴۰۵. نقشه‌ی راه: `COMPLETION_ROADMAP.md`، فاز ۳. راهنمای فرانت: بخش ۷ ِ `docs/FRONTEND_PANEL_ORDERS.md` (پنل) و «درخواست تغییر و پرداخت مانده» در بخش ۶ ِ `docs/FRONTEND_PURCHASE.md` (مشتری).
> برای هر ردیف بخش ۳ ِ نقشه‌ی راه که مال این فازه: دفاع کجاست، کدوم تست نگهبانشه، و نتیجه‌ی خرابکاری عمدی (نگهبان یه بار برداشته شد، تست قرمز شد، برگردونده شد).

---

## خلاصه

| چی بررسی شد | نتیجه |
|---|---|
| `./gradlew test --continue --rerun-tasks` | ✅ **۲۵۰۵ تست** سبز (common ۲۰۹، domain ۱۰۷۹، application ۲۰۳، اصلی ۱۰۱۴). ۴ تست skip: تست‌های ابزار واقعی (`VipsFfmpegMediaProcessorTest`) چون `vips` و `ffmpeg` روی این ماشین نیستن |
| هشدار کامپایلر | ✅ صفر |
| قوانین لایه‌ها | ✅ `domain`، `common` و `application` هیچ import از Ktor، Exposed یا Koin ندارن |
| کانترکت | ✅ 1.10.0 (گام ۳.۰). تست کل مسیر ِ تازه کلیدهای هر شیء صفحه‌ی سفارش رو بعد از هر قدم ِ درخواست تغییر با شِمای کانترکت مقایسه می‌کنه (`ContractSchemas`)، و هویت جمع‌های تصمیم ۷ رو بعد از هر قدم |
| اسکن لاگ | ✅ `PanelOrdersLogScanOnDatabaseTest` یه تست تازه برای مسیر درخواست تغییر گرفت (یادداشت مشتری و اپراتور، پرداخت مانده) |
| دور با پنل | ⬜ مونده برای تو (پایین‌تر)؛ همون مسیر با تست کل مسیر روی routeهای واقعی رفته شد |
| merge به `main` | ⬜ منتظر تأیید تو (فاز ۱ و ۲ هم هنوز merge نشدن؛ این برنچ از سر فاز ۲ ساخته شده) |

---

## ردیف‌های بخش ۳

| # | خطر | دفاع | تست‌ها |
|---|---|---|---|
| 7 | ارسال سفارش بدهکار | `Order.ship` با `balanceDue > 0` ← `422 BALANCE_DUE` (با `meta.balanceDue`)؛ گروه رزرو با عضو بدهکار ← `GROUP_NOT_READY`. دیوار دوم: `CHECK` ِ `orderqm_no_ship_with_balance` (`V29`) | `OrderChangeRequestTest` › `TheBalanceDue` › `it doesn't ship while something is owed - 422 BALANCE_DUE with the amount`؛ `WebV1ChangeResolutionOnDatabaseTest` › `a dearer color - … it doesn't ship until paid, then it does`، `a reservation group with an order owing its balance - GROUP_NOT_READY naming it, none shipped`؛ `BalancePaymentsMigrationTest` › `an order that owes something can't be SHIPPED in the read model`؛ کل مسیر: `WebV1MyOrderJourneyOnDatabaseTest` › `a dearer color after the photos - asked, applied, the balance paid at the gateway, new photos, approved, shipped - …`. (بخش «لغوشده» ِ این ردیف مال فاز ۲ ـه: `PANEL_ORDERS_REVIEW.md`) |
| 8 | فروش بیشتر از موجودی در درخواست تغییر | رنگ تازه با `InventoryDomainService.rebalanceForOrder` رزرو می‌شه: کم بود ← `QUANTITY_EXCEEDS_STOCK` و هیچ چیز ذخیره نمی‌شه؛ ذخیره‌ی موجودی‌ها به ترتیب `(productId, variantId)`؛ نوشتن همزمان روی همون رنگ ← قید یکتای stream ِ موجودی ← تلاش دوباره (تا ۳ بار) با خوندن تازه | `WebV1ChangeResolutionOnDatabaseTest` › `the last bag of a color - a change to it and a purchase of it at once, 20 times - exactly one of them gets it, never sold twice` (**تازه**)، `A swaps Nila for Mahak while B swaps Mahak for Nila, at once, 20 times - never a deadlock, each holds its new bag` (**تازه**)، `a color with none left - 422 QUANTITY_EXCEEDS_STOCK with what is left, and nothing changed, the old hold still there`؛ `InventoryDomainServiceTest` › `a swapped color - the old one's hold goes back, the new one is held, in lock order`، `a new color with too few left is QUANTITY_EXCEEDS_STOCK with what it has, and nothing was saved` |
| 21 | پرداخت مانده دو بار | همون دیوارهای پرداخت: `IdempotencyGuard` روی `POST …/payments`، قفل همه‌ی تلاش‌های سفارش (`lockAttemptsOf`)، «یه تأیید در هر لحظه» (`payment_one_verifying_per_order_idx`)، و تأیید `BALANCE` فقط وقتی `balanceDue` هنوز **دقیقاً** مبلغ تلاشه (`PaymentDomainService.waitsFor`)؛ `Order.payBalance` با مبلغ دیگه ← `AmountMismatch` دیوار دومه | `WebV1BalancePaymentsOnDatabaseTest` › `two attempts of one balance both paid at the bank, called back at the same moment, 10 times - paid once, never twice`، `staff change the order again after the attempt started - the old attempt is never confirmed, its money never taken`، `«پرداخت» of a balance sent twice with one Idempotency-Key - the same attempt both times, one BALANCE attempt stored` (**تازه**)؛ `PaymentDomainServiceTest` › `TheBalance` › `the balance changed since the attempt started (staff changed it again) - never confirmed, its money never taken`؛ `WebV1ChangeResolutionOnDatabaseTest` › `resolution and the customer starting to pay the balance at the same moment, 5 times - …` |
| 15 | داده‌ی شخصی در لاگ (همه‌ی فازها) | `Cmd` ِ درخواست و رسیدگی `toString` ِ بدون `note` دارن؛ لاگ تغییرات `ORDER_CHANGE_RESOLVED` فقط تصمیم، نوع و رنگ | `PanelOrdersLogScanOnDatabaseTest` › `a change asked, turned down, asked again, carried out and its balance paid leave nothing personal in the log` (**تازه**)؛ `WebV1ChangeResolutionOnDatabaseTest` › `turned down - …, one change-log row` (متن یادداشت تو ردیف لاگ تغییرات نیست) |

---

## خرابکاری عمدی

| نگهبان | چی برداشته شد | تست(هایی) که قرمز شد |
|---|---|---|
| چک مانده قبل از ارسال | `if (balanceDue > Money.ZERO) …` از `Order.ship` | `OrderChangeRequestTest` › `it doesn't ship while something is owed - …`؛ `a dearer color - … it doesn't ship until paid, then it does`؛ `a reservation group with an order owing its balance - …`. دیوار دوم هم دیده شد: ارسال به `CHECK` ِ `orderqm_no_ship_with_balance` خورد |
| شرط تأیید پرداخت مانده | `order.balanceDue == payment.amount` از `PaymentDomainService.waitsFor` | `PaymentDomainServiceTest` › `the balance changed since the attempt started …`؛ `WebV1BalancePaymentsOnDatabaseTest` › `staff change the order again after the attempt started - …`. بدون این چک `payBalance` هنوز با `AmountMismatch` رد می‌کرد، ولی پول اول گرفته و بعد برگشت داده می‌شد |
| ترتیب قفل در عوض کردن رنگ | مرتب‌سازی `(productId, variantId)` از `rebalanceForOrder` | `InventoryDomainServiceTest` › `a swapped color - … in lock order`؛ `A swaps Nila for Mahak while B swaps Mahak for Nila, at once, 20 times - never a deadlock, …` (با `deadlock detected` ِ Postgres) |
| چک موجودی رنگ تازه | `MoreThanAvailable` در `rebalanceForOrder` نادیده گرفته شد | `InventoryDomainServiceTest` › `a new color with too few left is QUANTITY_EXCEEDS_STOCK …`، `a color never stocked is out of stock with nothing left`؛ `a color with none left - 422 QUANTITY_EXCEEDS_STOCK …`؛ `the last bag of a color - … never sold twice` |
| گرد کردن سهم کد | `floor` ِ `codeShareOf` به `ceil` (سهم پرداخت‌شده رو به پایین) | `ChangeRequestPolicyTest` › `the code's share is rounded down, so what comes back is rounded up and never more than the line cost` |
| یه بار برای هر کلید (ردیف ۲۱) | `IdempotencyGuard.once` از `StartPaymentAttemptUc` | `«پرداخت» of a balance sent twice with one Idempotency-Key - …` |

---

## چیزهایی که موقع کار پیدا شد

- **یه assertion ِ گام ۳.۴ هیچ‌وقت قرمز نمی‌شد.** `assertEquals(200_000L, … meta?.balanceDue … ?: 200_000L)`: اگه `meta` نبود، پیش‌فرض خودش رو با خودش مقایسه می‌کرد. حالا مستقیم `meta.balanceDue` رو می‌خونه (و `meta` واقعاً هست).
- **گرد کردن سهم کد به نفع مشتریه، نه برعکس.** تصمیم ۶ می‌گه «گرد کردن رو به پایین یعنی هیچ‌وقت بیشتر از پرداخت‌شده برنمی‌گرده»، ولی `floor` روی **سهم کد** ـه، پس سهم پرداخت‌شده‌ی قلم حذف‌شده تا کمتر از ۱ تومان **بیشتر** از سهم دقیقش می‌شه (همون چیزی که اسم تست طلایی هم می‌گه: «what comes back is rounded up»). چیزی که واقعاً نمی‌ذاره بیشتر از پرداخت‌شده برگرده، سقف `refundable` در `ResolveChangeRequestUc.refundOf` و در لغوه (جمع استردادها ≤ پرداخت). عدد درسته و تو کانترکت هم همینه؛ فقط متن تصمیم ۶ و KDoc ِ `ChangeRequestPolicy.paidShareOf` دلیل رو برعکس نوشتن. **سؤال برای تو:** همین بمونه (۱ تومان به نفع مشتری) و فقط متن درست بشه، یا سهم کد رو بالا گرد کنیم (تغییر کانترکت)؟
- **ترتیب قفل استردادها در `ResolveChangeRequestUc` با KDoc ـش نمی‌خونه.** KDoc می‌گه «۶، ۹، استردادها، ۷، ۸»، ولی کد استردادها رو آخر قفل می‌کنه (بعد از ذخیره‌ی موجودی و سفارش). بن‌بست نمی‌سازه، چون هر نویسنده‌ی استرداد (`CreateRefundUc`، `UpdateRefundUc`، لغو) اول قفل ۹ ِ همون سفارش رو می‌گیره و رسیدگی اون رو از اول داره؛ ولی یا متن باید درست بشه یا قفل جابه‌جا.
- **`rebalanceForOrder` موجودی رو بی‌قفل می‌خونه** (مثل `reserveForOrder`)؛ فروش بیشتر از موجودی رو قید یکتای stream ِ موجودی و تلاش دوباره می‌گیره، نه قفل قبل از خوندن. تست همزمانی تازه (۲۰ بار) ثابت کرد همیشه دقیقاً یکی آخرین کیف رو می‌گیره. زیر فشار خیلی زیاد، بعد از ۳ تلاش، کلاینت `409 CHANGED_BY_SOMEONE_ELSE` می‌گیره.
- **ردیف‌های ۷، ۸ و ۲۱ دو تست همزمانی کم داشتن:** «تغییر به آخرین کیف یه رنگ همزمان با خرید همون» و «دو تعویض ضربدری همزمان». هر دو اضافه شدن و هر دو با خرابکاری قرمز شدن.
- **کل مجموعه برای گام ۳.۴ دوباره اجرا نشده بود** (یادداشت گام). اینجا اجرا شد و جز تست‌های تازه‌ی همین گام چیزی قرمز نبود.
- **محیط این ماشین:** Postgres ِ محلی با `kiva`/`kiva` باز نمی‌شه، پس تست‌ها روی یه Postgres ِ موقت (پورت ۵۴۳۳، `KIVA_TEST_DB_URL`) رفتن؛ و Gradle بیرون از sandbox و با `-Djdk.net.unixdomain.tmpdir` ِ کوتاه اجرا شد («Unable to establish loopback connection»).

---

## دور با پنل (مونده برای تو)

اینجا پنل و kiva-fe در دسترس نبودن؛ همین مسیر با `WebV1MyOrderJourneyOnDatabaseTest` › `a dearer color after the photos - …` روی routeهای واقعی و درگاه آزمایشی رفته شد (درخواست، رسیدگی، پرداخت مانده، عکس تازه، تأیید، ارسال؛ کانترکت و هویت جمع‌ها بعد از هر قدم). دور واقعی:

1. پنل: به یه رنگ از نیلا قیمت جدا بده (گرون‌تر از رنگ دیگه).
2. kiva-fe: یه نیلا ِ ارزون‌تر بخر و بپرداز (درگاه آزمایشی). پنل: عکس ← «ارسال شد».
3. kiva-fe: «درخواست تغییر» ← `COLOR` به رنگ گرون‌تر با یه توضیح. سفارش «درخواست تغییر ثبت شد»، دکمه‌های لغو و درخواست خاموش.
4. پنل: تب `CHANGE_REQUESTED` ← سفارش ← «اجرا» با یه یادداشت (پیامک «درخواستت بررسی شد» تو لاگ `LogSmsSender` یا گوشی، بدون یادداشت). `balanceDue` درست؛ دکمه‌ی ارسال `BALANCE_DUE` می‌ده.
5. kiva-fe: «پرداخت» با مبلغ مانده ← درگاه آزمایشی ← صفحه‌ی نتیجه ← صفحه‌ی سفارش: مانده صفر، «مانده‌ی … تومانی پرداخت شد».
6. پنل: عکس تازه ← «ارسال شد»؛ kiva-fe: «همونه!»؛ پنل: ارسال با کد رهگیری.
7. یه بار هم: «درخواست تغییر» به رنگ ارزون‌تر ← اجرا ← استرداد `PENDING` ِ `CHANGE_REQUEST` تو پنل ← `COMPLETED`.
8. الگوی پیامک تازه (`changeResolvedPattern`) رو در پنل فراز بساز و در `KIVA_FARAZ_CHANGE_RESOLVED_PATTERN` بذار (README).
