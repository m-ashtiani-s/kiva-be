# خرید: راهنمای فرانت

> بک‌اند: برنچ `purchase`، گام‌های ۰ تا ۲۱، ۲۱ مهر ۱۴۰۵. مرجع دقیق شکل درخواست‌ها و پاسخ‌ها کانترکت `api/kiva-openapi.yml` (نسخه‌ی 1.6.0) است؛ بخش ۲۱ ِ `PURCHASE_ROADMAP.md` هر تغییری رو که بعد از ۱.۵.۰ اومده فهرست کرده.
> این فایل می‌گه جریان خرید از سبد تا صفحه‌ی نتیجه چطور کار می‌کنه، و کجاها راحت می‌شه اشتباه کرد. ورود و توکن‌ها تو `FRONTEND_AUTH.md` ان.

## خلاصه

- سبد مهمان با هدر `X-Cart-Token` شناخته می‌شه؛ بعد از ورود، با `Authorization` و بدون توکن سبد. ورود سبد مهمان رو ادغام می‌کنه.
- همه‌ی عددها مال سروره: قیمت، جمع، هزینه‌ی ارسال، تخفیف، `payable`. فرانت هیچ مبلغی حساب نمی‌کنه، فقط نشون می‌ده.
- ردیفی که `purchasable = false` داره تو سبد می‌مونه ولی تو پول حساب نمی‌شه، و تا حذف نشه سفارش ثبت نمی‌شه.
- هر چیزی که از آخرین بار عوض شده (قیمت، موجودی، روش ارسال، کد تخفیف) یه بار تو `cart.issues` میاد.
- ثبت سفارش `Idempotency-Key` لازم داره و `expectedPayable` رو باید همیشه بفرستی.
- بعد از ثبت، کاربر به `payment.redirect` می‌ره. درگاه اون رو به صفحه‌ی نتیجه‌ی فرانت (`/checkout/result?paymentId=…`) برمی‌گردونه؛ صفحه با `GET /payments/{paymentId}` نتیجه رو می‌پرسه و تا وقتی `PENDING` ـه، هر ۳ ثانیه تا ۳۰ ثانیه.
- پرداخت ناموفق یا انصراف ← «تلاش دوباره» (`POST /payments/{paymentId}/retry`)، تا پایان مهلت پرداخت سفارش (۱۵ دقیقه). بعدش سفارش `EXPIRED` می‌شه و فقط «خرید دوباره» می‌مونه.

## ۱. سبد

### توکن سبد مهمان

```js
// The first cart request of a guest makes a cart. Its token comes back once, in a header, on whichever cart endpoint made it.
POST /api/v1/cart/items                      { "variantId": 6, "quantity": 1 }
200  X-Cart-Token: gct_… (43 characters after gct_)
     { "cart": { "id": "cart_…", "isGuest": true, "items": [ … ], "totals": { … }, … }, "affectedItemId": "ci_…", "message": "کراس‌بادی نیلا — زیتونی (۱ عدد) به سبد اضافه شد" }
```

- `X-Cart-Token` رو هر بار که تو پاسخ بود ذخیره کن (`localStorage`) و روی **همه‌ی** درخواست‌های سبد مهمان بفرست. توکن ناشناخته یا بدشکل خطا نیست: سرور یه سبد تازه می‌سازه و توکن تازه‌ش رو برمی‌گردونه. پس هدر پاسخ همیشه برنده‌ست.
- توکن فقط hash ـش تو دیتابیسه و راهی برای پس گرفتنش نیست. گم بشه، سبد مهمان گم شده.
- `GET /cart` رو فقط از مرورگر صدا بزن (نه از سرور رندر، نه از ربات)، چون بدون توکن یه سبد خالی تازه می‌سازه.
- بعد از ورود دیگه توکن سبد نفرست: با `Authorization` سبد خود کاربره و هر `X-Cart-Token` نادیده گرفته می‌شه. توکنی که منقضی شده 401 می‌گیره، نه سبد مهمان.

### ادغام بعد از ورود

`guestCartToken` رو تو `POST /auth/otp/verify` بفرست. `cart` ِ پاسخ، سبد کاربر بعد از ادغامه (با `issues`). بعدش توکن مهمان رو پاک کن. اگه ادغام اونجا انجام نشد، `POST /cart/merge` با `{ "guestCartToken": "gct_…" }` همون کاره و تکرارش بی‌خطره.

