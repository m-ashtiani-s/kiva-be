# سفارش‌ها در پنل: راهنمای فرانت

> بک‌اند: برنچ `panel-orders`، فاز ۲ ِ `COMPLETION_ROADMAP.md` (گام‌های ۲.۰ تا ۲.۱۱)، ۲۶ مهر ۱۴۰۵؛ و برنچ `change-requests`، فاز ۳ (گام‌های ۳.۰ تا ۳.۵، بخش ۷ ِ همین فایل)، ۲۷ مهر ۱۴۰۵. مرجع دقیق شکل درخواست‌ها و پاسخ‌ها کانترکت `api/kiva-openapi.yml` (نسخه‌ی 1.10.0) است؛ بخش‌های ۷.۱۰ و ۸.۶ ِ نقشه‌ی راه هر تغییر کانترکت این دو فاز رو فهرست کردن.
> این فایل می‌گه پنل (kiva-backoffice-fe) صف سفارش‌ها، عکس قبل از ارسال، ارسال، «تحویل شد»، لغو، یادداشت داخلی، استرداد و رسیدگی به درخواست تغییر رو چطور کار می‌کنه، و کجاها راحت می‌شه اشتباه کرد. طرف مشتری (لغو، تأیید عکس، درخواست تغییر، پرداخت مانده، رهگیری) تو `FRONTEND_PURCHASE.md` ـه.

## خلاصه

- همه‌ی endpointهای سفارش با `ADMIN` یا `OPERATOR` کار می‌کنن، جز **ثبت و پیگیری استرداد** که فقط `ADMIN` ـه. نقش ناکافی ← `403`، بدون توکن ← `401`. کد سفارش ناموجود یا بدشکل ← `404 ORDER_NOT_FOUND`.
- هر نوشتن (جز لغو خود مشتری) جواب رو به شکل کامل `AdminOrder` ِ تازه برمی‌گردونه (عکس پیش‌نویس: `PreShipmentMedia`؛ یادداشت: `AdminNote`؛ استرداد: `Refund`). بعد از هر کار صفحه رو با همین جواب به‌روز کن، دوباره نپرس.
- **دکمه‌ی وضعیت رو فقط از `allowedTransitions` بساز.** `POST …/status` فقط `CANCELLED` (قبل از ارسال) و `DELIVERED` (بعد از ارسال) رو می‌گیره؛ عکس با `…/pre-shipment-media/send` و ارسال با `PUT …/shipment` میان.
- هر نوشتن یه ردیف تو لاگ تغییرات می‌ذاره. متن یادداشت و شماره‌ی پیگیری بانکی تو لاگ نمیاد.
- دو نفر همزمان روی یه سفارش: سرور خودش یکی‌یکی می‌کنه (تا ۳ بار دوباره)؛ اگه باز هم نشد ← `409 CHANGED_BY_SOMEONE_ELSE`. صفحه رو دوباره بخون.

## ۱. صف و صفحه‌ی سفارش

`GET /admin/orders?status=PROCESSING,PHOTO_SENT&q=&shippingMethod=&reserved=&awaitingPhotos=&refundStatus=&from=&to=&sort=newest|oldest&page=&size=`

- `status` با کاما؛ مقدار ناشناخته ← `400`. `q`: کد سفارش (`KV-218340` یا فقط رقم)، موبایل (هر شکلی)، یا واژه (اسم، شهر). `from`/`to` روز تهران‌ان و شامل.
- `statusCounts` همیشه هر ۱۳ وضعیت رو داره (صفر هم)، با فیلترهای دیگه ولی بدون `status`: برای عددهای تب‌ها.
- ردیف: `customerName` (اسم حساب، وگرنه اسم گیرنده)، `mediaCount` (آخرین دسته‌ی فرستاده‌شده، وگرنه پیش‌نویس)، `shipmentGroupCode` (`SG-…` برای سفارش گروه رزرو)، `reservationExpiresAt` (فقط وقتی گروه هنوز نگه می‌داره)، `trackingCode`.

