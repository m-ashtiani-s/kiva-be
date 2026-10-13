# ریویوی خرید: گام ۲۱، حمله‌ی آزمایشی و جمع‌بندی

> برنچ `purchase`، بعد از گام ۲۰ (`82c3d11`)، ۲۱ مهر ۱۴۰۵. پایه: `PURCHASE_ROADMAP.md`، بخش‌های ۳، ۱۱، ۱۹ و ۲۲ (گام ۲۱).
> این فایل جواب سؤال «این قانون چرا اینجاست و از کجا بدونم هنوز کار می‌کنه؟» رو برای خرید می‌ده، مثل `AUTH_REVIEW.md` برای ورود.

---

## خلاصه

**وضعیت کلی: آماده برای وصل شدن فرانت.** هر ردیف جدول بخش ۳ ِ نقشه‌ی راه تست داره، و هر نگهبانی که عمداً خراب شد حداقل یه تست رو قرمز کرد. اسکن لاگ یه خرید کامل هیچ نشتی پیدا نکرد. یه دور واقعی با Chromium (بدون kiva-fe، که اینجا در دسترس نیست) کل جریان رو از سبد مهمان تا صفحه‌ی نتیجه و انقضا رد کرد.

| چی بررسی شد | نتیجه |
|---|---|
| `./gradlew test --rerun-tasks` | ✅ **۱۹۵۵ تست**، همه سبز (common ۲۰۱، domain ۸۸۵، application ۱۳۲، اصلی ۷۳۷). گام ۲۱، ۲ تست اضافه کرد |
| هشدار کامپایلر | ✅ صفر |
| هر ردیف بخش ۳ حداقل یه تست | ✅ ۲۱ از ۲۱ (بخش ۱) |
| خرابکاری عمدی: ۱۰ نگهبان ِ گام ۲۱، هر کدوم جدا | ✅ همه قرمز شدن (بخش ۳) |
| خرابکاری‌های گام ۲۰ (کد تخفیف، ۹ تا) | ✅ همه قرمز شدن؛ یکی اول تستی نداشت که حالا داره (بخش ۳) |
| اسکن لاگ: سبد مهمان، ورود و ادغام، آدرس تازه، ثبت سفارش، درگاه، callback، صفحه‌ی نتیجه و صفحه‌ی سفارش، با لاگ SQL روشن | ✅ هیچ متن آدرس، کد پستی، اسم گیرنده، موبایل کامل، آیدی و یادداشت پیام‌رسان، یادداشت سفارش، پیام هدیه یا توکن سبدی. دو نشت عمدی هر دو گرفته شدن (بخش ۲) |
| دور واقعی با مرورگر | ⚠️ با Chromium روی سرور واقعی و صفحه‌ی درگاه آزمایشی انجام شد، **بدون kiva-fe** (بخش ۴). دور با kiva-fe روی `localhost:3100` برای شما مونده |

تغییرهای این گام:

| فایل | چی |
|---|---|
| `PurchaseLogScanOnDatabaseTest` (جدید، ۲ تست) | اسکن لاگ یه خرید کامل، با مقدارهای انتخاب‌شده که تصادفاً تو لاگ پیدا نمی‌شن، و یه تست که نشون می‌ده اسکن واقعاً پیدا می‌کنه |
| `docs/FRONTEND_PURCHASE.md` (جدید) | کل جریان برای فرانت |
| `README.md` | متغیرهای پرداخت و jobها، درگاه آزمایشی، کوتاه کردن مهلت پرداخت روی دیتابیس محلی |
| `PURCHASE_ROADMAP.md` | گام ۲۱ و گزارش کار |

کد اصلی تو این گام عوض نشد: هیچ نشتی پیدا نشد و هیچ ردیفی بی‌تست نبود.

---

## ۱. جدول بخش ۳: هر خطر و تستی که ازش محافظت می‌کنه

ستون آخر می‌گه با خرابکاری عمدی (بخش ۳ ِ همین فایل) ثابت شده که اون تست واقعاً قرمز می‌شه یا نه.

