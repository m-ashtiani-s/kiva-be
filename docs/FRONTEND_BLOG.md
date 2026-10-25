# مجله‌ی کیوا (بلاگ): راهنمای فرانت (kiva-fe و پنل)

> بک‌اند: برنچ `blog`، فاز ۱۵ ِ `COMPLETION_ROADMAP.md` (گام‌های ۱۵.۰ تا ۱۵.۵)، ۳ آبان ۱۴۰۵. مرجع دقیق کانترکت `api/kiva-openapi.yml` (نسخه‌ی 1.21.0) است؛ بخش ۲۰.۷ ِ نقشه‌ی راه تغییرات کانترکت و بخش ۲۰.۲ دلیل هر قانون.

## خلاصه

- فروشگاه پنج endpoint ِ عمومی داره: دسته‌ها، لیست (با مقاله‌ی منتخب)، صفحه‌ی مقاله، مرتبط‌ها و «این مطلب مفید بود؟». چهار تای اول کش می‌شن (`ETag`، `Cache-Control: public, max-age=300`).
- مجله فقط چیزی رو نشون می‌ده که **الان** دیده می‌شه: `PUBLISHED` با `publishedAt` ِ گذشته. پیش‌نویس، بایگانی و زمان‌بندی‌شده در فروشگاه همه‌جا `404` یا غایبن.
- پنل پنج endpoint داره (`ADMIN` و `CONTENT_EDITOR`): لیست، یکی، ساخت، ویرایش (جایگزینی کامل)، حذف. پاسخ ساخت و ویرایش همون `AdminBlogPost` ِ `GET` ـه.
- دسته‌ها و نویسنده‌ها endpoint ندارن (با SQL ساخته می‌شن). نویسنده نذاری ← «تیم کیوا».

## ۱. فروشگاه (kiva-fe)

### دسته‌ها: چیپ‌های بالای صفحه‌ی مجله

```js
GET /blog/categories
200 [ { id, slug: "style", name: "استایل", postCount: 4 }, … ]   // به ترتیب نمایش
```

`postCount` فقط مقاله‌های دیده‌شده رو می‌شمره. چیپ با `postCount = 0` رو می‌تونی کم‌رنگ یا پنهان کنی.

### لیست

```js
GET /blog/posts?category=style&q=کیف&tag=فرم بدن&page=1&size=9
200 { featured: BlogPostSummary | null, items: [BlogPostSummary], meta: PageMeta, emptyMessage: string | null }
```

| پارامتر | قانون |
|---|---|
| `category` | slug ِ دسته؛ ناشناخته ← لیست خالی (نه خطا) |
| `q` | حداکثر ۱۰۰ حرف (بیشتر ← `400 SEARCH_QUERY_TOO_LONG` روی `q`)؛ هر کلمه باید در عنوان، خلاصه، برچسب‌ها یا اسم دسته باشه؛ «ي» و «ی» یکی |
| `tag` | برابر یکی از برچسب‌ها (بعد از همون یکسان‌سازی) |
| `size` | ۱ تا ۳۰، پیش‌فرض ۹؛ بیرون ← `400 PAGE_SIZE_INVALID` |

- `featured` فقط **صفحه‌ی ۱** و فقط وقتی با فیلتر جوره. مقاله‌ی منتخب (وقتی با فیلتر جوره) در `items` ِ **هیچ** صفحه‌ای نیست و در `meta.totalItems` هم شمرده نمی‌شه؛ پس صفحه‌بندی رو فقط از `meta` بساز و کارت منتخب رو جدا، بالای صفحه‌ی ۱ بذار.
- ترتیب: جدیدترین `publishedAt` اول.
- هیچ مقاله‌ای (نه منتخب، نه `items`) ← `emptyMessage` («مقاله‌ای با این مشخصات پیدا نشد.»)؛ همون متن رو نشون بده.

`BlogPostSummary`: `id`، `slug`، `title`، `excerpt` (خالی ← خود سرور از `lead` یا اولین پاراگراف می‌سازه)، `category`، `cover` (`MediaAsset` یا `null`: بدون کاور، ایلاستریشن با `coverBackground` ِ `#RRGGBB`)، `readingMinutes` (همیشه عدد؛ نذاشته باشن، از تعداد کلمه‌ها)، `publishedAt`، `author` (`name`، `title`، `initial` برای آواتار حرفی، `avatarUrl`، `bio`)، `isFeatured`، `tags`.

