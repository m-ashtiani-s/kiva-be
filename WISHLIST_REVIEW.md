# ریویوی علاقه‌مندی‌ها و داشبورد: فاز ۹ (گام‌های ۹.۰ تا ۹.۵)

> برنچ `wishlist`، ۳۰ مهر ۱۴۰۵. نقشه‌ی راه: `COMPLETION_ROADMAP.md`، فاز ۹. راهنمای فرانت: `docs/FRONTEND_WISHLIST.md`.
> برای ردیف ۱۴ ِ بخش ۳ ِ نقشه‌ی راه و خطرهای خود این فاز (سقف لیست زیر درخواست‌های همزمان، «انتقال» ِ نیمه‌کاره، لو رفتن لیست یا صاحبش): دفاع کجاست، کدوم تست نگهبانشه، و نتیجه‌ی خرابکاری عمدی (نگهبان یه بار برداشته شد، تست قرمز شد، برگردونده شد).

---

## خلاصه

| چی بررسی شد | نتیجه |
|---|---|
| `./gradlew test --continue --rerun-tasks` | ✅ **۲۹۴۳ تست** سبز. ۵ تست skip: تست‌های ابزار واقعی (`vips` و `ffmpeg` روی این ماشین نیستن) |
| هشدار کامپایلر | ✅ صفر |
| قوانین لایه‌ها | ✅ `domain`، `common` و `application` هیچ import از Ktor، Exposed یا Koin ندارن |
| کانترکت | ✅ 1.15.0 (گام ۹.۰). پاسخ‌های علاقه‌مندی، اشتراک و داشبورد در تست‌های route با شِماهای کانترکت مقایسه می‌شن (`ContractSchemas`)؛ `RouteGuardsTest` ورود ِ اجباری ِ `/me/*` رو با کانترکت چک می‌کنه |
| محدودیت nginx | ✅ `/api/v1/wishlists/shared/` با ۳۰ در دقیقه و `burst=10` (`kiva-http.conf`، `api.kivashop.ir.conf`، جدول `LAUNCH.md`) |
| دور واقعی روی سرور با kiva-fe | ⬜ مونده برای تو (پایین‌تر) |
| merge به `main` و deploy | ⬜ منتظر تأیید تو (فازهای قبل هم هنوز merge نشدن؛ این برنچ از سر فاز ۸ ساخته شده) |

---

## ردیف‌های بخش ۳ و خطرهای این فاز