| # | خطر | دفاع | تست‌هایی که ازش محافظت می‌کنن | خرابکاری |
|---|---|---|---|---|
| 1 | **فروش بیشتر از موجودی** | رزرو موقع ثبت، با قید یکتای event store و اجرای دوباره‌ی تراکنش | `WebV1OrdersOnDatabaseTest` › the last bags - 20 customers buy the last 2 Hasti cream at once, exactly 2 orders are placed and the rest are OUT_OF_STOCK<br>`InventoryDomainServiceTest` › all or nothing - a short color holds nothing, and the failure names every short color, one never stocked too<br>`InventoryTest` › whatever happens in whatever order, available never goes below zero · more than can be sold is QUANTITY_EXCEEDS_STOCK with how many can | — |
| 2 | **بن‌بست** | موجودی به ترتیب `(productId, variantId)` قفل می‌شه؛ ردیف محصول قبل از stream | `InventoryDomainServiceTest` › every line is held, and the stocks come back in lock order, by product and then color, whatever the order's own order<br>`WebV1OrdersOnDatabaseTest` › no deadlock - orders of A and B and of B and A, and of two colors of one bag, at once, 50 times<br>`PurchaseJobsOnDatabaseTest` › run again, or twice at once, the expiry changes nothing twice - one OrderExpired, the stock given back once | ✅ |
| 3 | **دو بار پول گرفتن** | verify فقط برای سفارش پرداخت‌نشده و وقتی تلاش دیگه‌ای در حال verify نیست، روی تلاش‌های قفل‌شده؛ ایندکس‌های «یک VERIFYING» و «یک موفق» | `PaymentDomainServiceTest` › two attempts both paid - the second, back after the first was confirmed, is refused and never confirmed · two attempts both paid at once - while the first is being confirmed, the second waits and changes nothing<br>`WebV1PaymentsOnDatabaseTest` › two attempts both paid - only the first is confirmed, … · two attempts both paid, back at the same moment, 10 times - never confirmed twice<br>`WebV1PaymentRetriesOnDatabaseTest` › an old attempt paid after a new one was made still pays the order, and the new one then isn't confirmed<br>`PaymentRepositoryImplTest` › an order is paid once - a second confirmed attempt of the same order is refused · an order is confirmed one attempt at a time - … | ✅ |
| 4 | **پول گرفتیم، کیف آزاد شد** | job سفارشی با تلاش در جریان رو منقضی نمی‌کنه؛ callback برای سفارش منقضی verify نمی‌کنه | `PaymentDomainServiceTest` › no expiry before the time to pay ends, of an order paid or expired, nor while an attempt may still pay it · paid, but the order expired meanwhile - refused as ORDER_NOT_PAYABLE, never confirmed<br>`PurchaseJobsOnDatabaseTest` › an attempt on the bank's page less than 20 minutes, or one being confirmed, keeps the order - until it ends<br>`WebV1PaymentsOnDatabaseTest` › paid after the order expired - never confirmed, the attempt fails as ORDER_NOT_PAYABLE, and the page says the money comes back<br>`OrderRepositoryImplTest` › a payment and an expiry of one order at the same moment end with one of them, never a deadlock | ✅ |
| 5 | **جعل callback** | پرداخت از Authority ِ ذخیره‌شده پیدا می‌شه؛ verify سرور به سرور با مبلغ خودمون | `PaymentRepositoryImplTest` › a callback finds its attempt by the gateway and the authority, and nothing by another gateway's · one gateway session is one attempt - a second attempt with the same authority is refused · one confirmation pays one attempt - the same reference number twice is refused<br>`WebV1PaymentsOnDatabaseTest` › nothing is found for an unknown session, another gateway, or parameters that aren't a gateway's - no payment changes, no paymentId · OK without paying - the gateway refuses to confirm, the attempt fails and nothing is paid<br>`ZarinpalGatewayTest` › confirming sends the amount we stored, in rials, … · another amount paid is AmountMismatch and an ERROR, a payment that failed is Declined<br>`PaymentValueObjectsTest` › a callback's authority is taken only when it can be one, so nothing is looked up for anything else | ✅ |
| 6 | **دستکاری مبلغ** | سرور همه‌چیز رو حساب می‌کنه؛ `expectedPayable` فقط مقایسه | `OrderDomainServiceTest` › the payable the customer saw must be the payable now - else 409 PRICE_CHANGED with it, and no order id is used<br>`WebV1OrdersOnDatabaseTest` › a payable the shopper didn't see is 409 PRICE_CHANGED with the payable now, and nothing is saved, not even the key<br>`OrderTest` › the lines' amounts come from the same calculation as the cart's<br>`WebV1DiscountCodesOnDatabaseTest` › a code that stopped applying between the cart and the order - 409 PRICE_CHANGED with the payable without it, then placed without it | ✅ |
| 7 | **سفارش دوباره** | `Idempotency-Key` با قفل advisory در همون تراکنش؛ سبد بعد از ثبت خالی | `WebV1OrdersOnDatabaseTest` › a double click - two requests at once with one key place one order, and both answers have its code and its payment · two tabs - two orders at once from one cart, each with its own key, place one order and the other finds the cart empty<br>`IdempotencyGuardOnDatabaseTest` › ten requests with one key at the same moment - the work is done once and all ten get its outcome<br>`WebV1PaymentRetriesOnDatabaseTest` › the Idempotency-Key works as for placing an order - the same request gives the same attempt, another body 409, none 400<br>`RequestFingerprintTest` › any other value is another request | ✅ |
| 8 | **قفل کردن موجودی** | حداکثر ۳ سفارش پرداخت‌نشده‌ی در جریان، و ۱۰ تلاش برای هر سفارش | `PaymentPolicyTest` › two unpaid orders holding stock are fine, the third stops the next until the earliest ends · the eleventh attempt is refused until the order's time to pay ends<br>`WebV1OrdersOnDatabaseTest` › a fourth unpaid order is 429 RATE_LIMITED, with how long until the first one stops holding its stock<br>`WebV1PaymentRetriesOnDatabaseTest` › 15 retries of one order at once, 10 times - exactly 10 attempts, the rest 429<br>`PurchaseJobsOnDatabaseTest` › 15 minutes and a second later, an order with no payment going on expires - its stock comes back and its attempts expire | — |
| 9 | **دسترسی به داده‌ی دیگران (IDOR)** | `userId` فقط از توکن؛ منبع کاربر دیگه 404؛ تست نگهبان routeها | `RouteGuardsTest` › every endpoint the contract says needs a login refuses a call without a token<br>`WebV1PaymentsOnDatabaseTest` › the result page is the customer's own - someone else's, a made-up and a malformed id are 404, and no login is 401<br>`WebV1MyOrdersOnDatabaseTest` › someone else's order, one that doesn't exist and a code that can't be one are all 404 ORDER_NOT_FOUND<br>`WebV1PaymentRetriesOnDatabaseTest` › paying from the order's page by its code works the same, and only for the customer's own order<br>`WebV1AddressesOnDatabaseTest` › someone else's address is ADDRESS_NOT_FOUND for all four operations, and nothing changes<br>`WebV1CheckoutOnDatabaseTest` › someone else's address, one that is gone and one that is no number are all a 400 on addressId | ✅ |
| 10 | **دزدی توکن سبد** | ۲۵۶ بیت تصادفی، فقط hash در دیتابیس، فقط یه سبد بی‌نام | `CartValueObjectsTest` › a token is gct_ and 43 base64url characters, the 32 random bytes · its hash is the 32-byte SHA-256 of the whole token<br>`CartDomainServiceTest` › a guest's new cart keeps only the hash of its token<br>`CartRepositoryImplTest` › a guest cart is found by its token's hash only, and only the hash is stored<br>`CartTest` › printing a guest cart shows nothing of its token<br>`WebV1CartOnDatabaseTest` › a customer gets their own cart and the guest token is ignored, an expired login is 401 and never a guest | — |
| 11 | **سوءاستفاده از کد تخفیف** | ردیف کد `FOR UPDATE` موقع ثبت؛ ظرفیت = تأییدشده + نگه‌داشته‌شده؛ «خرید اول» با سفارش پرداخت‌شده | `WebV1DiscountCodesOnDatabaseTest` › usageLimit 1 and two orders at the same moment - one gets the code, the other 409 PRICE_CHANGED without it · an order that expires gives its use back - the code can be used again · WELCOME - a guest must log in, a customer who paid before is refused, …<br>`DiscountDomainServiceTest` › held and confirmed uses count against both limits · a guest and a code that counts its customers - LOGIN_REQUIRED, before its limits | ✅ |
| 12 | **خطای گرد کردن پول** | فقط `Long`؛ ریاضی صحیح؛ تست‌های طلایی | `NoFloatingPointMoneyTest` › no Double or Float in the order, settings, payment and discount code<br>`OrderPricingTest` (طلایی‌ها) › Aida caramel and KV-1015 brown by post - 4,080,000 less 600,000 is 3,480,000, and post is free · the first cart with KIVA10 by post - …<br>`DiscountValueObjectsTest` › 10 percent to the nearest thousand, a half up<br>`WebV1DiscountCodesOnDatabaseTest` › rounding to the nearest thousand, a half up - 145,500 is 146,000 and 145,499 is 145,000 | ✅ |
| 13 | **کند شدن یا قطعی درگاه** | هیچ تراکنشی موقع تماس باز نیست؛ timeout ۵ و ۱۵ ثانیه؛ `PAYMENT_GATEWAY_UNAVAILABLE` با `meta.orderCode` | `ZarinpalGatewayTest` › it waits 5 seconds to connect and 15 for the whole call · no answer in time, a server error, an answer that isn't Zarinpal's or -52 are Unreachable<br>`WebV1OrdersOnDatabaseTest` › the gateway doesn't answer after the order is placed - 422 with the order and its attempt, the stock stays held, and the same key gives the same 422<br>`WebV1PaymentRetriesOnDatabaseTest` › the gateway doesn't answer the retry - 422 with the order and the new attempt, to go on from<br>`WebV1PaymentsOnDatabaseTest` › the gateway doesn't answer the confirmation - the attempt stays VERIFYING, the shopper still goes to the result page, which says PENDING | ✅ |
| 14 | **پر شدن connection pool** | تست معماری: درگاه آزمایشی داخل تراکنش خطا می‌ده | `FakePaymentGatewayTest` › a call inside a database transaction fails, so it can't be put there by mistake<br>و هر تستی که درگاه آزمایشی رو صدا می‌زنه (همه‌ی `WebV1OrdersOnDatabaseTest`، `WebV1PaymentsOnDatabaseTest`، …) | ✅ |
| 15 | **لو رفتن اطلاعات شخصی تو لاگ** | فقط شناسه و کد؛ موبایل ماسک؛ `toString` ِ ستاره‌دار؛ لاگ SQL بدون مقدار | **`PurchaseLogScanOnDatabaseTest`** (جدید) › a guest's cart, the login that merges it, a new address, an order, the bank, the callback and the pages leave nothing personal in the log · the scan finds a whole number, an address and a cart token, so a pass means something<br>`OrderTest` › printing an order or its first event shows no address, phone or note<br>`OrderDomainServiceTest` › a placement prints no address, phone or note<br>`PaymentTest` › printing a payment shows only its ids and status<br>`ZarinpalGatewayTest` › the merchant id is in no log line and not in toString<br>`DomainEventSerializerOrderTest` › printing a stored order shows neither the address nor a phone | ✅ |
| 16 | **قیمت یا وضعیت قدیمی** | ثبت سفارش read model رو داخل تراکنش دوباره می‌خونه | `WebV1OrdersOnDatabaseTest` › a color taken out of the shop since it was added is PRODUCT_UNAVAILABLE, naming the cart's line · a payable the shopper didn't see is 409 PRICE_CHANGED …<br>`OrderDomainServiceTest` › the lines - out of the shop first, then not enough left, then over the limit, each naming the cart's lines | ✅ |
| 17 | **callback تکراری** | پرداخت تموم‌شده دیگه عوض نمی‌شه؛ «قبلاً verify شده» هم موفقیته | `WebV1PaymentsOnDatabaseTest` › a callback that comes again - a refresh, or a POST after the GET - changes nothing and sends the shopper to the same place<br>`PaymentDomainServiceTest` › a callback that comes again changes nothing - after it ended, and while it is being confirmed<br>`FakePaymentGatewayTest` › verify confirms only a paid session, with its own amount, and a second time says it was confirmed already | ✅ |
| 18 | **redirect به سایت دیگه** | مقصد ثابت از config؛ فقط `paymentId` ِ چک‌شده اضافه می‌شه | `WebV1PaymentsOnDatabaseTest` › nothing is found for an unknown session, another gateway, or parameters that aren't a gateway's - no payment changes, no paymentId · a callback that breaks inside still sends the shopper to the result page<br>`AppConfigTest` (آدرس‌ها `http`/`https` و بدون `/` آخر) | — |
| 19 | **خطای منطقه‌ی زمانی** | `Asia/Tehran` در یه جا (`ShopCalendar`) | `ShopCalendarTest` › Thursday 23 59 in Tehran is still Thursday · 21 00 UTC on Thursday is already Friday in Tehran, which is the day that counts<br>`OrderPricingTest` › Thursday 23 59 in Tehran - post 2026-10-19 to 2026-10-21, … · 21 00 UTC is Friday in Tehran - it ships Saturday, …<br>`PurchaseJobsOnDatabaseTest` › the purchase's jobs and when they run (۴ صبح تهران) | — |
| 20 | **پرداخت شد ولی callback نرسید** | job ِ تطبیق با inquiry | `PurchaseJobsOnDatabaseTest` › a shopper who paid and closed the tab - the gateway says PAID, the payment is confirmed and the order paid · a confirmation the gateway didn't answer is asked again - SUCCEEDED, … · run twice at once, the reconciliation confirms once and pays once · still at the bank - left alone, and once abandoned it is closed and never asked about again | — |
| 21 | **فهمیدن حجم فروش** | جایگشت روی فضای ۶ رقمی | `OrderCodePolicyTest` › 900,000 ids in a row get 900,000 different six-digit codes, and no two neighbours are next to each other · the first order is the contract's KV-218340 | — |

