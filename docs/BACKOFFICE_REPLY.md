# جواب بک‌اند به «کارهای باز» ِ پنل (kiva-backoffice-fe)

> درباره‌ی: بخش «از بک‌اند بپرس / درخواست بده» در `docs/PLAN.md` ِ پنل (جلسه‌ی ۴، ۳ آبان ۱۴۰۵)، و دو جای دیگه‌ی همون سند که گفته «از بک‌اند پرسیده شود» (فرض mock درباره‌ی پیش‌نویس بلاگ، و کلید تکراری yml).
> نتیجه: کانترکت **1.22.0** (`api/kiva-openapi.yml` ِ بک‌اند). هیچ endpoint ای حذف نشده و هیچ فیلدی تغییر شکل نداده؛ فقط چیزهایی اضافه شده.

## خلاصه

| # | درخواست | جواب |
|---|---|---|
| ۱ | `GET /admin/blog/authors` | ✅ **ساخته شد** |
| ۱ | `GET /admin/blog/categories` | ❌ **لازم نیست**: `GET /blog/categories` همه‌ی دسته‌ها رو می‌ده |
| ۲ | کد جدا برای حساب حذف‌شده در `GET /admin/customers/{id}` | ✅ **اصلاح شد** (با اسم `CUSTOMER_DELETED`) |
| ۳ | `Review.product` در `GET /admin/reviews` همیشه `null` | ✅ **اصلاح شد** |
| ۴ | کلید تکراری `404` در yml | ✅ **اصلاح شد**، به‌علاوه‌ی یه کلید تکراری دیگه |
| ۵ | origin ِ پنل برای `refresh`/`logout` | ✔️ **از قبل درست بود**؛ تغییری لازم نشد |
| — | فرض mock: `GET /blog/posts/{id}` با توکن کارمند، پیش‌نویس رو هم بده | ❌ **قبول نشد**؛ از 1.21.0 کارش با `GET /admin/blog/posts/{postId}` ـه |

---

## ۱. نویسنده‌ها و دسته‌های بلاگ

### نویسنده‌ها: ساخته شد ✅

```
GET /admin/blog/authors        (ADMIN، CONTENT_EDITOR)
200 [
  { "id": 1, "name": "تیم کیوا", "title": "مجله‌ی کیوا", "initial": "ت", "avatarUrl": null, "bio": "…", "isDefault": true },
  { "id": 2, "name": "سارا", "title": "تیم استایل", "initial": "س", "avatarUrl": null, "bio": null, "isDefault": false }
]
```

- همه‌ی نویسنده‌ها میان، چه مقاله‌ی منتشرشده داشته باشن چه نه. ترتیب: اول نویسنده‌ی پیش‌فرض، بعد به ترتیب ساخته شدن.
- شِمای تازه‌ی `AdminBlogAuthor` = همه‌ی فیلدهای `BlogAuthor` به‌علاوه‌ی `isDefault`. `isDefault` همون نویسنده‌ایه که مقاله‌ی بدون `authorId` می‌گیره؛ تو انتخاب‌گر کنارش بنویس «پیش‌فرض».
- نقش دیگه ← `403 FORBIDDEN`.

**چرا قبول شد:** فرم مقاله `authorId` می‌گیره، ولی تا الان هیچ راهی نبود که لیست نویسنده‌ها رو بگیری. جمع کردن نویسنده‌ها از روی مقاله‌های عمومی، نویسنده‌ای رو که هنوز مقاله‌ی منتشرشده نداره نشون نمی‌ده. پس اون نویسنده هیچ‌وقت قابل انتخاب نمی‌شد.

**چی ساخته نشد:** endpoint ِ ساخت، ویرایش یا حذف نویسنده. نویسنده‌ها مثل قبل با SQL ساخته و عوض می‌شن. این تصمیم فاز ۱۵ ِ بک‌انده و عوض نشده.

### دسته‌ها: لازم نیست ❌

endpoint ِ تازه‌ای ساخته نشد، چون `GET /blog/categories` از اول همون چیزی رو می‌ده که پنل لازم داره:

- **همه‌ی** دسته‌ها رو می‌ده، به ترتیب نمایش، **حتی دسته‌ای که هیچ مقاله‌ای نداره** (با `postCount = 0`).
- `postCount` فقط مقاله‌های منتشرشده رو می‌شمره. برای فرم مهم نیست و فقط برای نمایشه.

یه `GET /admin/blog/categories` فقط همین لیست رو با یه آدرس دیگه برمی‌گردوند. دسته‌ها هم مثل نویسنده‌ها endpoint ِ نوشتن ندارن و با SQL ساخته می‌شن.