### صفحه‌ی مقاله

```js
GET /blog/posts/{idOrSlug}         // "12" یا "bag-by-body-shape"؛ فقط رقم = شناسه (رقم فارسی هم)
200 BlogPostDetail
404 NOT_FOUND                      // ناموجود، پیش‌نویس، بایگانی، هنوز نرسیده به زمانش
```

آدرس صفحه‌ی خودت `/blog/{slug}` باشه (همون `seo.canonicalUrl`).

| فیلد | چی |
|---|---|
| `lead` | پاراگراف درشت اول (متن ساده) |
| `blocks` | به ترتیب؛ پایین‌تر |
| `toc` | `[{ anchor: "s0", title }]` از تیترها؛ `id` ِ هر تیتر در صفحه = `anchor` اش |
| `breadcrumbs` | خانه / مجله / {دسته} / {عنوان}؛ آخری بدون لینک |
| `shareUrl`، `shareLinks` | آدرس مقاله، و لینک پیام‌رسان‌های فروشگاه با متن «{عنوان} {shareUrl}» آماده |
| `helpfulStats` | `{ helpfulCount, notHelpfulCount }` |
| `seo` | `title` (نذاشته باشن «{عنوان} \| مجله‌ی کیوا»)، `description`، `canonicalUrl`، `ogImageUrl` (نسخه‌ی ۱۲۰۰ ِ کاور)، `noIndex`، `jsonLd` (`Article` ِ آماده؛ همون رو در `<script type="application/ld+json">` بذار) |

**بلوک‌ها** (`BlogContentBlock`، همه‌ی فیلدها همیشه هستن و مال نوع‌های دیگه `null`):

| `type` | فیلدها | نمایش |
|---|---|---|
| `HEADING` | `anchor`، `text` | `<h2 id={anchor}>` |
| `PARAGRAPH` | `text` **یا** `html` | `html` از قبل پاک‌سازی شده (فقط تگ‌های مجاز، لینک نسبی هم)؛ با `dangerouslySetInnerHTML`/`v-html` بذار. `text` متن ساده‌ست |
| `QUOTE` | `text` | نقل‌قول |
| `TIP` | `title` (همیشه پر؛ نذاشته باشن «نکته کیوا»)، `text` یا `html` | جعبه‌ی «نکته‌ی کیوا» |
| `PRODUCT` | `product` (کارت محصول، `isWishlisted = null` چون پاسخ کش می‌شه) | کارت درون‌متنی؛ قلب رو از `GET /me/wishlist` خودت پر کن. محصولی که از فروشگاه رفته باشه بلوکش **اصلاً نمیاد** |
| `IMAGE` | `image` (`MediaAsset`)، `text` = زیرنویس (اختیاری) | `<figure>` |
| `LIST` | `items` | لیست نقطه‌دار |

### مرتبط‌ها: پایین مقاله

```js
GET /blog/posts/{postId}/related?limit=3     // limit ۱ تا ۱۲، پیش‌فرض ۳
200 [BlogPostSummary]                        // اول هم‌دسته‌ها، بعد بقیه؛ خود مقاله هرگز
400 VALIDATION_ERROR  (limit NUMBER_OUT_OF_RANGE، meta.min/max)
404 NOT_FOUND
```

### «این مطلب مفید بود؟»

```js
POST /blog/posts/{postId}/feedback   { helpful: true }  یا  { helpful: false, comment?: "مثال نداشت" }
204
```