---

## ۲. اسکن لاگ

`PurchaseLogScanOnDatabaseTest` یه خرید کامل رو با همه‌ی خط‌های لاگ ضبط می‌کنه (appender روی root، و `Exposed` روی DEBUG هر چی `logback.xml` بگه): مهمان دو کیف به سبد اضافه می‌کنه، با `guestCartToken` وارد می‌شه (ادغام)، صفحه‌ی تسویه، ثبت سفارش با آدرس تازه (ذخیره در دفترچه)، پیام‌رسان با شماره و آیدی و یادداشت، یادداشت سفارش و پیام هدیه، درگاه آزمایشی، callback، صفحه‌ی نتیجه، صفحه‌ی سفارش و دفترچه‌ی آدرس.

هیچ خطی نباید این‌ها رو داشته باشه: متن آدرس، کد پستی، اسم گیرنده، موبایل کامل (خریدار، گیرنده، پیام‌رسان؛ با هر فاصله و خط تیره‌ای بین رقم‌ها)، آیدی و یادداشت پیام‌رسان، یادداشت سفارش، پیام هدیه، و توکن سبد. مقدارها طوری انتخاب شدن که تصادفاً تو لاگ نباشن («یاس‌نهم»، «کاظم‌زاده»، `@niloo_kz97`، …).

