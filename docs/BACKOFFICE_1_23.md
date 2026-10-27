# کانترکت 1.23.0: پنل دیگه مقدار ساختگی رو ذخیره نمی‌کنه

> درباره‌ی: بررسی ناهماهنگی‌های پنل و بک‌اند (۵ آبان ۱۴۰۵). پنج باگ جدی پیدا شد که چهارتاش از پاسخ‌های بک‌اند بود و یکی از خود پنل.
> نتیجه: کانترکت **1.23.0**. هیچ فیلدی حذف یا عوض نشده؛ فیلدها و یک endpoint اضافه شده‌اند. کد پنل هم در branch ِ `backend-1.23-fixes` اصلاح شده.

## ریشه‌ی مشترک

پاسخ‌های پنل همون چیزی رو برمی‌گردوندن که فروشگاه نشون می‌ده: قیمت رنگ پیش‌فرض، SEO با جایگزین‌ها («نام | کیوا»)، خلاصه‌ی ساخته‌شده از متن.
فرم‌های پنل اینا رو پر می‌کردن و با `PUT` پس می‌فرستادن. نتیجه این بود که مقدار ساختگی جای مقدار واقعی ذخیره می‌شد.
حالا کنار هر کدوم، مقدار خود فرم هم میاد. **فرم فقط از این فیلدهای جدید پر می‌شه.**

## خلاصه

| # | مشکل | چی اضافه شد | پنل |
|---|---|---|---|
| ۱ | ویرایش رنگ، قیمت اختصاصی‌اش رو پاک می‌کرد | `AdminVariant.priceOverride`، `compareAtPriceOverride`، `sortOrder` | مودال رنگ از قبل همین‌ها رو می‌خوند؛ فقط نوع‌ها دقیق شدن |
| ۲ | ذخیره‌ی محصول، قیمت رنگ پیش‌فرض رو قیمت پایه می‌کرد | `AdminProduct.basePrice`، `baseCompareAtPrice` | فرم محصول قیمت رو از این‌ها می‌گیره |
| ۳ | SEO ذخیره‌شده برنمی‌گشت؛ `noIndex` هر بار خاموش می‌شد؛ نام بلند ذخیره نمی‌شد | شِمای `SeoInput` و فیلد `seoInput` در `AdminProduct`، `AdminCategory`، `AdminStaticPage` و `AdminBlogPost` | هر چهار فرم SEO رو از `seoInput` می‌گیرن |
| ۳ | خلاصه و زمان مطالعه‌ی بلاگ بعد از اولین ذخیره ثابت می‌شدن | `AdminBlogPost.excerptInput`، `readingMinutesInput` | فرم بلاگ از این‌ها پر می‌شه؛ خلاصه‌ی ساخته‌شده placeholder ِ فیلده |
| ۳ | ویرایشگر صفحه از `GET /pages/{slug}` ِ کش‌شده (تا ۵ دقیقه) می‌خوند | **`GET /admin/pages/{slug}`** (بدون کش، `AdminStaticPage`)؛ `PUT` هم همین شکل رو برمی‌گردونه | ویرایشگر صفحه از این می‌خونه |
| ۴ | صفحه‌ی سفارشِ دارای مرجوعی برای ADMIN کرش می‌کرد | `AdminOrder.returns` حالا `AdminReturnRequest[]` ـه (با `allowedTransitions` و `suggestedRefund`) | نوع پنل از قبل همین بود؛ mock هم همین بود |
| ۵ | سوئیچ «فعال» درگاه پرداخت کار نمی‌کرد | — (بک‌اند درست بود: `isActive` می‌خونه، `available` فقط‌خواندنیه) | سوئیچ به `isActive` وصل شد؛ نوع `PaymentGateway` شکل `AdminPaymentGatewaySetting` رو گرفت |

## قاعده برای فرم‌های بعدی

- `price` / `seo` / `excerpt` / `readingMinutes` = چیزی که مشتری می‌بینه. فقط برای نمایش.
- `basePrice` / `priceOverride` / `seoInput` / `excerptInput` / `readingMinutesInput` = مقدار خود فرم. همین‌ها رو پر کن و پس بفرست.
- `null` یعنی «از خودش بساز». خالی فرستادن، `null` ذخیره می‌کنه.

## چی در کانترکت 1.23.0 عوض شد (برای diff)

- شِمای تازه: `SeoInput`، `AdminStaticPage`
- عملیات تازه: `GET /admin/pages/{slug}` (`adminGetPage`، ADMIN و CONTENT_EDITOR)
- `PUT /admin/pages/{slug}`: پاسخ `StaticPage` ← `AdminStaticPage`
- `AdminVariant` + `priceOverride`، `compareAtPriceOverride`، `sortOrder`
- `AdminProduct` + `basePrice`، `baseCompareAtPrice`، `seoInput`
- `AdminCategory` + `seoInput`
- `AdminBlogPost` + `excerptInput`، `readingMinutesInput`، `seoInput`
- `AdminOrder.returns`: `ReturnRequest` ← `AdminReturnRequest`
