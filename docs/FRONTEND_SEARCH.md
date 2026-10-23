# جستجوی زنده: راهنمای فرانت (kiva-fe)

> بک‌اند: برنچ `search`، فاز ۱۲ ِ `COMPLETION_ROADMAP.md` (گام‌های ۱۲.۰ تا ۱۲.۳)، ۱ آبان ۱۴۰۵. مرجع دقیق کانترکت `api/kiva-openapi.yml` (نسخه‌ی 1.18.0) است؛ بخش ۱۷.۷ ِ نقشه‌ی راه تغییرات کانترکت و بخش ۱۷.۲ دلیل هر قانون.

## خلاصه

- قبل از تایپ: `GET /search/hints` (کش ۵ دقیقه، `ETag`). با هر حرف: `GET /search/suggest?q=…` با **حداقل ۲۵۰ میلی‌ثانیه debounce** و لغو درخواست قبلی (nginx بیشتر از ۱۰ در ثانیه رو `429` می‌کنه).
- `nameHighlighted` **فقط** با تگ `<mark>`ـه و بقیه‌ش escape شده: می‌تونی با `innerHTML` (یا `v-html`) نشونش بدی. `query` و `normalizedQuery` متن خامن: همیشه به‌صورت متن، هیچ‌وقت `innerHTML`.
- قانون جستجو با `GET /products?q=` یکیه (همه‌ی کلمه‌ها، حداکثر ۶)، پس «همه رو ببین» دقیقاً `totalProducts` نتیجه داره.

## ۱. پیش از تایپ

```js
GET /search/hints    200 { categories: [CategoryRef], colors: [Color], popularQueries: ["کیف مجلسی", …] }
```

- دسته‌های منو که محصول دارن (حداکثر ۸)، رنگ‌هایی که محصولی باهاشون هست (حداکثر ۱۲)، جستجوهای پیشنهادی دستی (حداکثر ۸). کلیک روی جستجوی پیشنهادی ← همون `q`.

## ۲. با هر حرف

```js
GET /search/suggest?q=کراس مشکي&limit=8
200 { query, normalizedQuery, products: [SearchSuggestProduct], categories, colors, totalProducts, seeAllUrl }
```

| خطا | کِی | چی کار کنی |
|---|---|---|
| `400 SEARCH_QUERY_EMPTY` روی `q` | فقط فاصله یا نیم‌فاصله | درخواست نزن (پیشنهادهای `hints` رو نشون بده) |
| `400 SEARCH_QUERY_TOO_LONG` | بیشتر از ۱۰۰ حرف | ورودی رو به ۱۰۰ حرف محدود کن |
| `400 LIMIT_INVALID` | `limit` بیرون ۱ تا ۲۰ | |
| `401` | توکن خراب یا منقضی | توکن رو تازه کن (جستجو بدون ورود هم کار می‌کنه؛ توکن خراب رو نفرست) |
| `429` | تایپ خیلی سریع بدون debounce | آخرین درخواست رو کمی بعد دوباره بزن |

- هر محصول: `nameHighlighted`، `categoryName`، `price`، `imageUrl` (عکس کوچیک؛ ممکنه `null` باشه)، `url`. اگه عبارت اسم یه رنگ داشت (`matchedColorKey`)، عکس و لینک با همون رنگن (`/product/nila-crossbody-bag?color=black`).
- ترتیب: اسم‌هایی که همه‌ی کلمه‌ها رو دارن اول، بعد موجودها، بعد پرفروش‌ترها؛ ناموجودها هم میان (روشون «ناموجود» و «موجود شد خبرم کن» نشون بده).
- `categories` و `colors` (هر کدوم حداکثر ۴) لینک سریع‌ان: `/products?category=…` و `/products?color=…`.
- «همه‌ی نتایج ({totalProducts})» ← `seeAllUrl` (عبارت نرمال‌شده، کدشده).