| | نتیجه |
|---|---|
| اولین اجرا | ✅ هیچ نشتی. لاگ SQL با `?` (گام ۱۱ ِ ورود)، و `toString` ِ `PlaceOrderUc.Cmd`، `OrderPlacement`، `Order`، `OrderQm`، رویدادها و payloadها پیش از این ستاره‌دار بودن |
| خرابکاری: `PlaceOrderUc` شماره‌ی پیام‌رسان رو لاگ کرد | ✅ تست قرمز شد |
| خرابکاری: لاگر پیش‌فرض Exposed (با مقدارها) برگشت | ✅ تست قرمز شد |
| تست دوم | خودِ اسکن رو امتحان می‌کنه: یه شماره با فاصله، یه آدرس و یه توکن رو باید پیدا کنه |

---

## ۳. خرابکاری عمدی

هر نگهبان یه بار جدا خراب شد، تست‌های مربوط (و همه‌ی تست‌های domain و application) اجرا شدن، و کد برگشت. هیچ‌کدوم commit نشده.

### ده نگهبان ِ گام ۲۱

| نگهبان | خرابکاری | تستی که قرمز شد |
|---|---|---|
| مرتب‌سازی قبل از رزرو | `sortedWith(compareBy(productId, variantId))` از `InventoryDomainService.reserveForOrder` حذف شد | `InventoryDomainServiceTest` › every line is held, and the stocks come back in lock order, … · `WebV1OrdersOnDatabaseTest` › no deadlock - orders of A and B and of B and A, … 50 times |
| `FOR UPDATE` ِ سبد | `.forUpdate()` از `CartRepositoryImpl.lockAndRead` حذف شد | `WebV1OrdersOnDatabaseTest` › two tabs - two orders at once from one cart, … · `WebV1CartMergeOnDatabaseTest` › a guest cart merged by two customers at once goes into one cart only · `WebV1CartOnDatabaseTest` › 20 adds at once on one cart - each product ends at its limit exactly, one line per color |
| verify فقط برای سفارش «در انتظار پرداخت» | چک `order.isAwaitingPayment` از `PaymentDomainService.beginConfirming` حذف شد | `PaymentDomainServiceTest` › two attempts both paid - the second, … · paid, but the order expired meanwhile - … · `WebV1PaymentsOnDatabaseTest` › two attempts both paid - only the first is confirmed, … · paid after the order expired - never confirmed, … · `WebV1PaymentRetriesOnDatabaseTest` › an old attempt paid after a new one was made still pays the order, … |
| پیدا کردن پرداخت با authority | `findByAuthority` آخرین تلاش `REDIRECTED` ِ همون درگاه رو برگردوند | `PaymentRepositoryImplTest` › a callback finds its attempt by the gateway and the authority, … · `WebV1PaymentsOnDatabaseTest` › nothing is found for an unknown session, … · a callback that comes again - … · two attempts both paid - … · two attempts both paid, back at the same moment, 10 times - … |
| قفل idempotency | `pg_advisory_xact_lock` جاش رو به یه `SELECT` ِ بی‌قفل داد | `WebV1OrdersOnDatabaseTest` › a double click - two requests at once with one key place one order, … · `IdempotencyGuardOnDatabaseTest` › ten requests with one key at the same moment - the work is done once and all ten get its outcome |
| تماس درگاه بیرون از تراکنش | `requestPayment` ِ `StartPaymentUc` داخل `inTransaction` رفت | ۱۰ تست `WebV1OrdersOnDatabaseTest` و ۱۳ تست `WebV1PaymentsOnDatabaseTest` (درگاه آزمایشی داخل تراکنش خطا می‌ده)، از جمله › the way it should go - 201, … |
| ایندکس «یک پرداخت موفق» | `UNIQUE` از `payment_one_success_per_order_idx` در `V16` برداشته شد (دیتابیس تست هر بار از migrationها ساخته می‌شه) | `PaymentRepositoryImplTest` › an order is paid once - a second confirmed attempt of the same order is refused |
| چک «پرداخت در جریان» در job | `PaymentInProgress` از `PaymentDomainService.expireUnpaid` حذف شد | `PaymentDomainServiceTest` › no expiry before the time to pay ends, …, nor while an attempt may still pay it · `PurchaseJobsOnDatabaseTest` › an attempt on the bank's page less than 20 minutes, or one being confirmed, keeps the order - until it ends |
| چک مالکیت در `GET /payments` | `takeIf { it.userId == query.userId }` از `GetPaymentResultUc` حذف شد | `WebV1PaymentsOnDatabaseTest` › the result page is the customer's own - someone else's, … are 404, … |
| اعتماد به قیمت فرستاده‌شده | مقایسه‌ی `expectedPayable` از `OrderDomainService.placeOrder` حذف شد | `OrderDomainServiceTest` › the payable the customer saw must be the payable now - … · the rules come one at a time, in the contract's order · `WebV1OrdersOnDatabaseTest` › a payable the shopper didn't see is 409 PRICE_CHANGED … · دو تست `WebV1DiscountCodesOnDatabaseTest` |

