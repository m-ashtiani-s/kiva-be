# نظرها: راهنمای فرانت (kiva-fe و پنل)

> بک‌اند: برنچ `reviews`، فاز ۸ ِ `COMPLETION_ROADMAP.md` (گام‌های ۸.۰ تا ۸.۶)، ۳۰ مهر ۱۴۰۵. مرجع دقیق شکل درخواست‌ها و پاسخ‌ها کانترکت `api/kiva-openapi.yml` (نسخه‌ی 1.14.0) است؛ بخش ۱۳.۸ ِ نقشه‌ی راه تغییرات کانترکت این فاز رو فهرست کرده، و تصمیم‌های بخش ۱۳.۲ دلیل هر قانون رو.
> این فایل می‌گه مشتری چطور نظر می‌ده، ویرایش و حذف می‌کنه، لیست و خلاصه‌ی امتیاز و رأی «مفید بود» چطور کار می‌کنن، امتیاز کجاها دیده می‌شه، و پشتیبانی تو پنل چه کارهایی داره.

## خلاصه

- هر کاربر واردشده برای محصول **منتشرشده** نظر می‌ده؛ خرید شرط نیست. هر نظر اول `PENDING` ـه و فقط بعد از تأیید پشتیبانی عمومی می‌شه.
- **یه نظر «زنده» (`PENDING` یا `APPROVED`) برای هر کاربر و محصول.** بعد از رد، نظر تازه یا ویرایش همون نظر ردشده مجازه.
- برچسب «خریدار» (`isVerifiedBuyer`) فقط وقتی نظر به یه ردیف ِ سفارش **تحویل‌شده**‌ی خود کاربر وصله. اگه فرم سفارش رو نفرسته، سرور خودش آخرین خرید تحویل‌شده رو پیدا می‌کنه.
- امتیاز محصول فقط از نظرهای تأییدشده‌ست و روی کارت لیست، جستجو، صفحه‌ی محصول و پنل یکیه.
- **متن نظر و پاسخ متن ساده‌ست، نه HTML.** همیشه به‌صورت متن نشون بده (نه `innerHTML`/`v-html`)؛ خط تازه رو با `white-space: pre-line` نگه دار.
- پیامکی برای نظرها نمی‌ره (نه تأیید، نه رد، نه پاسخ). مشتری وضعیت رو در «نظرهای من» می‌بینه.

## ۱. kiva-fe: فرم نظر

```js
POST /products/{productId}/reviews     (ورود لازم)
{ "rating": 5, "text": "…", "authorName": "سمیرا", "contact": "09121234567",
  "orderCode": "KV-218340", "orderItemId": 1, "mediaIds": ["med_…"] }
201 Review (status = PENDING)
```

- `rating` ۱ تا ۵ (`400 RATING_INVALID`). `text` ۱۰ تا ۱۰۰۰ بعد از trim (`TEXT_TOO_SHORT` با `meta.minLength`، `TEXT_TOO_LONG`)؛ بیشتر از دو خط خالی پشت‌سرهم یکی می‌شه.
- `authorName` اختیاری، حداکثر ۴۰. خالی ← «کاربر کیوا». **اسم حساب خودکار گذاشته نمی‌شه.** `authorInitial` آماده‌ست برای آواتار.
- `contact` اختیاری: موبایل یا ایمیل (`400 CONTACT_INVALID`). فقط پنل می‌بینه؛ بگو «فقط برای اینکه پشتیبانی اگه لازم شد باهات تماس بگیره».
- `orderCode` و `orderItemId` با هم یا هیچ‌کدوم (فقط یکی ← `FIELD_REQUIRED` روی اون یکی). از «نظر بده» ِ صفحه‌ی سفارش یا «منتظر نظر تو» پرشون کن. سفارش کس دیگه ← `404 ORDER_NOT_FOUND`؛ ردیف دیگه یا محصول دیگه ← `400`؛ هنوز تحویل نشده ← `422 REVIEW_PURCHASE_NOT_DELIVERED`.
- عکس و ویدیو: فقط وقتی `features.reviewMedia` ِ `/config` روشنه. اول `POST /me/uploads` با `purpose = REVIEW_PHOTO`، بعد شناسه‌ها در `mediaIds` (حداکثر ۵). پرچم خاموش ← `400 REVIEW_MEDIA_DISABLED`. عکس بعد از ثبت عوض نمی‌شه.
- `409 REVIEW_ALREADY_EXISTS`: نظر زنده‌ی دیگه‌ای برای همین محصول داره ← لینک «نظرهای من». `429 RATE_LIMITED` (بیشتر از ۵ نظر در ۲۴ ساعت) هدر `Retry-After` (ثانیه) داره.
- همه‌ی خطاهای شکل با هم و هر کدوم روی فیلد خودش (`mediaIds.2`).
- بعد از ثبت بگو «نظرت ثبت شد و بعد از بررسی منتشر می‌شه». نظر `PENDING` برای خود نویسنده اول ِ صفحه‌ی ۱ ِ لیست محصول با `status`/`statusLabel` دیده می‌شه.

