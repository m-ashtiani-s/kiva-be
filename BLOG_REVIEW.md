# ریویوی مجله‌ی کیوا (بلاگ): فاز ۱۵ (گام‌های ۱۵.۰ تا ۱۵.۵) و جمع‌بندی کل نقشه

> برنچ `blog`، ۳ آبان ۱۴۰۵. نقشه‌ی راه: `COMPLETION_ROADMAP.md`، فاز ۱۵ (بخش ۲۰). راهنمای فرانت: `docs/FRONTEND_BLOG.md`.
> ردیف ۴ ِ بخش ۳ ِ نقشه‌ی راه (XSS ِ ذخیره‌شده) برای بلاگ، و خطرهای خود این فاز: مقاله‌ی منتشرنشده‌ای که در فروشگاه یا کش دیده بشه، دو منتخب، کارت محصولی که دیگه در فروشگاه نیست، رأی تقلبی، و IP ِ خواننده در دیتابیس. برای هر کدوم: دفاع، تست نگهبان، و نتیجه‌ی خرابکاری عمدی.

---

## خلاصه

| چی بررسی شد | نتیجه |
|---|---|
| `./gradlew test --continue` | ✅ **۳۱۹۷ تست** سبز، یه اجرای کامل بی هیچ قرمزی (فاز ۱۴: ۳۱۴۲). ۵ تست skip: تست‌های ابزار واقعی (`vips` و `ffmpeg` روی این ماشین نیستن) |
| خرابکاری عمدی | ✅ **هر ۱۹ تا قرمز کردن** (پایین‌تر) |
| «هر مسیر کانترکت یه route داره» | ✅ تست تازه‌ی `RouteGuardsTest › every operation of the contract has a route`: همه‌ی operationهای کانترکت 1.21.0 (پنل و فروشگاه) route دارن؛ با تغییر اسم یه route (خرابکاری ۱۷) قرمز می‌شه |
| قوانین لایه‌ها | ✅ `domain` (`BlogPost`، `BlogBlocks`، `BlogVote`، `BlogPolicy`) و `application` (UseCaseها، `BlogPosts`، `BlogPostWriting`، پورت `VoterHashes`) هیچ import از Ktor، Exposed یا `javax.crypto` ندارن؛ HMAC در `HmacVoterHashes` ِ infrastructure |
| کانترکت | ✅ 1.21.0 (گام ۱۵.۰)؛ پاسخ‌ها با `ContractSchemas.propertiesOf` مقایسه می‌شن (`BlogPostSummary`، `BlogPostDetail`، `AdminBlogPost`، `BlogPostListResponse`) |
| migration | ✅ `V40__blog.sql` (چهار جدول، seed ِ پنج دسته و «تیم کیوا»، `blogpost_one_featured_idx`، `CHECK` ِ `voterKey` و «نظر فقط با نه»)؛ `V41__blog_audit_log.sql` (`BLOG_POST` در قید `auditlog_tbl`، جاافتاده از `V40`) |
| پیکربندی تازه | ⚠️ `KIVA_BLOG_VOTER_PEPPER` **اجباریه**: بدون اون اپ بالا نمیاد. قبل از deploy روی سرور (`read -rsp` و `kiva.env`) و در Run configuration ِ IntelliJ بساز: `openssl rand -base64 32` |
| N+1 | ✅ لیست، مرتبط‌ها و لیست پنل همه با چند کوئری ِ ثابت (دسته‌ها، نویسنده‌ها، عکس‌ها، کارت‌های محصول، رأی‌ها، کاربرها یه بار برای همه)؛ در لاگ Exposed ِ تست‌ها دیده شد |
| دور واقعی روی سرور با kiva-fe و پنل | ⬜ مونده برای تو (چک‌لیست بخش ۳ ِ `docs/FRONTEND_BLOG.md`) |
| merge به `main` و deploy | ⬜ منتظر تأیید تو (این برنچ از سر فاز ۱۴ ساخته شده و همه‌ی فازهای قبلی رو داره) |