### نُه نگهبان ِ کد تخفیف (گام ۲۰)

| نگهبان | خرابکاری | تستی که قرمز شد |
|---|---|---|
| `FOR UPDATE` ِ ردیف کد | `.forUpdate()` از `lockByCode` حذف شد | ❌ **اول هیچ تستی قرمز نشد.** ✅ حالا: `WebV1DiscountCodesOnDatabaseTest` › usageLimit 1 and two orders at the same moment - … |
| ظرفیت = نگه‌داشته + تأییدشده | فقط `CONFIRMED` شمرده شد | `WebV1DiscountCodesOnDatabaseTest` › usageLimit 1 and two orders at the same moment - … · an order that expires gives its use back - … |
| چک دوباره بعد از هر تغییر سبد | `discounts.recheck` از `CartCommands.run` حذف شد | `WebV1DiscountCodesOnDatabaseTest` › a code that expires while in the cart is taken off with DISCOUNT_REMOVED, told once · excludeSaleItems - … |
| `RELEASED` با انقضا | `releaseFor` از `ExpireUnpaidOrderUc` حذف شد | `WebV1DiscountCodesOnDatabaseTest` › an order that expires gives its use back - … |
| `CONFIRMED` با پرداخت | `confirmFor` از `PaymentConfirmation` حذف شد | `WebV1DiscountCodesOnDatabaseTest` › KIVA10 on 3,480,000 - … the payment confirms its use |
| گرد کردن نیمه به بالا | `+ 50_000` به `+ 0` (گرد کردن رو به پایین) | `DiscountValueObjectsTest` › 10 percent to the nearest thousand, a half up · `WebV1DiscountCodesOnDatabaseTest` › rounding to the nearest thousand, … |
| `excludeSaleItems` | چکش از `DiscountCode.covers` حذف شد | سه تست `DiscountDomainServiceTest`/`DiscountValueObjectsTest` و `WebV1DiscountCodesOnDatabaseTest` › excludeSaleItems - … |
| ارقام فارسی ِ کد | `normalizeDigits()` حذف شد | `DiscountValueObjectsTest` › kiva10 with a space and KIVA with Persian digits are both KIVA10 · `WebV1DiscountCodesOnDatabaseTest` › kiva10 with a space and KIVA with Persian digits are KIVA10, … |
| «فقط خرید اول» | چکش از `DiscountDomainService.check` حذف شد | دو تست `DiscountDomainServiceTest` و `WebV1DiscountCodesOnDatabaseTest` › WELCOME - … |