## ۲. kiva-fe: «نظرهای من» و «منتظر نظر تو»

```js
GET    /me/reviews?page=1&size=20         {items: [Review], meta, counts: {total, pending, approved, rejected}}
GET    /me/reviews/pending-products       [ReviewablePurchase]   (حداکثر ۵۰)
PATCH  /me/reviews/{reviewId}             { "rating": 4, "text": "…", "authorName": null }   200 Review
DELETE /me/reviews/{reviewId}             204
```

- همه‌ی وضعیت‌ها، تازه‌ترین اول؛ `product` (اسم، لینک، عکس کوچیک) اینجا (و در پنل) پره. `rejectionReason` فقط برای خود نویسنده‌ست؛ کنار نظر ردشده نشونش بده.
- ویرایش فقط `PENDING` یا `REJECTED` (`APPROVED` ← `422 REVIEW_NOT_EDITABLE`: بگو «برای عوض کردن، حذف کن و دوباره بنویس»). ویرایش ردشده دوباره `PENDING` می‌شه و دلیلش پاک. فیلدی که نفرستی عوض نمی‌شه؛ `authorName: null` یعنی «کاربر کیوا».
- حذف هر وضعیتی، واقعاً پاک می‌شه (با رأی‌هاش) و اگه تأییدشده بود امتیاز محصول کم می‌شه. نظر کس دیگه ← `404`.
- «منتظر نظر تو»: یه ردیف برای هر محصول خریده‌شده و تحویل‌شده که هنوز نظر زنده نداره (آخرین تحویل)، با `orderCode` و `orderItemId` برای فرم.

## ۳. kiva-fe: لیست نظرهای محصول، خلاصه و رأی

```js
GET /products/{productId}/reviews?sort=newest&rating=5&withMedia=true&verifiedOnly=true&page=1&size=20
    {summary: RatingSummary, items: [Review], meta}
GET /products/{productId}/reviews/summary      RatingSummary
PUT /reviews/{reviewId}/helpful   { "helpful": true }     {helpfulCount, myVote}
```

- `sort`: `newest` (پیش‌فرض)، `highest`، `lowest`، `helpful`. فیلترها: `rating` ۱ تا ۵، `withMedia` و `verifiedOnly` فقط `true`/`false` (وگرنه `400 BOOLEAN_INVALID`). صفحه ۲۰، حداکثر ۱۰۰.
- لیست با ورود و بدون ورود کار می‌کنه؛ توکن منقضی ← `401` (اول توکن رو تازه کن، بعد دوباره بخون). با ورود، `myHelpfulVote` و `isMine` پرن و نظرهای `PENDING` ِ خودش اول صفحه‌ی ۱ میان (بیرون از `meta.totalItems`).
- `summary`: `average` (یه رقم اعشار)، `count`، `distribution` پنج ردیف (۵ ستاره اول) با `count` و `percent`، `verifiedBuyerPercent` (بی‌نظر ← `null`). محصول منتشرنشده ← `404`.
- `reply`: «پاسخ کیوا» با `authorName = "کیوا"`؛ زیر نظر با استایل جدا نشونش بده.
- رأی: `helpful: true` می‌ده، `false` پس می‌گیره؛ «مفید نبود» نداریم. `myVote` همیشه `true` یا `null`. تکرار بی‌اثره (همون `200`). رأی به نظر خود ← `422 OWN_REVIEW_VOTE` (دکمه رو برای `isMine` نشون نده). ورود لازم (`401` ← صفحه‌ی ورود).