---

## خطرهای این فاز

| خطر | دفاع | تست‌ها |
|---|---|---|
| XSS ِ ذخیره‌شده در پاراگراف یا «نکته» (ردیف ۴ ِ بخش ۳) | `html` ِ هر بلوک **قبل** از اندازه‌گیری و ذخیره از `HtmlSanitizer` (jsoup) رد می‌شه؛ لینک نسبی می‌مونه، `javascript:` نه. `lead`، `QUOTE`، `LIST` و نظرها متن ساده‌ان | `WebV1AdminBlogOnDatabaseTest › a paragraph's script and onerror are cleaned out - a relative link stays`؛ `BlogBlocksTest` |
| مقاله‌ی منتشرنشده در فروشگاه | «دیده‌شدن» = `PUBLISHED` و `publishedAt ≤ now`، یه جا در SQL (`seenAt`) برای لیست، منتخب، مرتبط‌ها و شمارش دسته، و یه جا در دامنه (`isVisibleAt`) برای صفحه‌ی مقاله و رأی. پیش‌نویس، بایگانی، زمان‌بندی‌شده و ناموجود یه جواب دارن (`404 NOT_FOUND`) | `WebV1BlogOnDatabaseTest › a post scheduled for an hour later is 404 and out of the list until then - …`، `› postCount leaves drafts and scheduled posts out - …`؛ `BlogRepositoriesImplTest$Posts › the shop sees published posts whose moment came, …` |
| کش ِ داده‌ی منتشرنشده | پاسخ عمومی هیچ‌وقت چیز دیده‌نشده نداره، پس `ETag` ِ پنج‌دقیقه‌ای چیزی لو نمی‌ده؛ `404` هیچ‌وقت کش نمی‌شه (`respondResultCacheable`) | `WebV1BlogOnDatabaseTest › postCount leaves drafts and scheduled posts out - the answers are cached - 304 with If-None-Match` |
| دو منتخب | `isFeatured = true` اول بقیه رو در همون تراکنش `false` می‌کنه، بعد ذخیره؛ `blogpost_one_featured_idx` دیوار دوم | `WebV1AdminBlogOnDatabaseTest › featuring a second post unfeatures the first`؛ `BlogRepositoriesImplTest$Posts › featuring a second post unfeatures the first - and the index refuses two featured` |
| منتخب دو بار در لیست، یا صفحه‌بندی جابه‌جا | منتخب (اگه با فیلتر جوره) از `items` و شمارش **همه‌ی** صفحه‌ها بیرونه؛ خودش فقط در صفحه‌ی ۱ | `WebV1BlogOnDatabaseTest › the featured post only on page one - out of the items and the count - …` |
| کارت محصولی که در فروشگاه نیست | موقع ذخیره: محصول منتشرشده ← وگرنه `400 PRODUCT_NOT_FOUND` روی `blocks.{i}.productId`. بعداً برداشته بشه: بلوکش از صفحه‌ی فروشگاه حذف، در پنل با `unavailableProductIds` | `WebV1AdminBlogOnDatabaseTest › a slug taken is 409 - only digits 400 - a draft product in a block 400 on its place - …`؛ `WebV1BlogOnDatabaseTest › the id and the slug are the same post - … - and a product taken out of the shop leaves no block` |
| رأی تقلبی (پر کردن «مفید بود») | یه رأی برای هر (مقاله، رأی‌دهنده)، رأی دوباره جایگزین (کلید اصلی `postId, voterKey`)؛ سقف `blog-feedback` ِ ۳۰ در ساعت برای هر IP؛ فقط مقاله‌ی دیده‌شده | `WebV1BlogOnDatabaseTest › a guest votes once per address - again replaces it - …`، `› helpful is required - … - the 31st vote in an hour is 429` |
| IP ِ خواننده در دیتابیس (داده‌ی شخصی) | `ip:` + HMAC-SHA256 ِ آدرس با `kiva.blog.voterPepper` (کلید بیرون از دیتابیس؛ SHA-256 ِ ساده با امتحان همه‌ی IPv4ها برمی‌گرده)؛ خود IP هیچ‌جا نوشته نمی‌شه، در لاگ هم نه (`Cmd.toString`) | `WebV1BlogOnDatabaseTest › the address itself is nowhere in the database - only its keyed hash`؛ `AppConfigTest › without the blog's voter pepper the app doesn't start - …` |
| نظر «آره» که ذخیره بشه | `BlogVote`: با «آره» نظر دور ریخته می‌شه؛ `CHECK (comment IS NULL OR NOT helpful)` دیوار دوم | `BlogVoteTest`؛ `WebV1BlogOnDatabaseTest › a guest votes once per address - …` |
| رأی کاربر بعد از حذف حساب | `AccountEraser` رأی‌های `u:{id}` رو پاک می‌کنه (تصمیم ۱۸) | `AccountErasureOnDatabaseTest › everything only hers is gone - …, blog votes` |
| ادیتوری که پنل دیگه رو باز کنه، یا اپراتوری که مقاله بنویسه | `requireRoles(ADMIN, CONTENT_EDITOR)` روی هر پنج route ِ پنل، برابر `x-roles` ِ کانترکت | `WebV1AdminBlogOnDatabaseTest › an OPERATOR is 403 on all five routes - ADMIN may`؛ `RouteGuardsTest` |
| slug ِ تکراری همزمان | `isSlugTaken` قبل از ذخیره، و قید یکتا (`UniqueTakenException` ← `409`) برای لحظه‌ی همزمان | `WebV1AdminBlogOnDatabaseTest › a slug taken is 409 - …`؛ `BlogRepositoriesImplTest` |