`GET /admin/orders/{code}`: همه‌ی صفحه‌ی مشتری + `customer`، `shipmentGroup` (`orderCodes` ِ لغونشده‌ها به ترتیب پرداخت)، `payments` (**همه‌ی** تلاش‌ها)، `refunds`، `notes` (با اسم نویسنده)، `draftItems` (پیش‌نویس عکس، که مشتری نمی‌بینه) و `allowedTransitions`.

## ۲. عکس قبل از ارسال

```js
PUT  /admin/orders/KV-218340/pre-shipment-media   { "items": [{ "mediaId": "med_…", "orderItemId": 1, "view": "FRONT" }, …] }
200  PreShipmentMedia  (پیش‌نویس در items)
POST /admin/orders/KV-218340/pre-shipment-media/send   { "channelOverride": "TELEGRAM", "message": "…" }   (بدنه اختیاری)
200  AdminOrder  (PHOTO_SENT)
```

- اول عکس رو با `POST /admin/media` و `purpose = PRE_SHIPMENT` آپلود کن (عکس یا ویدیو).
- `PUT` پیش‌نویس رو **کامل جایگزین** می‌کنه؛ ۱ تا ۲۰ مورد، فقط در `PROCESSING`. مشتری پیش‌نویس رو نمی‌بینه.
- سفارش بیشتر از یه ردیف: هر ردیف حداقل یه عکس با `orderItemId` ِ خودش، وگرنه `send` ← `422 PHOTOS_INCOMPLETE` با `meta.missingItemIds`. برای سفارش تک‌ردیفه `orderItemId` لازم نیست.
- **ارسال همیشه دستیه:** اپراتور عکس‌ها رو خودش تو پیام‌رسان مشتری می‌فرسته (`preShipmentMediaDetail.channel`، `phone`، `username`) و بعد `send` رو می‌زنه. `manual: false` ← `400 MANUAL_ONLY`. `message` فقط ثبت می‌شه.
- بعد از `send` مشتری پیامک «عکس کیفت فرستاده شد» می‌گیره و **۲۴ ساعت** برای «همونه!» وقت داره (`feedbackDeadline`). جواب نده ← job هر ۱۰ دقیقه خودکار تأیید می‌کنه (تایم‌لاین: «خودکار تأیید شد»).

## ۳. ارسال

```js
PUT /admin/orders/KV-218340/shipment   { "carrier": "POST", "trackingCode": "183920674512", "shippedAt": "2026-10-18T09:30:00Z", "showInDailyList": true }
200 AdminOrder  (SHIPPED)
```

- فقط از `PHOTO_SENT`، و فقط وقتی مشتری تأیید کرده یا مهلتش گذشته؛ وگرنه `422 AWAITING_CUSTOMER_FEEDBACK` با `meta.feedbackDeadline`: دکمه رو تا اون زمان غیرفعال کن و زمان رو نشون بده.
- سفارشی که بعد از درخواست تغییر مانده داره (`totals.balanceDue > 0`) ارسال نمی‌شه: `422 BALANCE_DUE` با `meta.balanceDue`. دکمه رو غیرفعال کن و بنویس «مشتری هنوز … تومان مانده رو نپرداخته» (بخش ۷).
- `carrier` باید **همون روش ارسال سفارش** باشه (`400 CARRIER_MISMATCH`)؛ مقدار رو از `shippingMethod.code` ِ سفارش پر کن و قفلش کن. ناشناخته ← `400 CARRIER_INVALID`.
- `trackingCode` ۶ تا ۴۰ نویسه از `[0-9A-Za-z-]`، با حروف بزرگ ذخیره می‌شه (`400 TRACKING_CODE_INVALID`/`REQUIRED`).
- `shippedAt` اختیاری (پیش‌فرض الان)؛ نه آینده، نه بیشتر از ۷ روز پیش، نه قبل از ارسال عکس (`400 SHIPPED_AT_INVALID`).
- **گروه رزرو:** ارسال هر سفارش گروه، **همه‌ی** سفارش‌های لغونشده‌ی ارسال‌نشده‌ی گروه رو با همون کد می‌فرسته. یکی آماده نیست ← `422 GROUP_NOT_READY` با `meta.orderCodes` (اون‌هایی که آماده نیستن) و هیچ‌کدوم ارسال نمی‌شن. گروهی که هنوز نگه می‌داره ← `422 INVALID_STATUS_TRANSITION`. لغوشده‌ها کنار گذاشته می‌شن. یه پیامک «ارسال شد» برای کل بسته.
- **اصلاح:** همون `PUT` روی سفارش `SHIPPED` فقط کد، `shippedAt` و `showInDailyList` رو عوض می‌کنه (برای گروه، همه‌ی سفارش‌های همون کد)، بدون پیامک و بدون موجودی. روی `DELIVERED` ← `422`.
- `trackingUrl` رو سرور از الگوی روش ارسال می‌سازه؛ خودت نساز.

