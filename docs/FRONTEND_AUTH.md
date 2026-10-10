# ورود و حساب: راهنمای فرانت

> بک‌اند: برنچ `auth`، گام‌های ۱ تا ۱۱، ۱۸ مهر ۱۴۰۵. مرجع دقیق شکل درخواست‌ها و پاسخ‌ها کانترکت `api/kiva-openapi.yml` (نسخه‌ی 1.4.0) است.
> این فایل می‌گه چطور ازشون استفاده کنی، و کجاها راحت می‌شه اشتباه کرد.

## خلاصه

- ورود و ثبت‌نام یکیه: `POST /auth/otp/send` و بعد `POST /auth/otp/verify`.
- `accessToken` فقط تو حافظه نگه داشته می‌شه (یه متغیر)، نه `localStorage` و نه کوکی. با `Authorization: Bearer …` فرستاده می‌شه و ۱۵ دقیقه عمر داره.
- refresh token تو کوکی `kiva_rt` ـه و جاوااسکریپت نمی‌بیندش. مرورگر فقط وقتی نگهش می‌داره و می‌فرستدش که درخواست با `credentials: 'include'` بره.
- با باز شدن صفحه، و با هر `401 TOKEN_EXPIRED`، `POST /auth/refresh` صدا زده می‌شه. در هر لحظه فقط یه تب این کار رو می‌کنه (`navigator.locks`).
- خروج یعنی `POST /auth/logout` و پاک کردن `accessToken` از حافظه.

## ۱. ورود

```js
// 1) send the code. The answer is the same whether the phone has an account or not.
POST /api/v1/auth/otp/send            { "phone": "0912 123 4567" }
200 { "phone": "09121234567", "codeLength": 5, "expiresInSeconds": 300, "resendAvailableInSeconds": 120 }

// 2) verify. credentials: 'include' is required, or the browser drops the session cookie.
POST /api/v1/auth/otp/verify          { "phone": "09121234567", "code": "۴۸۲۱۳" }
200 { "accessToken": "eyJ…", "tokenType": "Bearer", "expiresIn": 900, "refreshExpiresIn": 2592000,
      "isNewUser": true, "user": { … }, "cart": null }
Set-Cookie: kiva_rt=…; Max-Age=2592000; Path=/api/v1/auth; Secure; HttpOnly; SameSite=Strict
```

- شماره با هر شکلی پذیرفته می‌شه (`+98`، فاصله، رقم فارسی). برای متن «کد به … رفت»، `phone` ِ پاسخ رو نشون بده.
- کد با رقم فارسی هم قبوله. کدی که ۵ رقم نباشه `400` می‌گیره و یه تلاش حساب نمی‌شه.
- تایمر «ارسال دوباره» از `resendAvailableInSeconds` میاد.
- اگه `isNewUser` بود، مرحله‌ی «دوست داری چی صدات کنیم؟» رو نشون بده و اسم رو با `PATCH /me` ذخیره کن.
- `refreshExpiresIn` برای مشتری ۳۰ روزه و برای کارمند پنل ۱۲ ساعت. فقط برای اطلاعه و لازم نیست چیزی باهاش بسازی.

## ۲. توکن‌ها و `credentials`

| درخواست | `credentials: 'include'` |
|---|---|
| `/auth/otp/verify`، `/auth/refresh`، `/auth/logout`، `/me/phone-change/verify` | **لازمه.** بدون اون، مرورگر کوکی پاسخ رو دور می‌ریزه یا کوکی رو نمی‌فرسته |
| بقیه | لازم نیست. کوکی فقط برای `/api/v1/auth/*` فرستاده می‌شه |

- `accessToken` رو هیچ‌جا ذخیره نکن. با رفرش صفحه از بین می‌ره و این عمدیه، چون تمدید بی‌صدا (بخش ۳) یه توکن تازه می‌گیره.
- کوکی `Secure` ـه. روی `http://localhost` کار می‌کنه، چون مرورگر localhost رو امن حساب می‌کنه. روی آدرس‌های HTTP دیگه (مثلاً گوشی روی شبکه‌ی خونه که به `192.168…` وصله) مرورگر کوکی رو نگه نمی‌داره، پس ورود نمی‌مونه.
- فرانت باید روی یکی از originهای مجاز باشه. تو توسعه، `localhost` یا `127.0.0.1` روی پورت 5173 یا 3000. برای origin دیگه، `/auth/refresh` و `/auth/logout` جواب `403` می‌دن.

## ۳. تمدید بی‌صدا و چند تب