---

## خرابکاری عمدی

یه اسکریپت هر نگهبان رو برداشت، تست‌های مربوط رو اجرا کرد و برش گردوند؛ **هر ۱۹ تا قرمز کردن**:

| # | خرابکاری | تستی که قرمز شد |
|---|---|---|
| ۱ | `html` ِ پاراگراف بدون `HtmlSanitizer` | `WebV1AdminBlogOnDatabaseTest › a paragraph's script and onerror are cleaned out - …` |
| ۲ | فیلتر دیده‌شدن فقط با `status` (بدون `publishedAt ≤ now`) | `WebV1BlogOnDatabaseTest › a post scheduled for an hour later is 404 …`، `› postCount leaves drafts and scheduled posts out …`؛ `BlogRepositoriesImplTest$Posts › the shop sees published posts whose moment came …`، `› the featured post only while the shop sees it …` |
| ۳ | صفحه‌ی مقاله بدون `isVisibleAt` | `WebV1BlogOnDatabaseTest › a post scheduled for an hour later is 404 …`، `› postCount leaves drafts and scheduled posts out …` |
| ۴ | ساخت مقاله‌ی منتخب بدون `unfeatureAllExcept` | `WebV1AdminBlogOnDatabaseTest › featuring a second post unfeatures the first` |
| ۵ | `unfeatureAllExcept` بی‌اثر (فقط قید یکتا) | همون، و `BlogRepositoriesImplTest$Posts › featuring a second post unfeatures the first - and the index refuses two featured` |
| ۶ | منتخب در `items` و شمارش هم | `WebV1BlogOnDatabaseTest › the featured post only on page one - …` |
| ۷ | بلوک محصول ِ برداشته‌شده در صفحه‌ی فروشگاه | `WebV1BlogOnDatabaseTest › the id and the slug are the same post - … - and a product taken out of the shop leaves no block` |
| ۸ | محصول پیش‌نویس در بلوک پذیرفته | `WebV1AdminBlogOnDatabaseTest › a slug taken is 409 - … - a draft product in a block 400 on its place - …` |
| ۹ | IP با SHA-256 ِ ساده به‌جای HMAC | `WebV1BlogOnDatabaseTest › the address itself is nowhere in the database - only its keyed hash` |
| ۱۰ | نظر «آره» نگه داشته | `WebV1BlogOnDatabaseTest › a guest votes once per address - …` |
| ۱۱ | رأی روی مقاله‌ی زمان‌بندی‌شده | `WebV1BlogOnDatabaseTest › helpful is required - … - a post the shop doesn't see is 404 - …` |
| ۱۲ | سقف `blog-feedback` برداشته (۱۰۰۰) | همون (`› … the 31st vote in an hour is 429`) |
| ۱۳ | مرتبط‌ها خود مقاله رو هم دارن | `WebV1BlogOnDatabaseTest › related - the same category first, …`؛ `BlogRepositoriesImplTest$Posts › related - its category first, …, never itself` |
| ۱۴ | مرتبط‌ها بدون «هم‌دسته اول» | همون دو تست |
| ۱۵ | `postCount` همه‌ی وضعیت‌ها رو می‌شمره | `WebV1BlogOnDatabaseTest › postCount leaves drafts and scheduled posts out …`؛ `BlogRepositoriesImplTest$Posts › posts the shop sees, counted by category` |
| ۱۶ | حذف حساب رأی‌های بلاگ رو نگه می‌داره | `AccountErasureOnDatabaseTest › everything only hers is gone - …, blog votes` |
| ۱۷ | یه route ِ کانترکت (`/related`) با اسم دیگه | `RouteGuardsTest › every operation of the contract has a route` |
| ۱۸ | `OPERATOR` به پنل بلاگ راه داره | `WebV1AdminBlogOnDatabaseTest › an OPERATOR is 403 on all five routes - ADMIN may`؛ `RouteGuardsTest › every admin endpoint takes exactly the roles of its x-roles in the contract` |
| ۱۹ | `BlogConfig.toString` با pepper | `AppConfigTest › without the blog's voter pepper the app doesn't start - … - it never shows when printed` |