## ۴. «تحویل شد» و لغو

```js
POST /admin/orders/KV-218340/status   { "status": "DELIVERED", "note": "به نگهبان تحویل شد", "notifyCustomer": true }
POST /admin/orders/KV-218340/status   { "status": "CANCELLED", "note": "مشتری زنگ زد", "notifyCustomer": false }
```

- `note` (≤ ۵۰۰) **به مشتری نشون داده می‌شه** (تو تایم‌لاینش). کنار فیلد بنویس که مشتری می‌بینه؛ چیز داخلی رو تو یادداشت داخلی بنویس.
- `notifyCustomer` پیش‌فرض `true`: پیامک «تحویل شد» یا «لغو شد».
- «تحویل شد» مهلت مرجوعی رو **همون لحظه** ثابت می‌کنه (`returnableUntil` = الان + `returnWindowDays` ِ تنظیمات). تغییر بعدی تنظیمات سفارش‌های قبلی رو جابه‌جا نمی‌کنه.
- لغو تا قبل از `SHIPPED` مجازه (بعدش `422` با `meta.allowed = ["DELIVERED"]`). لغو همه‌چیز رو برمی‌گردونه: موجودی، فروش محصول، استفاده‌ی کد تخفیف، و اگه پولی پرداخت شده **یه استرداد `PENDING` با `BANK_TRANSFER`** می‌سازه (تو `refunds` ِ جواب). پرداختی که همین الان در حال تأییده ← `422 PAYMENT_IN_PROGRESS`: چند ثانیه بعد دوباره.

## ۵. یادداشت داخلی

`POST /admin/orders/{code}/notes { "text": "…" }` ← `201 AdminNote`. ۱ تا ۱۰۰۰ نویسه بعد از trim (`NOTE_REQUIRED`، `NOTE_TOO_LONG`). فقط پنل می‌بینه؛ ویرایش و حذف نداره.

## ۶. استرداد (فقط ADMIN)

```js
POST  /admin/orders/KV-218340/refunds   { "amount": 1450000, "reason": "…", "method": "BANK_TRANSFER" }   → 201 Refund (PENDING)
PATCH /admin/refunds/12                  { "status": "PROCESSING" }
PATCH /admin/refunds/12                  { "status": "COMPLETED", "referenceId": "73519086", "note": "…" }
PATCH /admin/refunds/12                  { "status": "FAILED", "note": "شماره شبا تأیید نشد؛ دوباره باهات تماس می‌گیریم" }
```

- برگشت پول **دستیه**: سرور به درگاه چیزی نمی‌گه. همکار به مشتری زنگ می‌زنه، شبا رو می‌گیره (شبا هیچ‌جا ذخیره نمی‌شه)، واریز می‌کنه، بعد `COMPLETED` با شماره‌ی پیگیری بانک.
- مبلغ بیشتر از «پرداخت‌شده − استردادهای غیر `FAILED`» ← `400 REFUND_EXCEEDS_PAID` با `meta.refundable`: سقف رو کنار فیلد نشون بده.
- `COMPLETED` بدون `referenceId` ← `400 REFERENCE_ID_REQUIRED`. `COMPLETED` یا `FAILED` ِ دوباره ← `422`. `PROCESSING` ِ دوباره بی‌اثره.
- `COMPLETED` مبلغ رو به `totals.refunded` ِ سفارش اضافه می‌کنه و مشتری پیامک «پولت برگشت» می‌گیره. `note` به مشتری نشون داده می‌شه.
- استرداد `FAILED` مبلغش رو آزاد می‌کنه (می‌شه دوباره ثبت کرد).