- ورود **اختیاری**: توکن داری بفرست (رأی به اسم حساب)، نداری هم مشکلی نیست (رأی به اسم IP، که خودش ذخیره نمی‌شه). توکن خراب یا منقضی ← `401`، نه رأی مهمان؛ مثل بقیه‌ی endpointهای ورود-اختیاری اول رفرش کن.
- هر کاربر یا هر IP یه رأی داره؛ رأی دوباره جایگزین قبلیه. پس دکمه‌ها رو بعد از رأی هم فعال بذار («نظرت رو عوض کردی؟»)، ولی شمارنده‌ی `helpfulStats` رو خودت +۱ نکن مگه اولین رأیه؛ ساده‌ترش اینه که فقط «ممنون!» نشون بدی.
- `comment` (حداکثر ۳۰۰، متن ساده) فقط با «نه چندان» نگه داشته می‌شه و فقط پنل می‌بینه؛ با `helpful: true` بی‌صدا نادیده.

| پاسخ | یعنی |
|---|---|
| `204` | ثبت شد |
| `400` | `helpful` نیومده (`FIELD_REQUIRED`)، `comment` بلند (`TEXT_TOO_LONG`) یا با نویسه‌ی کنترلی (`TEXT_INVALID`) |
| `404` | مقاله دیده نمی‌شه |
| `429 RATE_LIMITED` | بیش از ۳۰ رأی در ساعت از این IP؛ بی‌صدا رد شو |

## ۲. پنل (صفحه‌ی «مجله»)

نقش‌ها: `ADMIN` و `CONTENT_EDITOR`؛ بقیه `403`.

### لیست

```js
GET /admin/blog/posts?status=DRAFT&q=کیف&category=style&page=1&size=20
200 { items: [AdminBlogPost], meta }          // آخرین تغییر اول؛ blocks و toc در لیست خالی‌اند
400  status BLOG_POST_STATUS_INVALID  |  q SEARCH_QUERY_TOO_LONG
```

ستون وضعیت: `status` (`DRAFT`، `PUBLISHED`، `ARCHIVED`) و اگه `scheduled = true` برچسب «زمان‌بندی‌شده برای {publishedAt}». `unavailableProductIds` ِ غیرخالی ← آیکن هشدار.

### یکی (فرم ویرایش)

```js
GET /admin/blog/posts/{postId}
200 AdminBlogPost   // BlogPostDetail + status, scheduled, createdAt, createdBy, updatedBy, unavailableProductIds, feedbackComments
404 NOT_FOUND
```

- `blocks` **همه‌ی** بلوک‌ها، بلوک محصولی که دیگه در فروشگاه نیست هم (با `product = null`)؛ شناسه‌ش در `unavailableProductIds`. هشدار بده: «این محصول در سایت نیست و بلوکش به خواننده نشون داده نمی‌شه».
- برای پر کردن فرم از بلوک `PRODUCT`، `productId` رو از `unavailableProductIds` یا `product.id` بردار.
- `feedbackComments`: ۵۰ نظر آخر «مفید نبود» (`comment`، `createdAt`)، بی‌نام.

### ساخت و ویرایش

```js
POST /admin/blog/posts            → 201 AdminBlogPost
PUT  /admin/blog/posts/{postId}   → 200 AdminBlogPost      // جایگزینی کامل: همه‌ی فیلدها و همه‌ی بلوک‌ها از نو
{
  title, slug, excerpt?, lead?, categoryId, authorId?, coverMediaId?, coverBackground?, readingMinutes?,
  blocks: [ { type, text?, html?, title?, items?, productId?, imageMediaId? } ],
  tags?, isFeatured?, status?, publishedAt?, seo?: { title?, description?, noIndex? }
}
```