- رنگ مشترک یه ردیف می‌شه با جمع تعدادها (و اگه از سقف رد بشه `QUANTITY_REDUCED`). رنگی که فقط مهمان داشت با همون `id` میاد، پس اگه صفحه‌ی سبد باز بود، ردیف‌هاش رو گم نمی‌کنه.
- روش ارسال و سوییچ رزرو ِ مهمان برنده‌ان، فقط وقتی سبد مهمان ردیفی داشت. کد تخفیف خود کاربر می‌مونه، وگرنه کد مهمان (دوباره برای کاربر چک می‌شه).

### ردیف‌ها

| فیلد | یعنی |
|---|---|
| `purchasable` | الان می‌شه خریدش. `false` ← ردیف تو سبد هست ولی تو `totals` نیست؛ روش یه برچسب بزن و دکمه‌ی حذف رو برجسته کن |
| `maxQuantity` | بیشترین تعداد این ردیف الان: کمتر از موجودی و از سقف محصول (سقف بین همه‌ی رنگ‌های یه محصول مشترکه). برای `+` همین رو ملاک بذار. `0` یعنی قابل خرید نیست |
| `stock` | مثل صفحه‌ی محصول: عدد دقیق موجودی هیچ‌وقت نمیاد، جز «فقط ۳ عدد باقی مونده» |
| `lineTotal`، `lineCompareAtTotal`، `lineDiscount` | مبلغ‌های ردیف، از سرور |

- `PATCH /cart/items/{itemId}` با `quantity`، `variantId` (رنگ دیگه‌ی همون کیف) یا هر دو. بیشتر از موجودی ← `422 QUANTITY_EXCEEDS_STOCK` با `meta.availableQuantity` (که همون `maxQuantity` ـه، نه موجودی واقعی)؛ بیشتر از سقف ← `QUANTITY_EXCEEDS_LIMIT`.
- `POST /cart/items` زیادی رو رد نمی‌کنه: تا جایی که جا هست اضافه می‌کنه و `QUANTITY_REDUCED` می‌ده. `message` وقتی چیزی اضافه نشد `null` ـه.
- `DELETE /cart/items/{itemId}` در پاسخ `removedItem` (رنگ و تعداد) رو می‌ده، برای دکمه‌ی «برگردون» (یه `POST /cart/items` دیگه).

### `issues`

هر کدوم **یه بار** میاد: همون پاسخی که اون رو گفت، سبد رو «دیده‌شده» ثبت کرد. نوار هشدار رو از همین پاسخ بساز و جایی نگهش ندار که دوباره نشون داده بشه.

| کد | چی شد |
|---|---|
| `PRICE_INCREASED`، `PRICE_DECREASED` | قیمت از آخرین بازدید عوض شده (`previousValue`، `currentValue`) |
| `OUT_OF_STOCK` | تموم شد؛ ردیف می‌مونه با `purchasable = false` |
| `VARIANT_UNAVAILABLE` | کیف یا این رنگ از فروشگاه رفت |
| `QUANTITY_REDUCED` | تعداد کم شد؛ `currentValue = 0` یعنی ردیف حذف شد (`itemId` همون ردیفیه که دیگه نیست) |
| `SHIPPING_METHOD_CHANGED` | روش ارسال دیگه ارائه نمی‌شه؛ پیش‌فرض فروشگاه انتخاب شد |
| `DISCOUNT_REMOVED` | کد تخفیف دیگه معتبر نیست و برداشته شد (پیام اسم کد رو داره) |

`message` ِ هر issue متن فارسی آماده‌ست؛ همون رو نشون بده.

### ارسال، رزرو و کد تخفیف

- `PUT /cart/shipping-method` با `{ "method": "POST" }`. `shippingOptions` همه‌ی روش‌ها رو با هزینه و تاریخ تحویل داره؛ `freeShipping` نوار «فقط … تا ارسال رایگان» (`progressPercent` یه رقم اعشار، و ۱۰۰ فقط وقتی `eligible`). `freeShipping` می‌تونه `null` باشه.
- `PUT /cart/reservation` با `{ "enabled": true }`؛ وقتی ممکن نیست `422 RESERVATION_UNAVAILABLE` با `meta.reason` (همون `reservation.unavailableReason`). اگه کاربر رزرو فعال داره، `consolidation` پره: سفارش به همون آدرس به رزرو می‌پیونده، با ارسال رایگان و فقط روش ارسال گروه.
- `POST /cart/discount-code` با `{ "code": "kiva10" }` (بزرگ و کوچیک و ارقام فارسی فرقی نداره). پاسخ همون `Cart` با `discount` (کد، برچسب، مبلغ) و `totals.codeDiscount`. کد خالی ← `400` روی `code`؛ بقیه `422`، به این ترتیب: `DISCOUNT_CODE_INVALID`، `DISCOUNT_CODE_NOT_STARTED`، `DISCOUNT_CODE_EXPIRED`، `DISCOUNT_LOGIN_REQUIRED` (اینجا کاربر رو به ورود ببر و بعدش کد رو دوباره بفرست)، `DISCOUNT_FIRST_ORDER_ONLY`، `DISCOUNT_USAGE_LIMIT_REACHED`، `DISCOUNT_MIN_SUBTOTAL_NOT_MET` (`meta.minSubtotal`)، `DISCOUNT_NOT_APPLICABLE`. `message` ِ خطا آماده‌ست. کد ردشده کد قبلی سبد رو دست نمی‌زنه. `DELETE /cart/discount-code` کد رو برمی‌داره.

