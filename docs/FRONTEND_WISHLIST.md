# علاقه‌مندی‌ها و سربرگ حساب: راهنمای فرانت (kiva-fe)

> بک‌اند: برنچ `wishlist`، فاز ۹ ِ `COMPLETION_ROADMAP.md` (گام‌های ۹.۰ تا ۹.۵)، ۳۰ مهر ۱۴۰۵. مرجع دقیق شکل درخواست‌ها و پاسخ‌ها کانترکت `api/kiva-openapi.yml` (نسخه‌ی 1.15.0) است؛ بخش ۱۴.۷ ِ نقشه‌ی راه تغییرات کانترکت این فاز رو فهرست کرده، و تصمیم‌های بخش ۱۴.۲ دلیل هر قانون رو.

## خلاصه

- علاقه‌مندی فقط برای کاربر واردشده روی سرور نگه داشته می‌شه. **مهمان قلب‌هاش رو در حافظه‌ی مرورگر نگه می‌داره** و کارت‌هاش رو با `GET /products?ids=…` می‌خونه. endpoint ِ ادغام نداریم: **بعد از ورود برای هر قلب محلی یه `PUT /me/wishlist/items/{productId}` بزن** (idempotent‌ه) و حافظه‌ی محلی رو پاک کن.
- حداکثر **۲۰۰ محصول**. محصولی که از سایت برداشته شده دیده نمی‌شه ولی جاش رو نگه می‌داره؛ اگه دوباره منتشر بشه برمی‌گرده.
- قلب روی کارت‌ها (`isWishlisted`): روی لیست محصولات، صفحه‌ی محصول، محصولات مرتبط و ردیف‌های سبد، برای کاربر واردشده `true`/`false` و برای مهمان `null`. روی پاسخ‌های کش‌شده (`/home`) **همیشه `null`**: اونجا قلب‌ها رو از `GET /me/wishlist/ids` روشن کن.
- **توکن منقضی روی لیست و صفحه‌ی محصول `401` می‌گیره، نه پاسخ مهمان**: اول توکن رو تازه کن، بعد دوباره بخون (مثل لیست نظرها).

## ۱. قلب

```js
PUT    /me/wishlist/items/{productId}   { "colorKey": "lilac" }      // بدنه اختیاری
DELETE /me/wishlist/items/{productId}
200 { productId, wishlisted, count, message }
GET    /me/wishlist/ids                 { productIds: [3, 7, 12], count: 3 }
```

- `colorKey` رنگیه که کاربر روی صفحه می‌دید؛ بفرستش. اگه محصول اون رنگ رو نداره ← `400 COLOR_NOT_FOUND` روی `colorKey`؛ کلید بدشکل ← `400 COLOR_KEY_INVALID`.
- دوباره زدن قلب فقط رنگ رو عوض می‌کنه؛ «از وقتی ذخیره کردی» از اولین بار حساب می‌شه.
- محصول منتشرنشده ← `404 PRODUCT_NOT_FOUND`؛ لیست پر ← `422 WISHLIST_LIMIT_REACHED` با `meta.limit = 200` («لیست علاقه‌مندی‌هات پر شده»).
- حذف همیشه `200` با `wishlisted = false` (حتی اگه نبود). `count` برای بج هدره (فقط دیده‌شده‌ها). `message` آماده‌ی toast.

## ۲. صفحه‌ی «علاقه‌مندی‌ها»

```js
GET    /me/wishlist?page=1&size=48      { items: [WishlistItem], meta, count, inStockCount, share }
DELETE /me/wishlist                     204
```

- تازه‌ترین ذخیره اول؛ صفحه ۴۸، حداکثر ۱۰۰. `count` و `inStockCount` روی کل لیسته، نه این صفحه.
- هر قلم: `product` (همون کارت لیست، به رنگ ذخیره‌شده)، `addedAt`، `preferredColorKey`، `priceAtAdd`، و `priceDrop` (`amount`، `percent`، `label` ِ آماده مثل «از وقتی ذخیره کردی ۱۵٪ ارزون‌تر شده»). افت زیر ۱٪ یا گرون‌تر شدن ← `null`.
- دکمه‌ی «افزودن همه‌ی موجودها به سبد ({inStockCount})».
- `share` لینک فعاله (یا `null`)؛ با پرچم `features.wishlistShare` ِ `/config` خاموش، همیشه `null` و دکمه‌ی اشتراک رو نشون نده.
- پاک کردن کل لیست لینک اشتراک رو باطل نمی‌کنه (همون لینک، لیست خالی).

