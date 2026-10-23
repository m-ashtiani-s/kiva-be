# ریویوی جستجوی زنده: فاز ۱۲ (گام‌های ۱۲.۰ تا ۱۲.۳)

> برنچ `search`، ۱ آبان ۱۴۰۵. نقشه‌ی راه: `COMPLETION_ROADMAP.md`، فاز ۱۲. راهنمای فرانت: `docs/FRONTEND_SEARCH.md`.
> این فاز ردیف تازه‌ای در بخش ۳ ِ نقشه‌ی راه نداره؛ خطرهای خودش: XSS از راه اسم محصول در `nameHighlighted`، پیش‌نویس یا بایگانی‌شده‌ای که در جستجو پیدا بشه، دو جستجو با یه عبارت و دو نتیجه، و فشار جستجوی «با هر حرف» روی سرور. برای هر کدوم: دفاع، تست نگهبان، و نتیجه‌ی خرابکاری عمدی.

---

## خلاصه

| چی بررسی شد | نتیجه |
|---|---|
| `./gradlew test --continue --rerun-tasks` | ✅ **۳۰۱۴ تست** سبز. ۵ تست skip: تست‌های ابزار واقعی (`vips` و `ffmpeg` روی این ماشین نیستن) |
| هشدار کامپایلر | ✅ صفر |
| قوانین لایه‌ها | ✅ `domain`، `common` و `application` هیچ import از Ktor، Exposed یا Koin ندارن |
| کانترکت | ✅ 1.18.0 (گام ۱۲.۰). پاسخ‌های `SearchSuggestResponse`، `SearchSuggestProduct` و `SearchHints` در تست‌ها با شِماهای کانترکت مقایسه می‌شن |
| nginx | ✅ zone ِ `kiva_search` با ۱۰ در ثانیه و `burst=20` برای `/api/v1/search/suggest` (`kiva-http.conf`، `api.kivashop.ir.conf`، جدول `LAUNCH.md`) |
| حذف حساب (بخش ۴.۱۰) | ✅ هیچ جدول کاربری تازه‌ای نیست؛ **لاگ جستجو نگه داشته نمی‌شه** |
| دور واقعی روی سرور با kiva-fe | ⬜ مونده برای تو (پایین‌تر) |
| merge به `main` و deploy | ⬜ منتظر تأیید تو (این برنچ از سر فاز ۱۱ ساخته شده) |

---

## خطرهای این فاز

| خطر | دفاع | تست‌ها |
|---|---|---|
| XSS از راه اسم محصول | `nameHighlighted` از `MatchRanges.mark`: کل اسم escape می‌شه و فقط `<mark>` ِ خودمون تگه؛ همون تابع سؤالات متداول | `MatchRangesTest` › `everything but the marks is escaped - a name with a tag never becomes one` |
| پیش‌نویس یا بایگانی در جستجو | `conditionsOf` و `findCards` همیشه `status = ACTIVE` | `WebV1SearchOnDatabaseTest` › `a draft is never found - …` |
| دو جستجو، دو نتیجه | `SearchQueryPolicy` یکی برای `suggest` و `GET /products?q=`؛ `totalProducts` همون شمارش | `WebV1SearchOnDatabaseTest` › `… totalProducts is GET products' count for the same words - both words must match`؛ `SearchQueryPolicyTest` |
| حرف عربی، رقم فارسی یا نیم‌فاصله‌ای که پیدا نکنه | نرمال‌سازی عبارت (نیم‌فاصله ← فاصله)؛ `searchName` با همون `normalizePersian` ِ `searchText`، backfill با تابع SQL ِ هم‌قانون | `SearchMigrationTest`؛ `SearchQueryPolicyTest`؛ `MatchRangesTest` |
| نتیجه‌ی نامربوط بالای لیست | رتبه: «همه‌ی کلمه‌ها در اسم»، بعد موجود، بعد پرفروش | `WebV1SearchOnDatabaseTest` › `a product whose name has the word comes before … a sold-out one after those in stock` |
| فشار روی سرور | nginx ۱۰ در ثانیه برای هر IP؛ چهار خوندن ثابت برای هر جستجو؛ `hints` کش ۵ دقیقه‌ای | `WebV1SearchOnDatabaseTest` › `the hints - … cached with 304` |
| داده‌ی رفتاری کاربر | هیچ جستجویی ذخیره یا لاگ نمی‌شه (`SearchSuggestUc` فقط `limit` رو لاگ می‌کنه)؛ `popularQueries` دستیه | بازبینی کد |

