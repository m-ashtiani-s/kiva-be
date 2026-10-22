# ریویوی نظرها: فاز ۸ (گام‌های ۸.۰ تا ۸.۶)

> برنچ `reviews`، ۳۰ مهر ۱۴۰۵. نقشه‌ی راه: `COMPLETION_ROADMAP.md`، فاز ۸. راهنمای فرانت: `docs/FRONTEND_REVIEWS.md`.
> برای ردیف ۱۳ ِ بخش ۳ ِ نقشه‌ی راه و خطرهای خود این فاز (لو رفتن دلیل رد، رأی تقلبی، امتیاز ناسازگار): دفاع کجاست، کدوم تست نگهبانشه، و نتیجه‌ی خرابکاری عمدی (نگهبان یه بار برداشته شد، تست قرمز شد، برگردونده شد).

---

## خلاصه

| چی بررسی شد | نتیجه |
|---|---|
| `./gradlew test --continue --rerun-tasks` | ✅ **۲۹۲۱ تست** سبز. ۵ تست skip: تست‌های ابزار واقعی (`vips` و `ffmpeg` روی این ماشین نیستن) |
| هشدار کامپایلر | ✅ صفر |
| قوانین لایه‌ها | ✅ `domain`، `common` و `application` هیچ import از Ktor، Exposed یا Koin ندارن |
| کانترکت | ✅ 1.14.0 (گام ۸.۰، به‌علاوه‌ی `409` ِ moderation در ۸.۵). هر پاسخ نظر در تست‌های route با شِمای `Review` مقایسه می‌شه (`ContractSchemas`)؛ `RouteGuardsTest` نقش‌های چهار route ِ پنل رو با `x-roles` چک می‌کنه |
| سازگاری امتیاز | ✅ `ReviewsConsistencyOnDatabaseTest` (تازه): ۲۰۰ قدم تصادفی از routeهای واقعی، بعد از هر قدم امتیاز هر محصول = `COUNT`/`SUM`/ستاره‌ها/خریدارهای نظرهای تأییدشده، و `helpfulCount` هر نظر = رأی‌هاش |
| دور واقعی روی سرور با kiva-fe و پنل | ⬜ مونده برای تو (پایین‌تر) |
| merge به `main` و deploy | ⬜ منتظر تأیید تو (فازهای قبل هم هنوز merge نشدن؛ این برنچ از سر فاز ۷ ساخته شده) |

---

## ردیف‌های بخش ۳ و خطرهای این فاز