## ۳. علاقه‌مندی و سبد

```js
POST /me/wishlist/add-to-cart          { "productIds": [3, 7] }      // بدنه اختیاری: همه
200 { cart, addedCount, skipped: [{ productId, name, reason }], message }
POST /cart/items/{itemId}/move-to-wishlist      200 CartMutationResponse (با removedItem)
```

- «همه به سبد» از هر محصول **یه عدد** می‌بره: رنگ ذخیره‌شده اگه موجوده، وگرنه اولین رنگ موجود. `skipped` یه ردیف برای هر محصول: `OUT_OF_STOCK`، `UNAVAILABLE`، `ALREADY_AT_MAX` (سبد همین الان سقفش رو داره). **قلم‌ها از علاقه‌مندی پاک نمی‌شن.** هیچ‌وقت به‌خاطر ناموجودی خطا نمی‌ده (`addedCount = 0` و پیام «هیچ کیفی به سبد اضافه نشد»).
- `productIds` حداکثر ۲۰۰ (`400 TOO_MANY_ITEMS`)؛ شناسه‌ای که تو لیست نیست نادیده.
- «انتقال» فقط با ورود (مهمان ← `401`، دکمه رو برای مهمان نشون نده). قلم با همون رنگ به لیست می‌ره؛ لیست پر ← `422 WISHLIST_LIMIT_REACHED` و **قلم تو سبد می‌مونه**. دکمه‌ی «برگردون» با `POST /cart/items` و `removedItem` قلم رو به سبد برمی‌گردونه (به علاقه‌مندی دست نمی‌زنه).

## ۴. لینک اشتراک

```js
POST   /me/wishlist/share     200 { token, url, createdAt }
DELETE /me/wishlist/share     204
GET    /wishlists/shared/{token}     200 { ownerDisplayName, items: [ProductSummary] }   // عمومی
```

- `POST` اگه لینک هست همون رو برمی‌گردونه، وگرنه می‌سازه. `url` آماده‌ی کپی و اشتراکه (`https://kivashop.ir/wishlist/shared/wl_…`). پرچم خاموش ← `422 WISHLIST_SHARE_DISABLED`.
- «غیرفعال کردن لینک» (`DELETE`) لینک قبلی رو همون لحظه ۴۰۴ می‌کنه؛ اشتراک بعدی لینک تازه می‌سازه.
- صفحه‌ی `/wishlist/shared/{token}`: `ownerDisplayName` فقط اسم کوچکه (یا «یکی از دوستان کیوا»)؛ کارت‌ها با `isWishlisted = null`. لینک خراب، باطل یا پرچم خاموش ← همه یه `404 NOT_FOUND`: صفحه‌ی «این لیست دیگه در دسترس نیست». پاسخ کش نمی‌شه.

## ۵. سربرگ حساب

```js
GET /me/dashboard   { greeting, user, stats, navCounts, activeReservation, pendingReviewCount }
```

- `greeting` آماده‌ست («سلام سارا» یا «سلام دوست عزیز»).
- هر عدد دقیقاً عدد صفحه‌ایه که بهش لینک می‌ده: `stats.ordersTotal` و `navCounts.orders` = `counts.all` ِ `/me/orders`؛ `stats.ordersActive` = `counts.current`؛ `stats.mediaReceived` = جمع `preShipmentMedia.count` ِ سفارش‌ها؛ `wishlistCount`/`navCounts.wishlist` = `count` ِ علاقه‌مندی؛ `navCounts.addresses` = تعداد آدرس‌ها؛ `navCounts.reviews` = `counts.total` ِ «نظرهای من»؛ `pendingReviewCount` = تعداد «منتظر نظر تو»؛ `activeReservation` = همون `/me/reservation` (نبود ← `null`).
- بعد از هر کاری که یکی از این عددها رو عوض می‌کنه (قلب، سفارش، نظر، آدرس) سربرگ رو دوباره بخون، یا عدد صفحه‌ی خودش رو جایگزین کن.