### ❌ → ✅ قفل کد تخفیف اول تست واقعی نداشت

نسخه‌ی اول تست همزمانی، دو سفارش با **یه کیف** ثبت می‌کرد. بدون قفل ردیف کد، هر دو ظرفیت رو صفر می‌دیدن؛ ولی بعدش هر دو می‌خواستن موجودی همون رنگ رو رزرو کنن، دومی پشت قفل ردیف محصول (قفل ۷) می‌موند، موقع نوشتن رویداد موجودی به `ConcurrentChangeException` می‌خورد، و کل تراکنشش از اول اجرا می‌شد: این بار ظرفیت رو یک می‌دید و کد رو نمی‌گرفت. یعنی تست با قفل موجودی سبز می‌شد، نه با قفل کد. حالا دو مشتری دو کیف از دو محصول می‌خرن، پس فقط قفل کد بینشون هست؛ و `DiscountRedemptionRepository` ِ تست شمارش رو ۱٫۵ ثانیه کند می‌کنه تا دو تراکنش واقعاً همزمان بخونن.

---

## ۴. دور واقعی با مرورگر

**بدون kiva-fe.** این محیط ریپوی kiva-fe رو نداره. پس سرور واقعی (`./gradlew run`، دیتابیس محلی تازه‌ی `kiva` با هر ۲۰ migration، `KIVA_SEED_ENABLED=true`، درگاه `fake`، jobها روشن) با یه اسکریپت Playwright اجرا شد: API همون‌طور که فرانت صدا می‌زنه، و **Chromium** روی صفحه‌ی درگاه آزمایشی. صفحه‌ی نتیجه‌ی فرانت (`localhost:3100/checkout/result`) با یه صفحه‌ی ثابت جایگزین شد تا redirect دیده بشه.