## ۷. درخواست تغییر (فاز ۳)

مشتری بعد از دیدن عکس (تأیید کرده باشه یا نه) می‌تونه رنگ یا مدل دیگه بخواد، یه قلم رو حذف کنه یا چیز دیگه‌ای بخواد. سفارش می‌ره `CHANGE_REQUESTED` (تب صف: `status=CHANGE_REQUESTED`) و عکس‌ها `CHANGE_REQUESTED`. چیزی که مشتری خواسته تو `preShipmentMediaDetail.feedback` ـه: `changeType` (`COLOR`، `MODEL`، `CANCEL_ITEM`، `OTHER`)، `orderItemId`، `desiredVariantId`، `note`. هر لحظه فقط یه درخواست باز.

```js
POST /admin/orders/KV-218340/change-request/resolution
{ "decision": "APPLY", "variantId": null, "note": "زیتونیش رو گذاشتیم", "notifyCustomer": true }
{ "decision": "REJECT", "note": "این رنگ تموم شده" }
200 AdminOrder
```

- **`REJECT`** با `note` ِ اجباری (`400 NOTE_REQUIRED`) و بدون `variantId` (`400 VARIANT_NOT_ALLOWED`). سفارش برمی‌گرده `PROCESSING`، هیچ چیز دیگه عوض نمی‌شه.
- **`APPLY` ِ `COLOR`/`MODEL`:** ردیف رنگی رو می‌گیره که مشتری خواسته، یا `variantId` ِ تو (رنگی که خودت انتخاب کردی). `COLOR` باید رنگ **همون** محصول باشه و `MODEL` رنگی از محصول **دیگه** (`400 DESIRED_VARIANT_INVALID` روی `variantId`). قیمت **امروز** ِ رنگ تازه حساب می‌شه، اسم و عکس و SKU هم تازه‌ان، و شماره‌ی ردیف (`items[].id`) عوض نمی‌شه. رزرو رنگ قبلی آزاد و رنگ تازه رزرو می‌شه.
  - گرون‌تر ← `totals.balanceDue` بالا می‌ره و مشتری تو صفحه‌ی سفارشش دکمه‌ی «پرداخت» می‌بینه. تا نپردازه ارسال نمی‌شه (`BALANCE_DUE`).
  - ارزون‌تر ← اول از مانده‌ی موجود کم می‌شه، باقیش یه استرداد `PENDING` (`source = CHANGE_REQUEST`، `BANK_TRANSFER`) تو `refunds`: مثل بقیه‌ی استردادها (بخش ۶) ADMIN واریز و `COMPLETED` ـش می‌کنه.
  - رنگ غیرفعال یا محصول منتشرنشده ← `422 PRODUCT_UNAVAILABLE`؛ موجودی کم ← `422 QUANTITY_EXCEEDS_STOCK` با `meta.availableQuantity`؛ بیشتر از سقف محصول در سفارش ← `422 QUANTITY_EXCEEDS_LIMIT`. با هر کدوم **هیچ چیز** عوض نمی‌شه و درخواست باز می‌مونه: رنگ دیگه‌ای پیشنهاد بده (`variantId`) یا رد کن.