| # | خطر | دفاع | تست‌ها |
|---|---|---|---|
| 13 | نظر جعلی | نظر فقط با ورود؛ هر نظر `PENDING` و فقط تأییدشده‌ها عمومی و در امتیاز؛ یه نظر زنده برای هر کاربر و محصول (چک `ReviewDomainService.gate` و دیوار `review_one_live_idx`)؛ حداکثر ۵ نظر در ۲۴ ساعت | `WebV1MyReviewsOnDatabaseTest` › `written - 201 PENDING, its author's, held against Review - again 409 - after it is turned down 201`، `the sixth review in 24 hours is 429 RATE_LIMITED with Retry-After`؛ `ReviewRepositoryImplTest` › `a second live review of a product by one user is refused by review_one_live_idx …`، `a rejected review edited back to PENDING while another is live is refused by the index too`؛ `WebV1AdminReviewsOnDatabaseTest` › `a turned-down review approved while its author has another live one - 409, nothing changed` |
| 13 | برچسب «خریدار» ِ دروغ | `ReviewDomainService.purchaseLink`: فقط ردیف ِ سفارش خود کاربر، از همون محصول، از سفارش تحویل‌شده‌ی لغونشده؛ پیدا کردن خودکار هم فقط بین همین‌ها | `ReviewDomainServiceTest` › `named - none of the author's 404, a line it hasn't 400, another product's 400, not delivered or cancelled 422`، `not named - the latest delivered of two orders is found by itself, a cancelled one never, none is no purchase`؛ `WebV1MyReviewsOnDatabaseTest` › `linked by name - someone else's order 404, a line of another product 400, an order shipped but not delivered 422, both or neither 400`، `a buyer's review is linked by itself …` |
| — | لو رفتن دلیل رد | `Reviews` (یه ساختن برای هر بیننده): `rejectionReason` فقط برای نویسنده و پنل (تست مستقیم: `WebV1ProductReviewsOnDatabaseTest` › `a turned-down review built for anyone but its author and the panel carries no reason …`)؛ لیست عمومی فقط `APPROVED` (به‌علاوه‌ی `PENDING` ِ خود بیننده)؛ دلیل و متن پاسخ در لاگ نمی‌رن (`toString` ِ Cmd ها، `longText` در لاگ تغییرات) | `WebV1ProductReviewsOnDatabaseTest` (دلیل رد کاربر دیگه هیچ‌وقت تو لیست عمومی نیست)؛ `WebV1MyReviewsOnDatabaseTest` › `«نظرات من» - every status, newest first, each with its product, the counts with rejected`؛ `WebV1AdminReviewsOnDatabaseTest` › `the body - … an approval with a reply keeps it` (لاگ فقط «پاسخ عوض شد») |
| — | رأی تقلبی | رأی فقط با ورود، فقط روی `APPROVED`، هرگز روی نظر خود (`422 OWN_REVIEW_VOTE`)؛ یه رأی برای هر کاربر (کلید اصلی `reviewvote_tbl`)؛ رأی زیر قفل ردیف نظر | `WebV1ProductReviewsOnDatabaseTest` › `a vote, the same vote again, taken back - one's own 422 …`، `20 votes from 20 customers at the same moment - helpfulCount 20`؛ `ReviewsConsistencyOnDatabaseTest` |
| — | امتیاز ناسازگار | `RatingBook` تنها راه عوض شدن امتیاز، در همون تراکنش؛ قفل ۱۱ (ردیف نظر) و بعد ردیف امتیاز؛ `CHECK` های `V33` روی جمع‌ها؛ `RatingTally` اعداد ناجور رو بلند رد می‌کنه | `ReviewsConsistencyOnDatabaseTest` › `200 random steps through the routes …` (**تازه**)؛ `WebV1AdminReviewsOnDatabaseTest` › `approval and the author's deletion at the same moment, 20 times …`؛ `WebV1MyReviewsOnDatabaseTest` › `an approved review deleted takes its stars out of the product's rating …`؛ `RatingTallyTest`؛ `ReviewRepositoryImplTest` › `V33 refuses a rating whose numbers don't add up, …` |
| — | نظر کس دیگه | ویرایش و حذف زیر قفل و فقط با `userId` ِ خود نویسنده؛ مال دیگری همون `404` ِ ناموجود | `WebV1MyReviewsOnDatabaseTest` › `edits - … someone else's 404` (ردیف ذخیره‌شده هم چک می‌شه)، تست حذف ِ نظر دیگری |
| 1 | بالا رفتن دسترسی | چهار route ِ پنل `[ADMIN, SUPPORT]` | `RouteGuardsTest` (هر route با کانترکت)؛ `WebV1AdminReviewsOnDatabaseTest` › `OPERATOR and CATALOG_MANAGER 403 - ADMIN and SUPPORT in` |

---

## خرابکاری عمدی

| نگهبان | چی برداشته شد | تست(هایی) که قرمز شد |
|---|---|---|
| مالکیت در ویرایش | `?.takeIf { it.userId == command.userId }` از `UpdateMyReviewUc` | `WebV1MyReviewsOnDatabaseTest` › `edits - an APPROVED one 422 - … someone else's 404` |
| مالکیت در حذف | `?.takeIf { it.userId == command.userId }` از `DeleteMyReviewUc` | `WebV1MyReviewsOnDatabaseTest` › `an approved review deleted takes its stars out of the product's rating - a pending one deleted changes nothing` |
| `RatingBook` در حذف | `ratingBook.deleted(…)` از `DeleteMyReviewUc` | `WebV1MyReviewsOnDatabaseTest` › `an approved review deleted takes its stars out …`؛ `ReviewsConsistencyOnDatabaseTest` › `200 random steps …`؛ `WebV1AdminReviewsOnDatabaseTest` › `approval and the author's deletion at the same moment, 20 times …` (و تست ۴۰۹ با امتیازی که از تست قبلی ناجور مونده بود) |
| `RatingBook` در moderation | `ratingBook.changed(…)` از `ModerateReviewUc` | `ReviewsConsistencyOnDatabaseTest` › `200 random steps …`؛ در گام ۸.۵ سه تست `WebV1AdminReviewsOnDatabaseTest` |
| ایندکس یکتای نظر زنده | `UNIQUE` از `review_one_live_idx` (خود `V33`، موقتاً) | `ReviewRepositoryImplTest` › `a second live review of a product by one user is refused by review_one_live_idx …`، `a rejected review edited back to PENDING while another is live is refused by the index too`؛ `WebV1AdminReviewsOnDatabaseTest` › `a turned-down review approved while its author has another live one - 409, nothing changed` |
| چک «نظر خود» در رأی | `if (review.userId == command.userId) …` از `VoteReviewHelpfulUc` | `WebV1ProductReviewsOnDatabaseTest` › `a vote, the same vote again, taken back - one's own 422, a pending one 404, no login 401` |
| چک «تحویل‌شده» ِ برچسب خریدار (نام‌برده) | `if (!line.delivered) …` از `ReviewDomainService.purchaseLink` | `ReviewDomainServiceTest` › `named - … not delivered or cancelled 422`؛ `WebV1MyReviewsOnDatabaseTest` › `linked by name - … an order shipped but not delivered 422 …` |
| چک «تحویل‌شده» ِ وصل خودکار | `&& it.delivered` از پیدا کردن آخرین خرید | `ReviewDomainServiceTest` › `not named - the latest delivered of two orders is found by itself, a cancelled one never, none is no purchase` |
| دلیل رد فقط برای نویسنده و پنل | `?.takeIf { own \|\| who.panel }` از `Reviews` | **اول هیچ تستی قرمز نشد** (پایین)؛ با تست تازه: `WebV1ProductReviewsOnDatabaseTest` › `a turned-down review built for anyone but its author and the panel carries no reason - the wall behind the public list` |
| نقش‌های پنل | `Role.OPERATOR` به `requireRoles` ِ `webV1AdminReviews` (گام ۸.۵) | `WebV1AdminReviewsOnDatabaseTest` › `OPERATOR and CATALOG_MANAGER 403 - ADMIN and SUPPORT in` |