| # | خطر | دفاع | تست‌ها |
|---|---|---|---|
| 14 | حدس لینک لیست اشتراکی | توکن `wl_` + ۳۲ نویسه‌ی base62 از `SecureRandom` (حدود ۱۹۰ بیت)؛ باطل کردن فوری (ردیف پاک می‌شه)؛ ۴۰۴ ِ یکسان برای توکن بدشکل، ناموجود، باطل‌شده و پرچم خاموش؛ `Cache-Control: no-store`؛ ۳۰ درخواست در دقیقه برای هر IP در nginx؛ توکن هیچ‌وقت کامل لاگ نمی‌شه (۶ نویسه‌ی اول) | `WebV1WishlistShareOnDatabaseTest` › `a token one character short or long, not one at all, or nobody's - the same 404 every time`، `asked twice - one token - revoked, the old link 404 at once …`، `the flag off - sharing 422, the public list 404 …`؛ `WishlistTest` › `a share token is wl_ and 32 base62 characters …` |
| 14 | لو رفتن صاحب لیست | لیست عمومی فقط اسم کوچک (یا «یکی از دوستان کیوا»)؛ نه فامیلی، نه موبایل؛ فقط محصولات منتشرشده؛ `isWishlisted = null` | `WebV1WishlistShareOnDatabaseTest` › `the public list - the first name only, never a phone, published products only, no-store …` |
| — | رد شدن سقف ۲۰۰ با درخواست‌های همزمان | کل لیست یه Aggregate زیر قفل advisory ِ `wishlist(userId)` (قفل ۱۳)؛ سقف روی همه‌ی ردیف‌ها (پیش‌نویس‌های پنهان هم) | `WebV1WishlistOnDatabaseTest` › `20 adds at once onto 195 rows leave exactly 200 …`؛ `WishlistTest` › `the 201st product is WISHLIST_LIMIT_REACHED with the limit …` |
| — | «انتقال» ِ نیمه‌کاره | یه تراکنش (قفل سبد ۲، بعد لیست ۱۳)؛ اول افزودن به لیست، شکست ← کل تراکنش برمی‌گرده و قلم تو سبد می‌مونه | `WebV1WishlistCartOnDatabaseTest` › `a cart line moved - … a full list 422 and the line stays …` |
| — | محصول برداشته‌شده یا بایگانی‌شده در لیست | `DRAFT` پنهان ولی نگه‌داشته (`findCards` و `countVisible` فقط `ACTIVE`)؛ بایگانی ردیف‌ها رو در همون تراکنش پاک می‌کنه | `WebV1WishlistOnDatabaseTest` › `a draft is hidden from the list, the ids and the count … archived it is gone for good`؛ `ProductStatusUseCasesTest` › `DELETE archives with one row …` |
| — | قلب شخصی در پاسخ کش‌شده | قلب فقط روی endpointهای بدون کش (لیست، صفحه، مرتبط، سبد)؛ مهمان `null`؛ توکن خراب ← `401`، نه مهمان | `WebV1WishlistHeartsOnDatabaseTest` › `a guest sees no heart anywhere - a customer sees theirs …` |
| — | عدد ناجور در سربرگ حساب | هر عدد داشبورد با همون خوندن صفحه‌ی خودش (`countByStatusOfCustomer`، `countVisible`، `countsOfUser`، `ReviewsAwaiting` ِ مشترک با «منتظر نظر تو») | `WebV1WishlistHeartsOnDatabaseTest` › `the dashboard - every number the same as the page it links to …` |
| 1 | بالا رفتن دسترسی | همه‌ی `/me/wishlist*` و «انتقال» و داشبورد با ورود اجباری؛ لیست کاربر همیشه مال توکنه، نه شناسه‌ی درخواست | `RouteGuardsTest`؛ تست‌های `401` در هر فایل بالا |

---

## خرابکاری عمدی

| نگهبان | چی برداشته شد | تست(هایی) که قرمز شد |
|---|---|---|
| قفل ۱۳ در سقف | `lockFor` به `find` در `AddToWishlistUc` | `WebV1WishlistOnDatabaseTest` › `20 adds at once onto 195 rows leave exactly 200 …` |
| بایگانی ← پاک از لیست‌ها | `wishlistRepository.deleteProduct(id)` از `ArchiveProductUc` | `WebV1WishlistOnDatabaseTest` › `a draft is hidden … archived it is gone for good`؛ `ProductStatusUseCasesTest` › `DELETE archives with one row …` |
| فقط ردیف‌های دیده‌شده | فیلتر `ACTIVE` از `WishlistRepositoryImpl` (شمارش و شناسه‌ها) | `WebV1WishlistOnDatabaseTest` › `a draft is hidden from the list, the ids and the count …` |
| برگشت کامل «انتقال» | شکست ِ لیست پر نادیده گرفته شد (قلم از سبد رفت) | `WebV1WishlistCartOnDatabaseTest` › `a cart line moved - … a full list 422 and the line stays …` |
| رنگ موجود در «همه به سبد» | `firstOrNull { it.isPurchasable }` به `firstOrNull()` | `WebV1WishlistCartOnDatabaseTest` › `all to the cart - another color when the saved one is gone …` |
| پرچم `wishlistShare` در لیست عمومی | چک پرچم از `GetSharedWishlistUc` | `WebV1WishlistShareOnDatabaseTest` › `the flag off - sharing 422, the public list 404 …` |
| `no-store` | هدر از route ِ لیست عمومی | `WebV1WishlistShareOnDatabaseTest` › `the public list - the first name only, never a phone, published products only, no-store …` |
| یه لینک زنده | برگردوندن لینک موجود از `ShareMyWishlistUc` | `WebV1WishlistShareOnDatabaseTest` › `asked twice - one token - revoked …` |
| فقط محصولات منتشرشده (لیست عمومی و «علاقه‌مندی‌ها») | فیلتر `ACTIVE` از `ProductQmRepositoryImpl.findCards` | `WebV1WishlistShareOnDatabaseTest` › `the public list - … published products only …`؛ `WebV1WishlistOnDatabaseTest` › `not in the shop 404 …`، `a draft is hidden …` |
| قلب روی سبد | `withHearts` از `GetCartUc` | `WebV1WishlistHeartsOnDatabaseTest` › `a guest sees no heart anywhere - a customer sees theirs …` |
| قلب روی لیست محصولات | `wishlistHearts.mark` از `FindProductsUc` | همون تست |
| عددهای داشبورد | `ordersActive` = همه‌ی سفارش‌ها به‌جای تب «جاری» | `WebV1WishlistHeartsOnDatabaseTest` › `the dashboard - every number the same as the page it links to …` |