- **`APPLY` ِ `CANCEL_ITEM`:** ردیف حذف می‌شه، رزروش آزاد، و سهم پرداخت‌شده‌اش (مبلغ ردیف منهای سهمش از کد تخفیف؛ هزینه‌ی ارسال نه) مثل «ارزون‌تر» تسویه می‌شه. **تنها ردیف سفارش ← لغو کامل سفارش**، دقیقاً مثل لغو پنل (بخش ۴): `CANCELLED`، استرداد کل پرداخت، و پیامک «لغو شد». قبل از تأیید به اپراتور هشدار بده.
- **`APPLY` ِ `OTHER`:** کار رو دستی انجام بده؛ `note` (چی کار کردی) اجباریه. فقط وضعیت برمی‌گرده.
- `variantId` فقط با `APPLY` ِ `COLOR`/`MODEL`؛ با بقیه ← `400 VARIANT_NOT_ALLOWED`.
- **بعد از هر رسیدگی** سفارش `PROCESSING` و عکس‌ها `WAITING` ـن: یه دسته‌ی **تازه** عکس بگیر و بفرست (بخش ۲). دسته‌ی قبلی تا دسته‌ی تازه برسه برای مشتری می‌مونه.
- `note` **به مشتری نشون داده می‌شه** (تو `feedback.resolution` و تایم‌لاینش) ولی با پیامک نمی‌ره. `notifyCustomer` (پیش‌فرض `true`) پیامک «درخواستت بررسی شد؛ جزئیات تو صفحه‌ی سفارش» می‌فرسته.
- سفارشی که `CHANGE_REQUESTED` نیست ← `422 INVALID_STATUS_TRANSITION`. پرداخت مانده‌ای که همین الان در حال تأییده ← `422 PAYMENT_IN_PROGRESS`: چند ثانیه بعد دوباره.
- هر رسیدگی یه ردیف `ORDER_CHANGE_RESOLVED` تو لاگ تغییرات (تصمیم، نوع، رنگ قبل و بعد؛ بدون `note`).
- **جمع‌ها بعد از تغییر:** `subtotal` و تخفیف محصول از ردیف‌های فعلی‌ان؛ `codeDiscount` ثابت، منهای سهم کد ِ ردیف‌های حذف‌شده؛ `shippingCost` ثابت؛ `payable` کل پرداخت‌شده‌ست (ثبت + مانده‌های پرداخت‌شده). تو `payments` پرداخت مانده با `purpose = BALANCE` و `amount` ِ خودش جداست.

## ۸. جاهایی که راحت می‌شه اشتباه کرد

- **وضعیت رو از دکمه‌های ثابت نساز.** `allowedTransitions` رو بخون؛ هر قانونی (مثلاً «لغو بعد از ارسال نه») همون‌جاست.
- **`carrier` رو به اپراتور برای انتخاب نده**؛ همون روش سفارشه.
- **دکمه‌ی ارسال قبل از `feedbackDeadline`** فقط وقتی فعاله که `preShipmentMedia.status = APPROVED`.
- **ارسال یه سفارش گروه، بقیه‌ی گروه رو هم می‌فرسته.** قبل از تأیید به اپراتور نشون بده (`shipmentGroup.orderCodes`).
- **`note` ِ وضعیت و استرداد عمومیه، یادداشت داخلی خصوصی.** جاشون رو قاطی نکن.
- **مبلغ قابل استرداد رو خودت حساب نکن**؛ از خطای `REFUND_EXCEEDS_PAID` (`meta.refundable`) یا جمع‌های صفحه بخون.
- **اختلاف قیمت تغییر رو خودت حساب نکن و جلوی اپراتور قول نده.** قیمت لحظه‌ی رسیدگی حساب می‌شه؛ بعد از `200` عدد رو از `totals.balanceDue` و `refunds` ِ جواب بخون.
- **بعد از رسیدگی عکس تازه لازمه.** دکمه‌ی ارسال تا دسته‌ی تازه فرستاده و تأیید نشده (یا مهلتش نگذشته) کار نمی‌کنه، حتی اگه دسته‌ی قبلی تأیید شده بود.
- **`CANCEL_ITEM` ِ تنها قلم کل سفارش رو لغو می‌کنه.** دیالوگ تأییدش همون دیالوگ لغو باشه.