| قدم | نتیجه |
|---|---|
| مهمان دو کیف اضافه می‌کنه (نیلا زیتونی و ترمه یاسی) | `200`، `X-Cart-Token` ِ `gct_…`، دو ردیف، `payable` ۲٬۱۱۵٬۰۰۰ |
| ورود با `guestCartToken` | `isNewUser = true`، سبد کاربر با همون دو ردیف (ادغام) |
| `GET /checkout` | `200`، دفترچه‌ی خالی، درگاه‌ها `ZARINPAL*`، `SAMAN`، `MELLAT` |
| `POST /orders` با آدرس تازه و پست | `201`، `PENDING_PAYMENT`، پرداخت ۲٬۱۱۵٬۰۰۰ با redirect ِ `GET` به `/dev/fake-gateway/FAKE-…` |
| Chromium: صفحه‌ی «درگاه پرداخت آزمایشی» با دکمه‌های «پرداخت موفق» و «انصراف» ← «پرداخت موفق» | ناوبری: `POST …/pay` ← `GET /api/v1/payment-callbacks/zarinpal?Authority=…&Status=OK` ← `GET localhost:3100/checkout/result?paymentId=pay_…` |
| صفحه‌ی نتیجه (`GET /payments/{id}`) | `SUCCEEDED`، سفارش `PROCESSING`، با شماره‌ی پیگیری |
| سفارش‌های من | سفارش `PROCESSING` با ۲٬۱۱۵٬۰۰۰. سبد خالی |
| سفارش دوم ← «انصراف» در درگاه | برگشت به صفحه‌ی نتیجه؛ `CANCELLED`، `canRetry = true`، سفارش `PAYMENT_FAILED` |
| `POST /payments/{id}/retry` ← «پرداخت موفق» | `201` با تلاش تازه و همون `expiresAt`؛ بعدش `SUCCEEDED` و سفارش `PROCESSING` |
| سفارش سوم با `paymentPendingMinutes = 5` روی دیتابیس محلی ← «انصراف» و صبر | مهلت ساعت ۰۸:۳۱:۰۷ تموم شد و job ِ `expire-unpaid-orders` ساعت ۰۸:۳۱:۴۱ سفارش رو منقضی کرد و موجودی رو برگردوند. صفحه‌ی سفارش: `EXPIRED` «منقضی شد»، تایم‌لاین «سفارش ثبت شد ← پرداخت ناموفق ← مهلت پرداخت تموم شد» |
| «خرید دوباره» از سفارش منقضی | `200`، «۲ کیف به سبد اضافه شد». بعدش `paymentPendingMinutes` به ۱۵ برگشت |