## ۲. تسویه

```js
GET /api/v1/checkout?addressId=42          // login required; without addressId the default address
200 { "cart": { … }, "addresses": [ … ], "selectedAddressId": 42, "selectedMessenger": "TELEGRAM", "messengerPhone": "09121112233",
      "paymentGateways": [ { "code": "ZARINPAL", "available": true, "isDefault": true, … }, … ], "giftWrap": { "available": false, "price": 0 }, … }
```

- `cart` همون `GET /cart` ـه، همگام‌سازی‌شده و با `issues`. اگه issue داشت، قبل از پرداخت نشونش بده.
- سبد بدون هیچ ردیف ← `422 CART_EMPTY` (ردیف غیرقابل خرید هم ردیفه؛ صفحه نشونش می‌ده و ثبت سفارش ردش می‌کنه). `addressId` ِ نامعتبر یا مال کس دیگه ← `400` روی `addressId`.
- `selectedAddressId = null` یعنی دفترچه خالیه: فرم `newAddress` رو باز کن.
- درگاه رو از `paymentGateways` انتخاب کن: فقط `available`ها قابل انتخاب‌ان، و `isDefault` همیشه یکی از اون‌هاست.
- `consolidation` ِ تسویه برای همون آدرس انتخاب‌شده‌ست؛ با عوض شدن آدرس، `GET /checkout?addressId=…` رو دوباره بزن.

## ۳. ثبت سفارش

```js
POST /api/v1/orders
Authorization: Bearer …
Idempotency-Key: 6f1c…                        // a new UUID per press of «پرداخت»
{ "addressId": 42,                             // or "newAddress": { … } and "saveNewAddress": true; exactly one of them
  "preShipmentMessenger": { "channel": "TELEGRAM", "phone": "09121112233", "username": "@sara_m", "saveAsDefault": true },
  "shippingMethod": "POST", "paymentGateway": "ZARINPAL",
  "expectedPayable": 3132000,                  // the payable the button showed
  "customerNote": null, "gift": null, "reserve": null, "acceptTerms": true }

201 { "order": { "code": "KV-218340", "status": "PENDING_PAYMENT", … },
      "payment": { "paymentId": "pay_…", "gateway": "ZARINPAL", "amount": 3132000, "expiresAt": "…",
                   "redirect": { "url": "https://…/StartPay/…", "method": "GET", "fields": {} } } }
```

### `Idempotency-Key`

- برای هر بار زدن «پرداخت» یه UUID تازه بساز و اگه **همون درخواست** رو به‌خاطر قطع شبکه یا timeout دوباره می‌فرستی، همون کلید رو بفرست. جواب تکرار همون سفارش و همون پرداخته (با وضعیت فعلی)، و هیچ‌وقت سفارش دوم ساخته نمی‌شه.
- همون کلید با بدنه‌ی دیگه ← `409 IDEMPOTENCY_KEY_REUSED`. کلید نیامده ← `400`.
- درخواستی که رد شد (۴۰۰، ۴۰۹، ۴۲۲ ِ قبل از ثبت، ۴۲۹) کلید رو مصرف نمی‌کنه، ولی بعد از درست کردن مشکل یه کلید تازه بساز.
- دو تب با یه سبد: یکی ثبت می‌شه و دیگری `422 CART_EMPTY` می‌گیره.

### ترتیب خطاها

اول شکل ورودی، همه با هم (`400 VALIDATION_ERROR` با `errors[]`؛ خطاهای آدرس تازه با پیشوند `newAddress.`). بعد قانون‌ها، یکی‌یکی، به ترتیبی که کانترکت تو `POST /orders` نوشته:

1. `CART_EMPTY`
2. `ADDRESS_NOT_FOUND` (یا برای آدرس تازه: ۴۰۰ روی استان/شهر، `CITY_NOT_IN_PROVINCE`)
3. `MESSENGER_REQUIRED`، `MESSENGER_PHONE_INVALID`
4. `SHIPPING_METHOD_UNAVAILABLE`
5. `PAYMENT_GATEWAY_UNAVAILABLE` **بدون** `meta.orderCode` (سفارشی ساخته نشده؛ درگاه دیگه‌ای انتخاب کن)
6. `TERMS_NOT_ACCEPTED`
7. `RESERVATION_UNAVAILABLE` (`meta.reason`)
8. `429 RATE_LIMITED` با هدر `Retry-After`: کاربر ۳ سفارش پرداخت‌نشده‌ی در جریان داره
9. `PRODUCT_UNAVAILABLE`، `OUT_OF_STOCK`، `QUANTITY_EXCEEDS_LIMIT`، هر کدوم با `meta.itemIds` (ردیف‌های سبد): همون ردیف‌ها رو تو صفحه علامت بزن
10. کد تخفیفی که دیگه معتبر نیست خطای جدا نداره: سفارش بدون اون حساب می‌شه و چون مبلغ عوض شده، قدم بعد جواب می‌ده
11. `409 PRICE_CHANGED` با `meta.payable`: مبلغ تازه رو نشون بده («قیمت تغییر کرد: …»)، سبد رو دوباره بگیر، و با `expectedPayable` ِ تازه و یه کلید تازه دوباره بفرست

هر کدوم رد بشه هیچ چیزی ذخیره نمی‌شه: سبد سر جاشه و موجودی کنار گذاشته نشده.

### بعد از `201`

- `payment = null` یعنی مبلغی برای پرداخت نبود (کد تخفیف کامل): سفارش همین الان پرداخت‌شده‌ست. مستقیم به صفحه‌ی موفقیت سفارش برو.
- وگرنه کاربر رو به درگاه بفرست:

```js
const { url, method, fields } = payment.redirect
if (method === 'GET') {
  window.location.assign(url)
} else {
  // A POST gateway: a form with the fields, submitted by the browser itself.
  const form = Object.assign(document.createElement('form'), { method: 'POST', action: url })
  for (const [name, value] of Object.entries(fields)) {
    form.append(Object.assign(document.createElement('input'), { type: 'hidden', name, value }))
  }
  document.body.append(form)
  form.submit()
}
```

- `422 PAYMENT_GATEWAY_UNAVAILABLE` **با** `meta.orderCode` و `meta.paymentId`: سفارش ساخته شده و موجودی‌ش نگه داشته شده، ولی درگاه جواب نداد. سفارش رو دوباره ثبت نکن؛ با `POST /payments/{meta.paymentId}/retry` (همون درگاه یا درگاه دیگه) ادامه بده.
- `payment.expiresAt` پایان مهلت پرداخته (۱۵ دقیقه از ثبت). با پرداخت دوباره تمدید نمی‌شه؛ اگه شمارش معکوس نشون می‌دی، از همین بشمار.

## ۴. برگشت از درگاه و صفحه‌ی نتیجه

درگاه کاربر رو به `{API}/api/v1/payment-callbacks/{gateway}` برمی‌گردونه و سرور پرداخت رو با درگاه تأیید می‌کنه و همیشه با `302` به صفحه‌ی نتیجه‌ی فرانت می‌فرسته: `/checkout/result?paymentId=pay_…`. اگه پرداختی پیدا نشد (پارامترهای دستکاری‌شده)، بدون `paymentId`. این مسیر مال مرورگره؛ فرانت خودش صداش نمی‌زنه.

```js
GET /api/v1/payments/pay_…                   // login required; only the customer's own payment, else 404
200 { "paymentId": "pay_…", "status": "SUCCEEDED", "amount": 3132000, "gateway": "ZARINPAL", "referenceId": "201843917",
      "cardMask": "6037-99**-****-1234", "paidAt": "…", "failureReason": null, "canRetry": false,
      "order": { "code": "KV-218340", "status": "PROCESSING", "reserved": false, "consolidatedInto": null }, "nextSteps": [ … ] }
```

| `status` | صفحه |
|---|---|
| `SUCCEEDED` | موفقیت: کد سفارش، `referenceId`، `nextSteps`. `order.reserved` یعنی «رزرو ۴ روزه» شروع شد؛ `consolidatedInto` یعنی به سفارش رزروی قبلی پیوست |
| `PENDING` | تأیید با درگاه هنوز در جریانه: هر ۳ ثانیه دوباره بپرس، حداکثر ۳۰ ثانیه. بعدش بنویس «نتیجه رو تو سفارش‌های من ببین»: سرور خودش پرداخت رو با درگاه تموم می‌کنه |
| `CANCELLED`، `FAILED` | `failureReason` آماده‌ی نمایشه. اگه `canRetry` بود دکمه‌ی «تلاش دوباره» |
| `EXPIRED` | مهلت گذشت؛ «خرید دوباره» از صفحه‌ی سفارش |
| `REFUNDED` | پول گرفته شد ولی سفارش دیگه قابل پرداخت نبود و همون لحظه برگشت داده شد (نادر) |

