# مرجوعی: راهنمای فرانت (kiva-fe و پنل)

> بک‌اند: برنچ `returns`، فاز ۷ ِ `COMPLETION_ROADMAP.md` (گام‌های ۷.۰ تا ۷.۶)، ۳۰ مهر ۱۴۰۵. مرجع دقیق شکل درخواست‌ها و پاسخ‌ها کانترکت `api/kiva-openapi.yml` (نسخه‌ی 1.13.0) است؛ بخش ۱۲.۱۰ ِ نقشه‌ی راه تغییرات کانترکت این فاز رو فهرست کرده، و تصمیم‌های ۱ تا ۵۲ ِ بخش ۱۲.۲ دلیل هر قانون رو.
> این فایل می‌گه مشتری چطور عکس آپلود می‌کنه و مرجوعی می‌خواد، «مرجوعی‌های من» و صفحه‌ی سفارش چی نشون می‌دن، و اپراتور و ادمین تو پنل چه قدم‌هایی دارن.

## خلاصه

- مشتری **تا ۷ روز بعد از تحویل** (`items[].returnableUntil` و `actions.canReturn` ِ سفارش) یه بار مرجوعی می‌خواد. اگه رد شد، تا مهلت باز ِ دوباره می‌تونه؛ اگه بسته شد یا پولش برگشت، دیگه نه.
- عکس‌ها **اول** با `POST /me/uploads` آپلود می‌شن (`purpose = RETURN_EVIDENCE`) و شناسه‌شون تو `mediaIds` می‌ره. آپلودی که ۴۸ ساعت به چیزی وصل نشه پاک می‌شه.
- قدم‌های پنل: تأیید یا رد ← (جمع‌آوری) ← رسیدن به انبار ← استرداد با `returnId` یا بستن. هر قدم کل `AdminReturnRequest` رو برمی‌گردونه؛ دکمه‌ها رو از `allowedTransitions` بساز.
- شماره شبا **هیچ‌جا** نیست: برای `BANK_TRANSFER` اپراتور تلفنی می‌گیره.

## ۱. kiva-fe: آپلود عکس

```js
POST /me/uploads   multipart: purpose=RETURN_EVIDENCE, file=<jpeg|png|webp|mp4>
201 MediaAsset   |  400 · 401 · 413 (عکس > ۸ مگ، ویدیو > ۵۰ مگ) · 415 (نوع دیگه، HEVC) · 429 (Retry-After)
```

- حداکثر ۲۰ آپلود در ساعت برای هر کاربر؛ `429` هدر `Retry-After` (ثانیه) داره. دکمه رو تا اون موقع غیرفعال کن.
- ویدیو حداکثر ۶۰ ثانیه (`400 VIDEO_TOO_LONG`). ویدیوی آیفون با HEVC ← `415` با `meta.hint = "H264"`: بگو «دوربین رو روی Most Compatible بذار».
- `alt` نداره. پیش‌نمایش رو از `thumbnailUrl` بساز.

## ۲. kiva-fe: فرم مرجوعی

```js
POST /me/orders/KV-218340/returns
{ "items": [{ "orderItemId": 1, "quantity": 1 }], "reason": "NOT_AS_PICTURED",
  "description": "رنگش با عکس فرق داشت", "mediaIds": ["med_…"], "refundMethod": "ORIGINAL_PAYMENT" }
201 ReturnRequest
```

