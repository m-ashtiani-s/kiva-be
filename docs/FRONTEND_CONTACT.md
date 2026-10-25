# تماس و خبرنامه: راهنمای فرانت (kiva-fe و پنل)

> بک‌اند: برنچ `contact`، فاز ۱۳ ِ `COMPLETION_ROADMAP.md` (گام‌های ۱۳.۰ تا ۱۳.۵)، ۱ آبان ۱۴۰۵. مرجع دقیق کانترکت `api/kiva-openapi.yml` (نسخه‌ی 1.19.0) است؛ بخش ۱۸.۸ ِ نقشه‌ی راه تغییرات کانترکت و بخش ۱۸.۲ دلیل هر قانون.

## خلاصه

- فرم تماس: `GET /contact/topics` (کش ۵ دقیقه، `ETag`) و `POST /contact/messages` ← `201` با کد پیگیری `CT-` و ۶ رقم. ورود اختیاریه؛ اگه کاربر وارد شده، توکن رو بفرست (پیام به حسابش وصل می‌شه). توکن خراب ← `401`، نه مهمان.
- خبرنامه: `POST /newsletter/subscriptions` **همیشه `200`** با یه پیام؛ پاسخ نمی‌گه ایمیل از قبل عضو بوده یا نه، پس همیشه همون پیام رو نشون بده. صفحه‌ی لغو: `/newsletter/unsubscribe?token=nl_…` (لینک ایمیل خوش‌آمد) که `POST /newsletter/subscriptions/unsubscribe` رو صدا می‌زنه و همیشه `204` می‌گیره.
- پنل (پشتیبانی): `GET /admin/contact-messages` و `PATCH /admin/contact-messages/{ticketCode}` برای `ADMIN` و `SUPPORT`؛ لیست خبرنامه `GET /admin/newsletter/subscriptions` فقط `ADMIN`.

## ۱. فرم تماس (kiva-fe، صفحه‌ی `contact`)

```js
GET /contact/topics   200 [{ value: "ORDER_FOLLOWUP", label: "پیگیری سفارش", requiresOrderCode: true }, …]
```

- پنج موضوع به همین ترتیب. `requiresOrderCode: true` یعنی **فیلد اختیاری** «شماره سفارش» رو نشون بده (`ORDER_FOLLOWUP` و `RETURN`)؛ اجباری نیست.

```js
POST /contact/messages  { topic, fullName, phone, email?, orderCode?, message }
201 { ticketCode: "CT-482113", createdAt, message: "پیامت رسید! کد پیگیریت CT-482113ـه؛ به زودی باهات تماس می‌گیریم." }
```

| فیلد | قانون | خطا |
|---|---|---|
| `topic` | یکی از پنج تا | `FIELD_REQUIRED`، `CONTACT_TOPIC_INVALID` |
| `fullName` | ۳ تا ۸۰ حرف | `FIELD_REQUIRED`، `TEXT_TOO_SHORT` (`meta.minLength`)، `TEXT_TOO_LONG`، `TEXT_INVALID` |
| `phone` | موبایل؛ ارقام فارسی، فاصله و `+98` هم قبوله | `PHONE_REQUIRED`، `PHONE_INVALID` |
| `email` | اختیاری؛ خالی یعنی نفرستاده | `EMAIL_INVALID`، `EMAIL_TOO_LONG` |
| `orderCode` | اختیاری؛ `kv-218340` و ارقام فارسی هم؛ فقط مرجعه (مالکیتش چک نمی‌شه) | `ORDER_CODE_INVALID` |
| `message` | متن ساده ۱۰ تا ۶۰۰ حرف؛ خط تازه می‌مونه | `FIELD_REQUIRED`، `TEXT_TOO_SHORT`، `TEXT_TOO_LONG`، `TEXT_INVALID` |

- همه‌ی خطاها با هم در `errors` میان (`400 VALIDATION_ERROR`)، هر کدوم زیر فیلد خودش.
- `429 RATE_LIMITED`: بیشتر از ۵ پیام در ساعت از یه IP، یا ۵ پیام در ۲۴ ساعت از یه موبایل. `meta.retryAfterSeconds` (و هدر `Retry-After`) زمان انتظاره؛ بگو «یه کم دیگه دوباره بفرست» و دکمه رو تا اون موقع غیرفعال کن.
- بعد از `201`، کد پیگیری رو بزرگ نشون بده و بگو اگه لازم شد با همین کد پیگیری کنن. خبری با پیامک نمی‌ره؛ پشتیبانی خودش تماس می‌گیره.

## ۲. خبرنامه (فوتر، و صفحه‌ی لغو)

```js
POST /newsletter/subscriptions  { email, source?: "footer" }
200 { message: "عضو خبرنامه کیوا شدی!" }
```