```js
const API = '/api/v1'
let accessToken = null              // memory only
let refreshing = null               // one refresh at a time inside this tab

// After the page opens, and after any 401 TOKEN_EXPIRED. Across tabs, one at a time.
function refreshSession() {
  refreshing ??= navigator.locks.request('kiva-refresh', async () => {
    const res = await fetch(`${API}/auth/refresh`, { method: 'POST', credentials: 'include' })
    accessToken = res.ok ? (await res.json()).accessToken : null
    return res.ok
  }).finally(() => { refreshing = null })
  return refreshing
}

async function api(path, init = {}) {
  const send = () => fetch(`${API}${path}`, {
    ...init,
    headers: { ...init.headers, ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}) },
  })
  const hadToken = accessToken !== null
  let res = await send()
  // TOKEN_EXPIRED is the usual case. UNAUTHORIZED with a token means the server's key changed: a refresh fixes that too.
  if (res.status === 401 && hadToken && await refreshSession()) {
    res = await send()
  }
  return res
}
```

- `200` از refresh یه `accessToken` تازه می‌ده. معمولاً یه کوکی تازه هم میاد. اگه همون لحظه تب دیگه‌ای تمدید کرده باشه، کوکی نمیاد، و این درسته.
- `401 REFRESH_TOKEN_INVALID` یعنی نشست تموم شده (خروج، منقضی، تغییر شماره یا نقش، یا دزدی). کاربر رو مهمان حساب کن. اگه صفحه ورود لازم داشت، بفرستش به ورود.
- چرا یه تب: سرور توکنی رو که همین الان عوض شده تا ۳۰ ثانیه می‌پذیره. بعد از اون، استفاده‌ش یعنی دزدی، و **کل نشست** باطل می‌شه. پس دو تب که بدون قفل و با فاصله تمدید کنن، کاربر رو بیرون می‌اندازن.
- `navigator.locks` تو همه‌ی مرورگرهای امروزی هست. تب دومی که منتظر قفل مونده، کوکی تازه رو می‌فرسته، چون کوکی بین تب‌ها مشترکه.

## ۴. ورود اختیاری

صفحه‌هایی که با و بی ورود کار می‌کنن (سبد، صفحه‌ی اصلی): بدون هدر `Authorization` یعنی مهمان. ولی اگه هدر رو بفرستی و توکن منقضی باشه، جواب `401 TOKEN_EXPIRED` ـه، نه نسخه‌ی مهمان. همون `api()` بالا درستش می‌کنه.

## ۵. خروج

```js
await fetch(`${API}/auth/logout`, { method: 'POST', credentials: 'include' })                       // this device
await fetch(`${API}/auth/logout`, { method: 'POST', credentials: 'include',
  headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ allDevices: true }) })   // every device
accessToken = null
```

- همیشه `204` ـه و کوکی پاک می‌شه، حتی وقتی کاربر از قبل بیرون بوده. `accessToken` لازم نداره.
- `accessToken` ِ مشتری تا ۱۵ دقیقه هنوز معتبره. برای همین باید از حافظه پاکش کنی. تو پنل، نشست تو هر درخواست چک می‌شه، پس خروج فوری اثر می‌کنه.

## ۶. تغییر شماره

۱. `POST /me/phone-change/request` با `{ "newPhone": "…" }`. یه کد به شماره‌ی فعلی می‌ره و یه کد به شماره‌ی جدید. جواب همیشه `200` ـه، حتی اگه شماره‌ی جدید مال حساب دیگه‌ای باشه.
۲. `POST /me/phone-change/verify` با `{ newPhone, currentPhoneCode, newPhoneCode }` و `credentials: 'include'`.
- موفقیت: نشست‌های همه‌ی دستگاه‌ها تموم می‌شن، این دستگاه یه `accessToken` و کوکی تازه می‌گیره، و یه پیامک هشدار به شماره‌ی قبلی می‌ره. `accessToken` ِ قبلی رو با جدید عوض کن.
- `422` با `meta.field` (`currentPhoneCode` یا `newPhoneCode`) می‌گه کدوم کد مشکل داره. کد درست ِ اون یکی مصرف نمی‌شه و دوباره قابل استفاده‌ست.
- `409 PHONE_ALREADY_REGISTERED` فقط بعد از درست بودن هر دو کد میاد.
- شماره‌ی قبلی آزاد می‌شه. ورود با اون، یه حساب **جدید** می‌سازه.

## ۷. پنل