---

## چیزهایی که عمداً همین‌طورن

- **بدون job ِ انتشار:** زمان‌بندی موقع خوندن حساب می‌شه (تصمیم ۳)؛ مقاله‌ی زمان‌بندی‌شده تا ۵ دقیقه (عمر کش) بعد از زمانش ممکنه هنوز در کش مرورگر یا CDN نباشه.
- **سقف رأی در حافظه‌ست** (مثل بقیه‌ی `RateLimit`ها): با restart از صفر؛ فقط سرعت‌گیره. یکتایی رأی خودش در دیتابیسه.
- **عوض کردن `KIVA_BLOG_VOTER_PEPPER`** هر مهمان رو رأی‌دهنده‌ی تازه می‌کنه (رأی‌های قبلی می‌مونن و شمرده می‌شن)؛ فقط یه رأی اضافه برای هر مهمان.
- **دسته و نویسنده با SQL** (تصمیم ۱): endpoint ِ پنل ندارن.

---

## جمع‌بندی کل نقشه (گام ۱۵.۵)

- **بخش ۳ (از چی محافظت می‌کنیم):** جلوی هر ۲۱ ردیف اسم تست‌های نگهبانش نوشته شد (بخش ۳.۱ ِ نقشه‌ی راه)؛ هر اسم با جستجو در `src/test` پیدا شد.
- **بخش ۰ (وضعیت فازها):** همه‌ی ۱۵ فاز در ریپو آماده‌ان، هر کدوم با ریویوی خودش. هیچ‌کدوم هنوز در `main` نیست؛ همه‌ی برنچ‌ها پشت سر هم ساخته شدن، پس merge ِ `blog` همه رو با هم میاره. merge و deploy (و `KIVA_BLOG_VOTER_PEPPER` روی سرور) با تو.
- **بخش ۲۹ (گزارش کار):** همه‌ی گام‌های ۱۵.۰ تا ۱۵.۵ ثبت شدن.
- **کانترکت و route:** تست تازه نشون می‌ده هیچ operation ِ کانترکت (پنل و فروشگاه) بدون route نیست.