## ۴. kiva-fe: امتیاز روی کارت‌ها و صفحه، «نظر بده» در سفارش‌ها

- `ProductSummary.rating = {average, count}` روی هر کارت (لیست، جستجو، محصولات مرتبط) همیشه هست؛ بی‌نظر `{0, 0}`: ستاره‌ها رو نشون نده، یا «هنوز نظری نیست».
- صفحه‌ی محصول: `rating` و `ratingSummary` ِ کامل (همون شکل `…/reviews/summary`).
- مرتب‌سازی `sort=rating_desc` («بیشترین امتیاز»): میانگین، بعد تعداد نظر؛ بی‌نظرها بعد از نظردارها، ناموجودها ته لیست.
- سفارش (`GET /me/orders` و `/me/orders/{code}`): `actions.canReview` روی سفارش تحویل‌شده‌ای که دست‌کم یه محصول منتشرشده‌ی بی‌نظر داره؛ `items[].reviewed` برای هر ردیف. «نظر بده» رو کنار ردیف‌های `reviewed = false` بذار و فرم رو با `orderCode` و `orderItemId` همون ردیف باز کن.

## ۵. پنل: صف، تأیید و رد، «پاسخ کیوا» (`ADMIN`، `SUPPORT`)

```js
GET    /admin/reviews?status=PENDING&productId=12&hasReply=false&page=1&size=20
       {items: [Review + customerId + contact], meta, pendingCount}
POST   /admin/reviews/{reviewId}/moderation   { "action": "APPROVE", "reply": "ممنون از نظرت" }
                                              { "action": "REJECT", "rejectionReason": "شامل تبلیغ" }      200 Review
PUT    /admin/reviews/{reviewId}/reply        { "text": "…" }      200 Review
DELETE /admin/reviews/{reviewId}/reply        204
```

- صف بدون `status` همه رو نشون می‌ده، تازه‌ترین ثبت اول. `pendingCount` کل نظرهای منتظره، **مستقل از فیلترها**: برای نشان روی منو.
- در پنل (صف و پاسخ تأیید/رد و «پاسخ کیوا») `product` پره (`id`، `slug`، `sku`، `name`، `imageUrl`)، با هر وضعیتی که محصول الان داره (پیش‌نویس و بایگانی هم). دیگه لازم نیست اسم‌ها رو از `GET /products?ids=` بخونی (کانترکت 1.22.0).
- `contact` راه تماسیه که نویسنده داد، وگرنه موبایل فعلی حسابش؛ `customerId` لینک به صفحه‌ی مشتری.
- وضعیت‌ها: `PENDING` ← تأیید یا رد؛ تأییدشده ← رد (برداشتن از سایت)؛ ردشده ← تأیید. همون وضعیت دوباره ← `422 INVALID_STATUS_TRANSITION` با `meta.allowed`.
- رد: دلیل ۳ تا ۳۰۰ لازم (`400 REJECTION_REASON_REQUIRED`)؛ فقط نویسنده‌ی نظر اون رو می‌بینه، پس مؤدبانه بنویس. `reply` با رد ← `400 REPLY_REQUIRES_APPROVAL`.
- تأیید ِ ردشده وقتی نویسنده بعدش نظر زنده‌ی دیگه‌ای برای همین محصول نوشته ← `409 REVIEW_ALREADY_EXISTS`.
- «پاسخ کیوا» فقط زیر نظر تأییدشده (`422` روی بقیه)، ۲ تا ۱۰۰۰، متن ساده؛ دوباره نوشتن جایگزین می‌کنه. حذف همیشه `204`. رد کردن نظر پاسخ رو نگه می‌داره ولی پنهانش می‌کنه.
- `OPERATOR` و `CATALOG_MANAGER` ← `403`: منوی «نظرها» رو فقط برای `ADMIN` و `SUPPORT` نشون بده.
- لاگ تغییرات: `REVIEW_MODERATED`، `REVIEW_REPLIED`، `REVIEW_REPLY_DELETED` با اسم محصول؛ متن پاسخ و دلیل رد در لاگ نمی‌رن.