**مونده برای شما:** همین دور با kiva-fe روی `http://localhost:3100` (صفحه‌های واقعی سبد، تسویه و نتیجه، و polling ِ `PENDING`). راهنمای اجرا تو README (بخش «Buying: payments and jobs») و جریان کامل فرانت تو `docs/FRONTEND_PURCHASE.md` ـه.

---

## ۵. چیزهایی که می‌مونه

### 🟢 یادداشت‌ها

- **دور با kiva-fe** (بخش ۴).
- **چک‌لیست بخش ۲۳ ِ نقشه‌ی راه** (حساب زرین‌پال، متغیرهای سرور، قالب پیامک «پرداخت موفق»، تأیید تنظیمات با SQL، صفحه‌ی قوانین، کدهای تخفیف تولید، محدودیت nginx، بکاپ، هشدارها روی `PAID_BUT_ORDER_NOT_PAYABLE` و بقیه، jobها روشن و seed خاموش) مال فاز انتشاره و هنوز بازه.
- **سؤال‌های باز بخش ۲۶** (سقف بین رنگ‌ها، عدد ۳ سفارش پرداخت‌نشده، پنجره‌ی ۲۰ دقیقه‌ای درگاه، تعطیلات رسمی، هزینه‌ی ارسال ثابت، نگه‌داری سبد مهمان) تصمیم بیزنس لازم دارن.
- **پیامک «۱۲ ساعت تا پایان رزرو»** (اختیاری ِ گام ۱۹) ساخته نشد.
- **ردیف‌های بدون خرابکاری** (۱، ۸، ۱۰، ۱۸ تا ۲۱): تست دارن، ولی تو این دور عمداً خرابشون نکردم. نگهبان‌هاشون یا قید دیتابیس‌ان (۱، که قید event store ِ کاتالوگ هم پوششش می‌ده)، یا سیاست خالص با تست‌های عددی (۸، ۱۹، ۲۱)، یا یه پیکربندی (۱۸).

---

## چطور تکرارش کنی

```bash
# 1) every test, really run (no cache)
./gradlew test --rerun-tasks

# 2) only the log scan
./gradlew :test --tests "*PurchaseLogScanOnDatabaseTest"
```

برای خرابکاری: یکی از ردیف‌های جدول بخش ۳ رو دستی تو کد اعمال کن، `./gradlew test --continue` بزن، ببین همون تست قرمز می‌شه، و کد رو برگردون. برای ایندکس‌ها، migration ِ خودشون رو عوض کن: دیتابیس تست هر بار پاک و از migrationها ساخته می‌شه.