---

## چیزهایی که موقع کار پیدا شد

- **خرابکاری ِ «دلیل رد فقط برای نویسنده» هیچ تستی رو قرمز نکرد** (گام ۸.۶): از routeها هیچ‌کس جز نویسنده نظر ردشده رو نمی‌بینه (لیست عمومی فقط تأییدشده‌ها، و تأیید دلیل رو پاک می‌کنه)، پس این چک دیوار دومه که هیچ مسیری بهش نمی‌رسید. یه تست مستقیم روی `Reviews` اضافه شد.
- **اسکریپت خرابکاری با چند فیلتر روی یه task فقط آخری رو اجرا می‌کرد** (`:test --tests A :test --tests B`): دو خرابکاری ِ این گام اول فقط کلاس آخرشون رو نشون دادن. اسکریپت حالا فیلترهای یه task رو با هم می‌فرسته و هر دو دوباره رفته شدن (جدول بالا).
- **خرابکاری ِ مالکیت در ویرایش اول قرمز نمی‌کرد** (گام ۸.۲): route بعد از ویرایش نظر رو با `GetMyReviewUc` ِ فقط‌مال‌خود می‌خونه، پس جواب همچنان `404` بود ولی ردیف کس دیگه عوض شده بود. تست حالا ردیف ذخیره‌شده رو هم چک می‌کنه، و یه تست حذف ِ نظر دیگری اضافه شد.
- **کانترکت `409` ِ moderation رو نداشت** (گام ۸.۵): تأیید ِ نظر ردشده وقتی نویسنده بعدش نظر تازه‌ای نوشته، دو نظر زنده می‌ساخت؛ ایندکس یکتا جلوش رو می‌گیره و حالا کانترکت هم می‌گه (تصمیم ۴۹).
- **شمارش کوئری لیست پنل ۳ ← ۴ شد** (گام ۸.۴): امتیاز همه‌ی کارت‌های صفحه با یه خوندن، نه join در کوئری لیست.
- **محیط این ماشین:** سرویس Postgres ِ محلی رمز `kiva` رو قبول نمی‌کنه، پس تست‌ها روی یه Postgres ِ موقت (پورت ۵۴۳۳، `KIVA_TEST_DB_URL`) رفتن.

---

## دور واقعی روی سرور (مونده برای تو)

اینجا سرور، پنل و kiva-fe در دسترس نبودن؛ همین مسیرها با تست‌های route روی routeهای واقعی رفته شدن. دور واقعی:

1. `V33` روی سرور اجرا می‌شه (ستون `ratingAvg` ِ `productqm_tbl` حذف می‌شه؛ هیچ کدی دیگه نمی‌خوندش).
2. kiva-fe: با یه حساب برای یه محصول نظر بنویس ← «نظرت ثبت شد» ← نظر `PENDING` فقط برای خودت اول لیست.
3. پنل (`SUPPORT`): صف نظرها ← `pendingCount` روی منو ← تأیید با «پاسخ کیوا» ← کارت محصول در لیست و صفحه‌ی محصول امتیاز و «پاسخ کیوا» رو نشون می‌دن.
4. یه حساب دیگه: «مفید بود» ← شمارنده ← پس گرفتن.
5. پنل: رد ِ همون نظر با دلیل ← امتیاز برمی‌گرده به صفر ← «نظرهای من» ِ نویسنده دلیل رو نشون می‌ده ← ویرایش ← دوباره در صف.
6. یه سفارش تحویل‌شده: «نظر بده» کنار ردیف ← نظر با برچسب «خریدار» ← «نظر بده» می‌ره و «منتظر نظر تو» خالی می‌شه.
7. پنل با `OPERATOR`: منوی نظرها نیست و route ها `403`.