- دکمه‌ی «مرجوع کردن» فقط وقتی `actions.canReturn` ِ سفارش `true` ـه. قلم‌های قابل انتخاب: `items[]` با `returnable = true` (قیمت و سیاست همون لحظه‌ی خرید).
- `quantity` ۱ تا تعداد خریده‌شده. هر قلم یه بار.
- چهار دلیل «تقصیر کیوا» (`NOT_AS_PICTURED`، `MANUFACTURING_DEFECT`، `WRONG_ITEM`، `DAMAGED_IN_TRANSIT`) **حداقل یه عکس یا ویدیو** می‌خوان (`400 EVIDENCE_REQUIRED` روی `mediaIds`)، حداکثر ۶. هزینه‌ی برگشتشون با کیواست (`shippingPaidBy = KIVA`)؛ `CHANGED_MIND` و `OTHER` با مشتری. این رو کنار هر دلیل نشون بده.
- `OTHER` توضیح لازم داره (`400 DESCRIPTION_REQUIRED`). `description` حداکثر ۱۰۰۰.
- `refundMethod`: `ORIGINAL_PAYMENT` (پیش‌فرض) یا `BANK_TRANSFER`. فیلد شبا نساز.
- خطاها: همه‌ی خطاهای شکل با هم و هر کدوم روی فیلد خودش (`items.0.quantity`، `mediaIds.2`). عکسی که مال کس دیگه‌ست یا برای چیز دیگه‌ای آپلود شده همون `MEDIA_NOT_FOUND` ِ عکس ناموجوده. `422 ORDER_NOT_RETURNABLE` (قبلاً مرجوعی داره یا تحویل نشده)، `422 RETURN_WINDOW_EXPIRED` با `meta.returnableUntil`.
- بعد از ثبت پیامکی نمی‌ره (مشتری خودش همین الان ثبت کرده).

## ۳. kiva-fe: «مرجوعی‌های من» و صفحه‌ی سفارش

```js
GET /me/returns            [ReturnRequest]   (آرایه‌ی ساده، تازه‌ترین اول، حداکثر ۵۰)
GET /me/orders/KV-218340   OrderDetail.returns: [ReturnRequest]  (همه، ردشده‌ها هم)
```

- `statusLabel` آماده‌ست. اسم و رنگ قلم‌ها همون لحظه‌ی خریده، نه کاتالوگ امروز.
- `instructions` در `REQUESTED` و `REJECTED` `null` ـه؛ بعد از تأیید یا متن اپراتوره یا متن پیش‌فرض («کد رهگیری رو برای پشتیبانی بفرست»). **کد رهگیری ِ ارسال مشتری endpoint نداره**: مشتری برای پشتیبانی می‌فرسته.
- `rejectionReason` و `closeReason` رو به خود مشتری نشون بده (پیامک متن نداره، فقط «وضعیت عوض شد»).
- `pickup` وقتی کیوا جمع می‌کنه: `carrierName`، `scheduledFor`، `trackingCode`، `note`.
- `refund` تازه‌ترین استرداد ِ همین مرجوعیه، `FAILED` هم (با `statusNote` مثل «شماره شبا تأیید نشد»).
- وضعیت سفارش: `RETURN_REQUESTED` (درخواست، تأیید، جمع‌آوری)، `RETURNED` (به انبار رسید)، `REFUNDED` (پول برگشت)؛ رد و بستن ← دوباره `DELIVERED`. تب «سفارش‌های من» همون قبلیه.
- `timeline` ِ سفارش پنج ردیف تازه داره (`RETURN_REQUESTED`، `RETURN_RECEIVED`، `RETURN_REJECTED`، `RETURN_CLOSED`، `RETURN_REFUNDED`) با `label` ِ آماده.

## ۴. پنل: صف و قدم‌ها (`ADMIN`، `OPERATOR`)

```js
GET  /admin/returns?status=APPROVED&sort=oldest&page=1&size=20   {items: [AdminReturnRequest], meta}
POST /admin/returns/{id}/decision   { "approve": true, "shippingPaidBy": "KIVA", "instructions": "…" }
                                    { "approve": false, "rejectionReason": "…" }               ← 200 AdminReturnRequest
PUT  /admin/returns/{id}/pickup     { "carrier": "TIPAX", "scheduledFor": "…Z", "trackingCode": "…", "note": "…", "notifyCustomer": true }
POST /admin/returns/{id}/receipt    { "items": [{ "orderItemId": 1, "receivedQuantity": 1, "restockQuantity": 1 }], "note": "…" }
POST /admin/returns/{id}/close      { "reason": "…", "notifyCustomer": true }
```