| فیلد | قانون |
|---|---|
| `title` | ۱ تا ۱۲۰ |
| `slug` | حروف کوچک انگلیسی، عدد و `-`، حداقل یه حرف (فقط عدد ← `SLUG_INVALID`)، ≤ ۱۰۰؛ تکراری ← `409 DUPLICATE_SLUG` |
| `excerpt` / `lead` | ≤ ۳۰۰ / متن ساده ≤ ۶۰۰ |
| `categoryId` | اجباری؛ ناموجود ← `BLOG_CATEGORY_NOT_FOUND` |
| `authorId` | نیومد ← «تیم کیوا»؛ ناموجود ← `BLOG_AUTHOR_NOT_FOUND` |
| `coverMediaId` | آپلود با `purpose = BLOG`، فقط عکس (`MEDIA_*`) |
| `coverBackground` | `#RRGGBB` |
| `readingMinutes` | ۱ تا ۶۰ یا `null` (خودکار) |
| `tags` | حداکثر ۱۰، هر کدوم ۱ تا ۳۰؛ تکراری حذف می‌شه |
| `isFeatured` | `true` ← منتخب قبلی خودکار از منتخب بودن درمیاد (همیشه حداکثر یکی) |
| `status` + `publishedAt` | نیومد ← `DRAFT`. `PUBLISHED` بدون تاریخ ← «الان» (ولی مقاله‌ای که قبلاً منتشر شده تاریخ اولش رو نگه می‌داره)؛ تاریخ آینده ← زمان‌بندی (خودکار در اون لحظه دیده می‌شه، تا ۵ دقیقه کش) |
| `seo` | `title` ≤ ۷۰، `description` ≤ ۱۷۰، `noIndex`؛ `canonicalUrl`/`ogImageUrl`/`jsonLd` رو سرور می‌سازه و ورودیشون نادیده‌ست |

**بلوک‌ها** (۱ تا ۱۰۰؛ برای هر نوع فقط فیلدهای خودش خونده می‌شه):

| `type` | لازم |
|---|---|
| `HEADING` | `text` ۱ تا ۱۲۰ (`anchor` رو سرور می‌سازه: `s0`، `s1`، …) |
| `PARAGRAPH` | دقیقاً یکی از `text` (≤ ۲۰۰۰) یا `html` (پاک‌سازی‌شده ≤ ۵۰۰۰)؛ هر دو یا هیچ ← `BLOG_BLOCK_TEXT_OR_HTML` |
| `QUOTE` | `text` ۱ تا ۵۰۰ |
| `TIP` | `title` ≤ ۶۰ (اختیاری)، و `text` یا `html` مثل پاراگراف |
| `PRODUCT` | `productId` ِ محصول منتشرشده (← `PRODUCT_NOT_FOUND`) |
| `IMAGE` | `imageMediaId` (`BLOG`، فقط عکس)، `text` ِ اختیاری ≤ ۲۰۰ زیرنویس |
| `LIST` | `items` ۱ تا ۲۰، هر کدوم ۱ تا ۳۰۰ |

خطاهای فرم همه با هم در `400 VALIDATION_ERROR` میان، هر کدوم با `field` (برای بلوک‌ها `blocks.{i}.text`، `blocks.3.productId` و …)؛ خطا رو زیر همون بلوک نشون بده. `html` قبل از ذخیره پاک‌سازی می‌شه (`<script>`، `onerror`، `javascript:` حذف؛ لینک نسبی مثل `/products?onSale=true` می‌مونه)؛ بعد از ذخیره، فرم رو از پاسخ پر کن تا ادیتور همون چیزی رو ببینه که ذخیره شده.

### حذف

```js
DELETE /admin/blog/posts/{postId}   → 204   (404 اگه نیست)
```

واقعاً پاک می‌شه، با رأی‌هاش. برای «فعلاً نشون نده» بهتره `status = ARCHIVED` پیشنهاد بدی. هر ساخت، ویرایش و حذف در لاگ تغییرات (`BLOG_POST_*`) می‌ره.

## ۳. چک‌لیست تست دستی

1. پیش‌نویس بساز؛ در پنل هست، `GET /blog/posts/{slug}` ِ فروشگاه `404`.
2. `PUBLISHED` با `publishedAt` ِ یه ساعت بعد؛ در پنل «زمان‌بندی‌شده»، در فروشگاه تا اون موقع نیست.
3. دو مقاله منتخب کن؛ فقط دومی منتخبه و در صفحه‌ی ۱ ِ لیست بالا میاد، نه در `items`.
4. یه محصول بلوک‌دار رو در پنل کاتالوگ بایگانی کن؛ صفحه‌ی مقاله بدون اون کارت، پنل با هشدار.
5. پاراگراف با `<script>` ذخیره کن؛ پاسخ بدون اسکریپت.
6. «مفید بود؟» رو از دو مرورگر بزن (یکی واردشده)؛ `helpfulStats` دو تا. نظر «نه چندان» در `feedbackComments` ِ پنل.