---

## خرابکاری عمدی

همه با یه اسکریپت در گام ۱۲.۳ دوباره رفتن و **هر ۱۰ تا قرمز کردن**:

| نگهبان | چی برداشته شد | تست(هایی) که قرمز شد |
|---|---|---|
| escape ِ `<` | از `MatchRanges` | `MatchRangesTest` › `everything but the marks is escaped …` |
| escape ِ `&` | از `MatchRanges` | همون تست |
| SQL مثل `normalizePersian` | `translate` از `kiva_normalize_persian` | `SearchMigrationTest` › `the SQL and normalizePersian give the same text …` |
| نیم‌فاصله ← فاصله | از `SearchQueryPolicy.normalize` | `SearchQueryPolicyTest` › `normalized - …` |
| اسم اول | معیار اول رتبه | `WebV1SearchOnDatabaseTest` › `a product whose name has the word comes before …` |
| موجود اول | معیار دوم رتبه | همون تست (بعد از اینکه نیلا پرفروش‌ترین شد؛ قبلش تست این نگهبان رو نمی‌دید) |
| فقط منتشرشده‌ها | `ACTIVE` از `conditionsOf` و `findCards` | پنج تست ِ `WebV1SearchOnDatabaseTest` |
| همه‌ی کلمه‌ها (AND) | فقط کلمه‌ی اول | `… both words must match` |
| رنگ‌های فروشگاه در `hints` | فیلتر «محصول منتشرشده داره» | `… the hints - …` (بعد از اینکه رنگ آزمایشی اول پالت رفت؛ قبلش بیرون سقف ۱۲ بود) |
| کش ِ `hints` | `respondResult` به‌جای `respondResultCacheable` | `… the hints - … cached with 304` |

---

## چیزهایی که موقع کار پیدا شد

- **`normalizePersian` نیم‌فاصله‌ی وسط کلمه رو نگه می‌داره** (تصمیم ۱۵): به‌جای عوض کردنش (که همه‌ی اسم‌های یکتا و ستون‌های جستجو رو جابه‌جا می‌کرد)، فقط عبارت جستجو نیم‌فاصله رو فاصله می‌کنه؛ با تطابق «شامل» هر دو شکل پیدا می‌شن.
- **`MarkHighlighter` ساخته نشد** (تصمیم ۱۶): `MatchRanges.mark` در `common` هم برای جستجو هم برای سؤالات متداوله؛ دو `escape` نمی‌مونه.
- **دو تست اول نگهبانشون رو نمی‌دیدن**: «موجود اول» (محصول ناموجود از قبل پایین‌تر بود) و «فقط رنگ‌های فروشگاه» (رنگ آزمایشی بیرون سقف ۱۲). هر دو قوی‌تر شدن و حالا قرمز می‌کنن.
- **تست همزمانی ارسال** (`WebV1ShipmentOnDatabaseTest` › `… 50 times - never a deadlock`) دوباره یه بار زیر بار به timeout خورد و تنها سبز شد؛ بی‌ربط به این فاز.

---

## دور واقعی روی سرور (مونده برای تو)

1. `V37` روی سرور اجرا می‌شه (تابع `kiva_normalize_persian`، `searchName`، پیشنهادهای جستجو). قطعه‌ی تازه‌ی nginx (zone ِ `kiva_search` و location ِ `/api/v1/search/suggest`) رو از مرجع اضافه کن، `nginx -t` و reload.
2. kiva-fe: جعبه‌ی جستجو باز ← دسته‌ها، رنگ‌ها و جستجوهای پیشنهادی؛ «کراس مشکي» (با ی عربی) ← کراس‌بادی‌ها با عکس مشکی و «کراس» هایلایت؛ «همه رو ببین» ← لیست با همون تعداد.
3. یه محصول با اسم `<b>تست</b>` در پنل ← در جستجو به‌صورت متن دیده می‌شه، نه تگ.
4. تایپ سریع (بدون debounce) ← بعد از ۲۰ درخواست پشت‌سرهم `429` با هدرهای CORS.