---

## چیزهایی که موقع کار پیدا شد

- **قیمت هر رنگ روی کارت نبود** (گام ۹.۱): `ProductCardQm` فقط قیمت کارت (رنگ پیش‌فرض) رو داشت؛ `priceAtAdd` و افت قیمت ِ رنگ ذخیره‌شده `ProductCardQm.Color.price` رو لازم داشتن.
- **سقف ۶۰ ِ `?ids=` برای read model نیست**: به‌جای دسته‌های ۶۰تایی، `findCards(ids)` با سه خوندن هر تعداد کارت رو می‌خونه (لیست ۱۲۰تایی در تست).
- **ورود اختیاری روی routeهای محصول** سه تست route ِ قدیمی رو مجبور کرد پلاگین احراز هویت رو نصب کنن (بدون اون Ktor route ِ `authenticate` رو رد می‌کنه).
- **«منتظر نظر تو» دو بار نوشته نشد**: داشبورد شمارشش رو می‌خواست؛ منطق به سرویس مشترک `ReviewsAwaiting` رفت و UseCase ِ فاز ۸ ازش می‌خونه.
- **سبد صفحه‌ی پرداخت قلب نداشت** (گام ۹.۴): کل مجموعه نشون داد `GET /checkout` و `GET /cart` دیگه یه سبد نیستن؛ قلب به `withHearts` ِ مشترک در `CartView` رفت.
- **محیط این ماشین:** تست‌ها روی یه Postgres ِ موقت (پورت ۵۴۳۳، `KIVA_TEST_DB_URL`) رفتن.

---

## دور واقعی روی سرور (مونده برای تو)

اینجا سرور و kiva-fe در دسترس نبودن؛ همین مسیرها با تست‌های route روی routeهای واقعی رفته شدن. دور واقعی:

1. `V34` روی سرور اجرا می‌شه؛ قطعه‌ی تازه‌ی nginx (zone ِ `kiva_wishlist_shared` و location ِ `/api/v1/wishlists/shared/`) رو از مرجع اضافه کن، `nginx -t` و reload.
2. kiva-fe بدون ورود: چند قلب ← ورود ← قلب‌ها به حساب می‌رن (یه `PUT` برای هر کدوم) ← سربرگ: شمارنده‌ی علاقه‌مندی.
3. «علاقه‌مندی‌ها»: یه محصول رو تو پنل ارزون‌تر کن ← برچسب «از وقتی ذخیره کردی …٪ ارزون‌تر شده».
4. «همه به سبد» ← سبد؛ از سبد «انتقال به علاقه‌مندی‌ها» ← «برگردون».
5. پنل: تنظیمات ← `wishlistShare` روشن ← «اشتراک‌گذاری» ← لینک رو در یه مرورگر بدون ورود باز کن: فقط اسم کوچک، بدون موبایل ← «غیرفعال کردن لینک» ← همون لینک ۴۰۴.
6. صفحه‌ی حساب: هر عدد سربرگ با صفحه‌ای که بهش لینک می‌ده.