- `paymentId` ِ بدشکل، ساختگی یا مال کس دیگه همه `404 PAYMENT_NOT_FOUND` ـن. بدون ورود `401`: اگه کاربر تو این فاصله خارج شده بود، بعد از ورود دوباره همین صفحه رو بپرس.
- صفحه‌ی نتیجه رو با رفرش امن نگه دار: فقط می‌خونه.

## ۵. تلاش دوباره

```js
POST /api/v1/payments/pay_…/retry           // or POST /me/orders/KV-218340/payments, from the order's page
Idempotency-Key: …                           // its own key, as for placing an order
{ "gateway": "ZARINPAL" }                    // optional; without it the same gateway
201 { "paymentId": "pay_(new)…", "redirect": { … }, "expiresAt": "…" }   // a new attempt: send the shopper to its redirect
```

- ترتیب ردها: `404` ← `ORDER_ALREADY_PAID` ← `PAYMENT_EXPIRED` (مهلت سفارش گذشت) ← `PAYMENT_GATEWAY_UNAVAILABLE` بدون `meta` (درگاه انتخابی در دسترس نیست) ← `429` (بیشتر از ۱۰ تلاش برای یه سفارش، با `Retry-After` تا پایان مهلت). درگاهی که بعد از ساختن تلاش جواب نده ← `422 PAYMENT_GATEWAY_UNAVAILABLE` با `meta.orderCode` و `meta.paymentId` (تلاش تازه)، که می‌شه دوباره ازش ادامه داد.
- `expiresAt` همون مهلت سفارشه و با تلاش دوباره عقب نمی‌ره.

## ۶. سفارش‌های من

- `GET /me/orders?filter=all|current|delivered|cancelled&page=…` (سفارش منقضی تو `cancelled` ـه) و `GET /me/orders/{code}` (جزئیات، آدرس، مبلغ‌ها با `discountCode`، آخرین پرداخت، تایم‌لاین). سفارش کس دیگه یا کد بدشکل `404 ORDER_NOT_FOUND`.
- `actions.canPay` ← دکمه‌ی «پرداخت» که `POST /me/orders/{code}/payments` رو می‌زنه.
- «خرید دوباره»: `POST /me/orders/{code}/reorder`. هر چیزی که هنوز هست به سبد میاد (`addedCount`)، و بقیه با دلیلشون (`ALREADY_AT_MAX` وقتی سبد سقفش رو داره).
- رزرو فعال: `GET /me/reservation` (`200` با شمارش معکوس، یا `204` وقتی رزروی نیست)؛ کارت سفارش رزروی `reservation` و `actions.canAddToReservation` داره.

## ۷. جاهایی که راحت می‌شه اشتباه کرد

- **مبلغ رو خودت حساب نکن.** حتی «جمع قبل از تخفیف» هم تو `totals` هست. اگه عددی که نشون دادی با سرور فرق داشته باشه، `PRICE_CHANGED` می‌گیری.
- **`expectedPayable` رو همیشه بفرست.** بدونش سفارش با مبلغ فعلی سرور ثبت می‌شه، حتی اگه از وقتی کاربر دکمه رو دید عوض شده باشه.
- **کلید idempotency رو بین دو «پرداخت» متفاوت دوباره استفاده نکن**، و برای تکرار یه درخواست قطع‌شده، کلید تازه نساز.
- **`X-Cart-Token` ِ هر پاسخ رو بگیر**، نه فقط ِ اولی: سبد تازه (مثلاً بعد از پاک شدن سبد قدیمی مهمان در job ِ پاک‌سازی) توکن تازه داره.
- **بعد از ادغام توکن سبد مهمان رو پاک کن.** سبد مهمان با ادغام پاک شده و توکنش دیگه به هیچ سبدی نمی‌رسه؛ بعد از خروج، اولین درخواست سبد یه سبد مهمان تازه و توکن تازه می‌ده.
- **`issues` رو جایی نگه ندار**؛ فقط همون پاسخ.
- **callback رو از جاوااسکریپت صدا نزن** و به `302` ِ اون تکیه نکن؛ صفحه‌ی نتیجه فقط `paymentId` رو از query می‌خونه و `GET /payments/{paymentId}` رو می‌پرسه.