- **دکمه‌ها از `allowedTransitions`:** `REQUESTED` ← تأیید/رد؛ `APPROVED` و `PICKUP_SCHEDULED` ← جمع‌آوری، رسید، بستن؛ `RECEIVED` ← «ثبت استرداد» (`REFUNDED`) و بستن؛ `RECEIVED` با استرداد باز ← هیچ (منتظر نتیجه‌ی استرداد). انتقال دیگه ← `422 INVALID_STATUS_TRANSITION` با `meta.allowed`.
- **صف:** `status` یکی یا هیچ؛ `sort=oldest` برای دیدن مرجوعی‌هایی که هیچ‌وقت نرسیدن (بستن خودکار نداریم). `customer.fullName` و `phone` از حساب فعلی مشتری.
- **تأیید:** `shippingPaidBy` نیومد = پیشنهاد ِ دلیل. **رد:** دلیل ۳ تا ۵۰۰ لازم (`400 REJECTION_REASON_REQUIRED`). هر دو پیامک دارن.
- **جمع‌آوری:** فقط `TIPAX` یا `POST` (`COURIER` ← `400 SHIPPING_METHOD_NOT_SUPPORTED`). `scheduledFor` از یه ساعت پیش تا ۳۰ روز بعد. دوباره صدا زدن **همه‌ی** فیلدها رو جایگزین می‌کنه (فیلد نیومده = خالی)، پس فرم رو از `pickup` ِ فعلی پر کن.
- **رسید:** **هر قلم مرجوعی دقیقاً یه ردیف** (کم، اضافه یا تکراری ← `400 RECEIPT_ITEMS_MISMATCH` روی `items` با `meta.expectedItemIds`)؛ `restockQuantity ≤ receivedQuantity ≤` تعداد مرجوعی. همه صفر هم مجازه (بسته خالی). عددهای `restockQuantity` به موجودی برمی‌گردن و تو «تاریخچه‌ی موجودی» با دلیل `RETURNED`، `note` = کد مرجوعی و اسم همین اپراتور دیده می‌شن. `note` ِ رسید داخلیه.
- **مبلغ پیشنهادی:** `suggestedRefund` فقط در `RECEIVED`: سهم پرداخت‌شده‌ی عددهای رسیده (کد تخفیف کم شده، **هزینه‌ی ارسال نه**)، حداکثر «قابل استرداد» ِ سفارش. فقط پیشنهاده؛ مثلاً اگه هزینه‌ی برگشت با مشتریه، خودت کم کن.
- **بستن:** دلیل ۳ تا ۵۰۰ (`400 CLOSE_REASON_REQUIRED`)، که مشتری می‌بینه. از `REQUESTED` نه (ردش کن). با استرداد ِ `PENDING`/`PROCESSING` ← `422 RETURN_REFUND_OPEN`: اول استرداد رو تموم یا `FAILED` کن.

## ۵. پنل: استرداد ِ مرجوعی (`ADMIN`)

```js
POST  /admin/orders/KV-218340/refunds   { "amount": 1890000, "reason": "مرجوعی", "returnId": 12 }   ← 201 Refund
PATCH /admin/refunds/{refundId}          { "status": "COMPLETED", "referenceId": "140507181234567" }
```

- `returnId` فقط برای مرجوعی ِ `RECEIVED` ِ **همین سفارش** (`400 RETURN_NOT_FOUND`، `422 INVALID_STATUS_TRANSITION`). یه استرداد ِ زنده برای هر مرجوعی (`422 RETURN_REFUND_EXISTS`)؛ بعد از `FAILED` یکی تازه ثبت کن.
- `method` نیومد = همون `refundMethod` ِ مرجوعی. برای `BANK_TRANSFER` شبا رو تلفنی بگیر و فقط شماره‌ی پیگیری بانک رو ثبت کن.
- `COMPLETED` ← مرجوعی `REFUNDED`، سفارش `REFUNDED`، `totals.refunded` بالا میره، پیامک «پولت برگشت». `FAILED` ← مرجوعی همون `RECEIVED` می‌مونه.

## ۶. جاهایی که راحت می‌شه اشتباه کرد

- `canReturn` رو خودت از تاریخ تحویل حساب نکن؛ سرور مرجوعی ِ بسته‌شده و مهلت رو با هم می‌بینه.
- عکس رو قبل از فرم آپلود کن و شناسه رو نگه دار؛ آپلود ۴۸ ساعته پاک می‌شه، پس فرم نیمه‌کاره‌ی دیروز ممکنه `MEDIA_NOT_FOUND` بگیره (دوباره آپلود کن).
- `allowedTransitions` رو از روی `status` حدس نزن: `RECEIVED` با استرداد باز هیچ قدمی نداره.
- جمع‌آوری رو «ویرایش جزئی» فرض نکن: هر `PUT` کل جمع‌آوری رو عوض می‌کنه.