- `401`: نشست تموم شده یا منقضیه. یه بار تمدید کن (بخش ۳)، و اگه نشد، برو به ورود.
- `403 FORBIDDEN`: نقش کافی نیست. صفحه‌ی «دسترسی نداری» نشون بده و کاربر رو بیرون نکن.
- نقش‌ها تو هر درخواست از سرور خونده می‌شن. تغییر نقش یه کارمند همه‌ی نشست‌هاش رو تموم می‌کنه، و ورود بعدی نقش جدید رو داره.
- نشست پنل ۱۲ ساعت بعد از آخرین استفاده تموم می‌شه، و حداکثر ۷ روز عمر داره.
- پنل باید زیر `kiva.ir` باشه (مثلاً `admin.kiva.ir`)، وگرنه کوکی `SameSite=Strict` فرستاده نمی‌شه.

## ۸. خطاها و رفتار UI

همه‌ی خطاها `application/problem+json` ان: `status`، `code`، `message` (فارسی، آماده‌ی نمایش)، `errors[]` (برای خطای هر فیلد)، `meta` و `traceId`.

| کد | کجا | چی نشون بدی |
|---|---|---|
| `VALIDATION_ERROR` با `errors[].field` | send، verify، `PATCH /me`، تغییر شماره | `message` ِ هر خطا زیر فیلد خودش (`PHONE_INVALID`، `OTP_CODE_FORMAT_INVALID`، `MESSENGER_REQUIRED`، `BIRTH_DATE_INVALID`، `PHONE_SAME_AS_CURRENT`) |
| `OTP_INVALID` (`422`) | verify | «کد اشتباهه»، و `meta.attemptsLeft` تلاش باقی مونده |
| `OTP_EXPIRED` (`422`) | verify | کد منقضی یا مصرف شده. دکمه‌ی «ارسال دوباره» |
| `OTP_TOO_MANY_ATTEMPTS` (`422`) | verify | این کد دیگه کار نمی‌کنه. کد جدید بگیر |
| `OTP_RESEND_TOO_SOON` (`429`) | send | تایمر رو از `meta.retryAfterSeconds` (یا هدر `Retry-After`) ادامه بده |
| `RATE_LIMITED` (`429`) | send، verify | «زیاد امتحان کردی»، و زمان از `meta.retryAfterSeconds`. بعد از ۱۰ کد اشتباه در روز، حتی کد درست هم تا پایان بازه رد می‌شه |
| `TOKEN_EXPIRED` (`401`) | هر جا | بی‌صدا تمدید کن و درخواست رو یه بار تکرار کن |
| `UNAUTHORIZED` (`401`) | هر جا | بدون توکن: ورود لازمه. با توکن: کلید سرور عوض شده یا توکن خرابه، پس مثل `TOKEN_EXPIRED` یه بار تمدید کن، و اگه نشد ورود |
| `REFRESH_TOKEN_INVALID` (`401`) | refresh | نشست تموم شده. حالت مهمان یا ورود |
| `FORBIDDEN` (`403`) | پنل، refresh یا logout از origin غریبه | «دسترسی نداری» |
| `PHONE_ALREADY_REGISTERED` (`409`) | تغییر شماره | «این شماره حساب دیگه‌ای داره» |
| `CHANGED_BY_SOMEONE_ELSE` (`409`) | `PATCH /me` | پروفایل رو دوباره بخون و فرم رو تازه کن |

خطای `5xx` رو با `traceId` نشون بده («کد پیگیری»). پشتیبانی با همین کد خط دقیقش رو تو لاگ پیدا می‌کنه.

## ۹. توسعه‌ی محلی

- بک‌اند روی `http://localhost:8080` و فرانت روی `http://localhost:5173`. دو پورت مختلف یه سایت حساب می‌شن، پس کوکی کار می‌کنه.
- پیامک واقعی فرستاده نمی‌شه. کد تو لاگ بک‌اند میاد، رو یه خط `WARN` از `LogSmsSender`.
- محدودیت‌ها واقعی‌ان: یه شماره هر ۱۲۰ ثانیه یه کد، ۵ کد در ساعت و ۱۰ کد در روز. برای تست سریع چند شماره عوض کن.

## ۱۰. هنوز نیست

| چی | کِی |
|---|---|
| حذف حساب (`POST /me/deletion/request`، `DELETE /me`) | بعد از فاز سفارش |
| `GET /me/dashboard` | فاز سفارش |
| ادغام سبد مهمان (`guestCartToken`) | فاز سبد. تا اون موقع `cart` همیشه `null` ـه |
| لیست دستگاه‌ها و خروج از یه دستگاه خاص | تو کانترکت endpoint نداره |