- `source` اختیاری: حروف کوچک لاتین، رقم، `_` و `-`، حداکثر ۳۰ (مثلاً `footer`، `home_popup`)؛ غیر از این بی‌صدا نادیده گرفته می‌شه.
- خطاها: `400` روی `email` (`FIELD_REQUIRED`، `EMAIL_INVALID`)؛ `429` بعد از ۵ درخواست در ساعت از یه IP.
- `Sara@Example.com` و `sara@example.com` یه عضون. عضو تازه یا برگشته یه ایمیل خوش‌آمد با لینک لغو می‌گیره؛ عضو ِ از قبل فعال نه. برای کاربر فرقی نکنه: همیشه همون پیام.

صفحه‌ی `/newsletter/unsubscribe?token=nl_…` (بدون ورود):

```js
POST /newsletter/subscriptions/unsubscribe  { token }   204
```

- توکن رو از آدرس بخون و با یه دکمه («لغو عضویت») بفرست، نه خودکار با باز شدن صفحه (پیش‌نمایش لینک ِ بعضی ایمیل‌ها صفحه رو باز می‌کنه).
- `204` ← «عضویتت لغو شد». توکن ناشناخته یا از قبل لغوشده هم `204`ـه؛ همون پیام.
- `400 UNSUBSCRIBE_TOKEN_INVALID` روی `token`: لینک ناقص کپی شده (`nl_` و ۳۲ حرف و رقم لاتین نیست) ← «لینک کامل نیست؛ از خود ایمیل بازش کن».

## ۳. پنل: پیام‌های تماس (`ADMIN`، `SUPPORT`)

```js
GET /admin/contact-messages?status=OPEN&topic=RETURN&page=1&size=20
200 { items: [AdminContactMessage], meta }
```

- تازه‌ترین اول. صف کار = `status=OPEN`. فیلتر ناشناخته ← `400` (`CONTACT_STATUS_INVALID`، `CONTACT_TOPIC_INVALID`).
- هر ردیف همه‌ی پیام: موبایل کامل، ایمیل، کد سفارش، متن، وضعیت، آخرین پاسخ (`reply`، `replyChannel`، `repliedAt`، `repliedBy` ← اسم اپراتور، وگرنه موبایلش ماسک‌شده)، یادداشت داخلی، `userId` (اگه وارد شده بود).

```js
PATCH /admin/contact-messages/CT-482113  { status?, reply?, replyChannel?, internalNote? }
200 AdminContactMessage
```

- همه اختیاری؛ `{}` چیزی رو عوض نمی‌کنه. وضعیت از هر به هر (`OPEN`، `IN_PROGRESS`، `CLOSED`).
- `reply` (۲ تا ۱۰۰۰) و `replyChannel` با هم: یکی بی دیگری ← `400 REPLY_CHANNEL_REQUIRED` یا `REPLY_REQUIRED`.
  - `SMS`: **متن پاسخ پیامک نمی‌شه.** فقط یه پیامک «پشتیبانی کیوا به پیامت رسیدگی کرد» با کد پیگیری می‌ره؛ اپراتور خودش زنگ می‌زنه یا در پیام‌رسان می‌نویسه. این رو کنار دکمه بنویس.
  - `EMAIL`: متن کامل پاسخ ایمیل می‌شه. فقط برای پیام‌هایی که ایمیل دارن؛ وگرنه `400 EMAIL_NOT_AVAILABLE` روی `replyChannel` (دکمه‌ی ایمیل رو برای پیام بی‌ایمیل غیرفعال کن).
  - هر پاسخ تازه جای قبلی رو می‌گیره و دوباره فرستاده می‌شه.
- `internalNote` (حداکثر ۱۰۰۰) جایگزین می‌شه؛ `null` پاکش می‌کنه؛ هیچ‌وقت به مشتری نمی‌رسه.
- کد ناشناخته ← `404`. فرستادن پیامک یا ایمیل بعد از ذخیره‌ست؛ اگه نرسه، پاسخ ذخیره شده و `200` میاد (لاگ سرور خبر داره).

## ۴. پنل: خبرنامه (`ADMIN`)

```js
GET /admin/newsletter/subscriptions?status=ACTIVE&q=exam&page=1&size=50
200 { items: [{ id, email, status, source, subscribedAt, unsubscribedAt }], meta }
```

- تازه‌ترین عضویت اول. تعداد مشترک‌های فعال = `meta.totalItems` با `status=ACTIVE`. `q` بخشی از ایمیل (بزرگ و کوچک فرقی نداره، حداکثر ۱۰۰).
- فرستادن خبرنامه با ابزار بیرونیه؛ این‌جا فقط لیسته. لغوشده‌ها ۹۰ روز بعد پاک می‌شن.