**کاری که پنل باید بکنه:** هیچی. همون `GET /blog/categories` درسته. فقط یادداشت «دسته‌ها از بلاگ عمومی خونده می‌شن» رو از «کارهای باز» بردارید، چون شکاف نیست.

---

## ۲. حساب حذف‌شده در صفحه‌ی مشتری: اصلاح شد ✅

```
GET /admin/customers/{customerId}
  شناسه‌ی ناموجود یا بدشکل  ← 404  { "code": "NOT_FOUND", … }
  حساب حذف‌شده              ← 404  { "code": "CUSTOMER_DELETED", "message": "حساب این مشتری حذف شده", … }
```

- کد تازه‌ی `CUSTOMER_DELETED` به enum ِ `ErrorCode` اضافه شد. وضعیت HTTP همون `404` می‌مونه.
- شناسه‌ی حساب حذف‌شده هنوز در `AdminOrder.customer.id`، `AdminReturnRequest.customer.id` و `customerId` ِ نظرها هست. پس پنل ممکنه به این صفحه لینک بده، و حالا می‌تونه «این حساب حذف شده» نشون بده، نه «پیدا نشد».
- رفتار بقیه‌ی جاها همون قبلیه: حساب حذف‌شده در `GET /admin/customers` نمیاد، و `PUT /admin/customers/{id}/roles` برای اون `404 NOT_FOUND` می‌ده.

**چرا اسمش `CUSTOMER_DELETED` ـه، نه `CUSTOMER_NOT_FOUND`:** چیزی که پنل لازم داره تشخیص «حذف شده» ست. اسم کد باید خودِ همین حالت رو بگه. شناسه‌ی ناموجود همون `NOT_FOUND` ِ عمومی می‌مونه، مثل بقیه‌ی مسیرهای «یکی» در پنل (مثلاً `GET /admin/blog/posts/{postId}`). اگه `CUSTOMER_NOT_FOUND` می‌ساختیم، باز هم یه کد دیگه برای «حذف‌شده» لازم بود.

**کاری که پنل باید بکنه:** `CUSTOMER_DELETED` رو به `ERROR_BEHAVIOUR` ِ صفحه‌ی مشتری اضافه کنید (پیام «حساب حذف‌شده»، بدون دکمه‌ی تلاش دوباره). mock هم همین کد رو برای حساب حذف‌شده برگردونه.

---

## ۳. اسم محصول در صف نظرها: اصلاح شد ✅

در پنل، `Review.product` حالا همیشه پره:

```
GET  /admin/reviews                         → items[].product
POST /admin/reviews/{reviewId}/moderation   → product
PUT  /admin/reviews/{reviewId}/reply        → product
product = { id, slug, sku, name, imageUrl, colorKey: null }   (شِمای ProductRef)
```

- محصول با **هر وضعیتی** که الان داره میاد: پیش‌نویس، بایگانی‌شده یا ناموجود. پس محصولی که از فروشگاه برداشته شده هم اسم داره، نه فقط شناسه.
- بیرون از پنل همون قبلیه: فقط «نظرات من» `product` داره و لیست عمومی نظرهای محصول `null` می‌ده.
- همه‌ی صفحه با یه خوندن پر می‌شه، نه یه کوئری برای هر نظر.

**کاری که پنل باید بکنه:** خوندن اسم‌ها از `GET /products?ids=` رو در صفحه‌ی نظرها بردارید و از `review.product` استفاده کنید.

---

## ۴. کلیدهای تکراری yml: اصلاح شد ✅

- `'404'` ِ تکراری در `PUT /reviews/{reviewId}/helpful` برداشته شد.
- **یه مورد دیگه هم پیدا شد که شما بهش نرسیدید:** در `BlogPostUpsertRequest.blocks.items`، کلید `description` دو بار اومده بود. js-yaml در اولین خطا می‌ایسته، برای همین فقط اولی رو دیدید. دو متن توی یه `description` یکی شدن.
- حالا کل فایل با یه parser ِ سخت‌گیر (snakeyaml با `allowDuplicateKeys = false`) بدون خطا خونده می‌شه، و همه‌ی ۱۳۶۹ `$ref` پیدا می‌شن. `js-yaml` بدون `json: true` هم باید کار کنه.

---

## ۵. origin ِ پنل برای `refresh`/`logout`: از قبل درست بود ✔️

تغییری در کد لازم نشد:

- **توسعه:** `http://localhost:3200` و `http://127.0.0.1:3200` از قبل در originهای پیش‌فرض بک‌اند هستن. `/auth/refresh` و `/auth/logout` از همین لیست پیروی می‌کنن.
- **پروداکشن:** دامنه‌ی پنل **`admin.kivashop.ir`** ـه، نه `admin.kiva.ir`. سایت روی `kivashop.ir` و API روی `api.kivashop.ir` ـه. این origin در `kiva.env.example` هست و موقع انتشار پنل روی سرور (گام ۶.۱ ِ `LAUNCH.md`) به `KIVA_ALLOWED_ORIGINS` ِ سرور اضافه می‌شه. تا اون گام، پنل روی سرور نیست.
- کوکی `kiva_rt` `SameSite=Strict` ـه. `admin.kivashop.ir` و `api.kivashop.ir` یه «سایت»‌ان، پس کوکی فرستاده می‌شه.
- قبل از انتشار، originهای `localhost` از سرور برداشته می‌شن و چک‌لیست انتشار این رو چک می‌کنه. پس پنل محلی به API ِ واقعی وصل نمی‌شه؛ با mock یا بک‌اند محلی تست کنید.

**کاری که پنل باید بکنه:** در `.env` ِ پروداکشن پنل، `NEXT_PUBLIC_API_URL=https://api.kivashop.ir/api/v1/`. هر جای سند که `admin.kiva.ir` نوشته، `admin.kivashop.ir` بشه.

---

## ۶. فرض mock درباره‌ی پیش‌نویس بلاگ: قبول نشد ❌

`PLAN.md` (جلسه‌ی ۱، فاز ۵) فرض کرده بود که `GET /blog/posts/{id}` با توکن کارمند، مقاله رو در هر وضعیتی برمی‌گردونه.

**این‌طوری نیست و نمی‌شه:**

- `GET /blog/posts/{idOrSlug}` یه endpoint ِ عمومی با کش ِ عمومیه (`Cache-Control: public`، `ETag`). اگه جوابش به توکن بستگی داشت، یه پیش‌نویس می‌تونست در کش بمونه و به همه نشون داده بشه.
- برای همین از کانترکت 1.21.0 دو endpoint ِ پنل هست: `GET /admin/blog/posts` و `GET /admin/blog/posts/{postId}`. همه‌ی وضعیت‌ها رو برمی‌گردونن، و `GET /blog/posts/{id}` برای پیش‌نویس، زمان‌بندی‌شده و بایگانی همیشه `404` می‌ده، با توکن یا بی توکن.

پنل در فاز ۱۳.۱ خودش به endpointهای ادمین رفته، پس فقط **mock** باید درست بشه: `GET /blog/posts/{id}` ِ mock با توکن کارمند هم پیش‌نویس رو برنگردونه (`404`)، تا mock با بک‌اند واقعی یکی باشه.

---

## چی در کانترکت 1.22.0 عوض شد (برای diff)

| کجا | تغییر |
|---|---|
| `info.version` | `1.21.0` ← `1.22.0` |
| `paths` | تازه: `GET /admin/blog/authors` (`x-roles: [ADMIN, CONTENT_EDITOR]`) |
| `components.schemas` | تازه: `AdminBlogAuthor` (`BlogAuthor` + `isDefault`) |
| `ErrorCode` | تازه: `CUSTOMER_DELETED` |
| `GET /admin/customers/{customerId}` | توضیح: حساب حذف‌شده ← `404 CUSTOMER_DELETED`، ناموجود ← `404 NOT_FOUND` |
| `Review.product` | توضیح: در «نظرات من» **و در پنل** پره |
| `PUT /reviews/{reviewId}/helpful` | `'404'` ِ تکراری برداشته شد |
| `BlogPostUpsertRequest.blocks.items` | `description` ِ تکراری یکی شد |

## کارهای پنل، یه‌جا

1. کانترکت 1.22.0 رو جای `kiva-openapi.yml` بذارید.
2. فرم مقاله: انتخاب‌گر نویسنده از `GET /admin/blog/authors` (با «پیش‌فرض»). جمع کردن نویسنده‌ها از روی مقاله‌ها رو بردارید.
3. دسته‌های بلاگ: همون `GET /blog/categories`. یادداشت شکاف رو بردارید.
4. صفحه‌ی مشتری: `CUSTOMER_DELETED` ← «این حساب حذف شده».
5. صف نظرها: اسم و عکس محصول از `review.product`. خوندن `GET /products?ids=` رو بردارید.
6. mock:
   - `GET /admin/blog/authors`
   - `product` در جواب‌های نظرِ پنل
   - `CUSTOMER_DELETED` برای حساب حذف‌شده
   - `404` برای پیش‌نویس در `GET /blog/posts/{id}` حتی با توکن
7. `admin.kiva.ir` ← `admin.kivashop.ir` در سندها و env.

راهنماهای بک‌اند هم به‌روز شدن: `docs/FRONTEND_BLOG.md` (نویسنده‌ها و دسته‌ها)، `docs/FRONTEND_REVIEWS.md` (بخش ۵) و `docs/FRONTEND_ACCOUNT.md` (حساب حذف‌شده در پنل).
