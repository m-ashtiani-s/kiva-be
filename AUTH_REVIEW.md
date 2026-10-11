# ریویوی ورود و حساب: گام ۱۱، حمله‌ی آزمایشی و جمع‌بندی

> برنچ `auth`، بعد از گام ۱۰ (`a1af1c2`)، ۱۸ مهر ۱۴۰۵. پایه: سند «معماری ورود و حساب کیوا»، بخش‌های ۲ و ۱۴.
> این فایل جواب سؤال «این قانون چرا اینجاست و از کجا بدونم هنوز کار می‌کنه؟» رو برای ورود و حساب می‌ده.

---

## خلاصه

**وضعیت کلی: آماده برای وصل شدن فرانت.** هر حمله‌ی جدول بخش ۲ تست داره، و همه‌ی نگهبان‌هایی که عمداً خراب شدن، تستشون قرمز شد. دو نشت تو لاگ پیدا و درست شد. دو جای خالی هم تو تست‌ها بود که پر شد.

| چی بررسی شد | نتیجه |
|---|---|
| `./gradlew test --rerun-tasks` | ✅ **۱۲۱۰ تست**، همه سبز (common ۲۰۰، domain ۴۷۹، application ۹۹، اصلی ۴۳۲). گام ۱۱، ۷ تست اضافه کرد |
| هشدار کامپایلر | ✅ صفر |
| اسکن لاگ (یه ورود کامل، تمدید، پروفایل، تغییر شماره، جستجوی ادمین، بدنه‌ی خراب) | ⚠️ **شماره‌ی کامل تو لاگ SQL بود** (۱۸، ۱۴ و ۳۰ خط در سه سناریو). درست شد (بخش ۲) |
| خرابکاری عمدی: ۱۶ نگهبان، هر کدوم جدا | ✅ همه قرمز شدن. **یکی اول تستی نداشت** (`FOR UPDATE` ِ نشست) که حالا داره (بخش ۳) |
| هر ردیف جدول بخش ۲ حداقل یه تست | ✅ **«CSRF: فقط POST» تست نداشت** که اضافه شد |
| دور واقعی با مرورگر | ✅ ورود، رفرش صفحه، دو تب (با قفل و بدون قفل)، و خروج (بخش ۴) |

تغییرهای کد این گام:

| فایل | چی |
|---|---|
| `persistence/SqlWithoutValuesLogger.kt` (جدید)، `DatabaseFactory.kt` | لاگ SQL به‌جای مقدارها `?` می‌نویسه |
| `common/http/StatusPages.kt` | لاگ خطا مسیر رو بدون query string می‌نویسه |
| `AuthLogScanOnDatabaseTest` (جدید) | اسکن لاگ، با کدهای انتخاب‌شده (`AuthTestStack` حالا `OtpCodes` رو از بیرون می‌گیره) |
| `WebV1AuthRefreshOnDatabaseTest` | دو تست جدید: فقط POST، و تمدید هم‌زمان با باطل شدن نشست |
| `HttpEdgeTest` | لاگ خطا query string نداره |
| `README.md` | `KIVA_ALLOWED_ORIGINS`، ورود از فرانت محلی، لاگ SQL |
| `docs/FRONTEND_AUTH.md` (جدید) | پیام نهایی برای فرانت |

---

## ۱. جدول بخش ۲: هر حمله و تستی که ازش محافظت می‌کنه

هر ردیف حداقل یه تست داره. ستون آخر می‌گه با خرابکاری عمدی (بخش ۳) ثابت شده که اون تست واقعاً قرمز می‌شه یا نه.

| حمله | دفاع | تست‌هایی که ازش محافظت می‌کنن | خرابکاری |
|---|---|---|---|
| **حدس زدن کد** | ۵ تلاش برای هر کد، ۵ دقیقه عمر، ۱۰ کد اشتباه در روز برای هر شماره | `OtpChallengeTest` › five wrong codes count down to exhausted · the right code a second before five minutes is accepted, and five minutes and a second after is expired<br>`WebV1AuthVerifyOnDatabaseTest` › the fifth wrong code is OTP_TOO_MANY_ATTEMPTS, and after it even the right code is · the tenth wrong code in a day locks the phone, even for a fresh right code, and OTP_LOCKED is recorded | ✅ |
| **درخواست‌های همزمان** | قفل ردیف کد با `SELECT … FOR UPDATE` | `WebV1AuthVerifyOnDatabaseTest` › twenty wrong codes at the same moment take exactly five tries · twenty logins with the right code at the same moment make one session, and the others are OTP_EXPIRED | ✅ |
| **پاک شدن شمارنده** | الگوی Outcome: داخل تراکنش فقط موفقیت، Failure بعد از commit | `WebV1AuthVerifyOnDatabaseTest` › a wrong code is 422 OTP_INVALID with 4 tries left, and the try is in the database, not rolled back<br>`WebV1AuthRefreshOnDatabaseTest` › the old token after 30 seconds was copied, the session is revoked, the new token dies too, and it is recorded | ✅ |
| **پیامک‌بازی** | حد IP، حدهای شماره، قفل advisory، سقف کل ساعتی | `WebV1AuthSendOnDatabaseTest` › the 21st request from one address in ten minutes is 429, and a made-up X-Forwarded-For changes nothing · the sixth code in an hour is 429 RATE_LIMITED · the eleventh code in a day is 429 RATE_LIMITED, though no hour had more than five · ten sends to one phone at the same moment send one SMS · at the shop's hourly cap no code goes out, with a 429, an ERROR in the log and a security event | ✅ |
| **شناسایی مشتری‌ها** | send اصلاً سراغ کاربر نمی‌ره. «حساب داره» فقط بعد از تأیید کد | `WebV1AuthSendOnDatabaseTest` › a phone with an account and one without get the same answer, but for the phone itself<br>`SendLoginCodeUcTest` › it can't look at users at all<br>`WebV1PhoneChangeOnDatabaseTest` › a new phone of another account is not told by the request, only by both right codes, with 409 | — |
| **دزدی accessToken** | عمر ۱۵ دقیقه. تو پنل، نشست در هر درخواست چک می‌شه | `JwtAccessTokensTest` › a token carries the user, the session, the roles and the phone, for 15 minutes<br>`AuthenticationTest` › a token past its 15 minutes is 401 TOKEN_EXPIRED, so the client refreshes<br>`RequireRolesOnDatabaseTest` › a session logged out or revoked shuts the panel at once, though its token is still valid | — |
| **دزدی refreshToken** | کوکی HttpOnly، چرخش با تشخیص استفاده‌ی دوباره، سقف عمر | `RefreshCookieTest` › the cookie is HttpOnly, Secure, SameSite=Strict and only for the login endpoints, as the contract writes it<br>`AuthSessionTest` › a token rotated up to 30 seconds ago is a second tab, rotated longer ago it was copied<br>`WebV1AuthRefreshOnDatabaseTest` › the old token after 30 seconds was copied, … · a customer refreshing every 29 days stays logged in until day 180, and not after · a panel session lasts 12 hours unused, and 7 days however often it is refreshed · five refreshes with one cookie at the same moment all get an access token, and only one a new cookie · **a refresh that meets a revoke not committed yet waits for it, and never brings the session back** (جدید) | ✅ |
| **CSRF** | `SameSite=Strict`، فقط POST، چک Origin | `RefreshCookieTest` › the cookie is HttpOnly, Secure, SameSite=Strict …<br>`WebV1AuthRefreshOnDatabaseTest` › refresh and logout answer only our frontends, so another site can't use the cookie · **refresh and logout take only a POST, so a link or an image on another page can't reach them** (جدید) | ✅ |
| **جعل JWT** | الگوریتم ثابت، secret حداقل ۳۲ بایت، iss و aud، نقش پنل از دیتابیس | `AuthenticationTest` › a changed signature, alg none, another key or another audience is 401 UNAUTHORIZED · a forged token that has also expired is UNAUTHORIZED, never TOKEN_EXPIRED<br>`JwtAccessTokensTest` › a token whose roles were changed is refused · a secret shorter than 32 bytes is refused<br>`RequireRolesOnDatabaseTest` › a role taken away shuts the panel at once, though the token still names it | ✅ |
| **تصاحب حساب با تغییر شماره** | کد هر دو شماره، باطل شدن همه‌ی نشست‌ها، پیامک هشدار به شماره‌ی قبلی | `WebV1PhoneChangeOnDatabaseTest` › with both codes the account moves, every session ends, this device gets a new one, and the old phone is warned · only the new phone's code right is 422 OTP_INVALID on currentPhoneCode, nothing changes, and the right code stays usable · the current phone's code moves the account only to the new phone it was sent for | — |
| **ارتقای دسترسی** | فقط ADMIN نقش می‌ده، کسی نقش خودش رو عوض نمی‌کنه، دو تست نگهبان | `RouteGuardsTest` › every endpoint under me and admin refuses a call without a token · every admin endpoint takes exactly the roles of its x-roles in the contract<br>`WebV1AdminCustomersOnDatabaseTest` › an admin can't change their own roles, and an operator can't use this at all · admin and support see the customers, an operator doesn't<br>`UserTest` › nobody changes their own roles | ✅ |
| **لو رفتن دیتابیس** | کد: HMAC با pepper. refresh: SHA-256 از ۲۵۶ بیت | `WebV1AuthSendOnDatabaseTest` › the stored code is a salted hash of the code in the SMS, never the code itself<br>`WebV1AuthVerifyOnDatabaseTest` › the tokens themselves are in no table, only the refresh token's SHA-256<br>`HmacOtpCodesTest` › another salt or another pepper gives another hash · a pepper shorter than 32 bytes is refused | — |
| **لو رفتن لاگ** | کد، توکن، کوکی و secret هیچ‌وقت لاگ نمی‌شن، شماره ماسک می‌شه، و یه تست کل لاگ رو اسکن می‌کنه | **`AuthLogScanOnDatabaseTest`** (جدید، ۴ تست)<br>**`HttpEdgeTest`** › the log names the path without its query string, which can be a customer's phone (جدید)<br>`OtpCodeTest` › the code never shows in a string, so a log by mistake writes stars · `VerifyLoginCodeUcTest` › a command shows neither the phone nor the code · `RefreshAndLogoutUcTest` › their commands never show the token · `JwtAccessTokensTest` › an issued token never shows in a string · `RandomSessionTokensTest` › a new token never shows in a string | ✅ |
| **جعل IP** | `X-Forwarded-For` فقط پشت پروکسی خودمون، و فقط آخرین hop | `WebV1AuthSendOnDatabaseTest` › the 21st request from one address in ten minutes is 429, and a made-up X-Forwarded-For changes nothing · behind our nginx the address is the last X-Forwarded-For entry, the one nginx wrote | ✅ |
| **قفل کردن حساب دیگران** (ریسک پذیرفته‌شده) | حداکثر ۲۴ ساعت، و رویداد امنیتی | `OtpSendLimitsTest` › the eleventh code in a day is refused until the oldest of them is a day old · ten wrong codes in a day, over several codes, stop new codes until enough of them are a day old<br>`WebV1AuthVerifyOnDatabaseTest` › the tenth wrong code in a day … and OTP_LOCKED is recorded | — |

---

## ۲. نشت‌های لاگ که پیدا و درست شد

### ✅ لاگ SQL شماره‌ی کامل رو می‌نوشت

`logback.xml` لاگر `Exposed` رو روی DEBUG گذاشته، هم برای توسعه و هم برای سرور. لاگر پیش‌فرض Exposed مقدارها رو **داخل** SQL می‌نویسه، پس هر ورود چند خط مثل این داشت:

```
DEBUG Exposed - SELECT pg_advisory_xact_lock(hashtext('otp:09900000005'))
```

ویرایش پروفایل هم اسم و ایمیل رو می‌نوشت. حالا `SqlWithoutValuesLogger` همون SQL رو با `?` می‌نویسه. شکل کوئری همچنان دیده می‌شه، ولی مقدارها نه.

**از کجا بفهمی درست شده:** `AuthLogScanOnDatabaseTest`. بدون این اصلاح، سه سناریوش به‌ترتیب با ۱۸، ۱۴ و ۳۰ خط شماره‌ی کامل قرمز می‌شن. تست لاگر Exposed رو هر طور که `logback.xml` بگه روی DEBUG می‌ذاره، پس اگه کسی بعداً لاگ SQL رو خاموش کنه، باز هم این مسیر چک می‌شه.

### ✅ لاگ خطا query string رو می‌نوشت

`StatusPages` برای ۴۰۰، ۴۱۵، ۴۰۹ و ۵۰۰ کل `uri` رو لاگ می‌کرد. جستجوی ادمین (`/admin/customers?q=09121234567`) یعنی شماره‌ی کامل تو لاگ. حالا فقط مسیر لاگ می‌شه، که کافیه، چون هر UseCase ورودی‌های خودش رو امن لاگ می‌کنه.

**از کجا بفهمی:** `HttpEdgeTest` › the log names the path without its query string, which can be a customer's phone.

### چیزهایی که سالم بودن

- لاگ هر درخواست Ktor (`CallLogging`) فقط مسیر رو می‌نویسه، نه query رو (تو کد Ktor 3.6 چک شد).
- بدنه‌ی خراب: پیام `BadRequestException` فقط «Failed to convert request body to …» ـه. متن JSON (با شماره و کد) فقط تو cause ـه که لاگ نمی‌شه. اسکن لاگ این رو با یه بدنه‌ی نصفه‌ی verify ثابت می‌کنه.
- همه‌ی Cmdها، `OtpCode`، توکن‌ها و `User` تو `toString` چیزی لو نمی‌دن (از گام‌های قبل).
- هدرهای `Authorization` و `Cookie` هیچ‌جا لاگ نمی‌شن (`io.ktor` روی INFO ـه).

---

## ۳. خرابکاری عمدی

هر نگهبان یه بار جدا خراب شد، کل تست‌ها اجرا شدن، و کد برگشت. هیچ‌کدوم از این خرابکاری‌ها commit نشده.

| نگهبان | خرابکاری | تستی که قرمز شد |
|---|---|---|
| قفل ردیف کد (`FOR UPDATE`) | `.forUpdate()` از `findOpenForUpdate` حذف شد | `WebV1AuthVerifyOnDatabaseTest` › twenty logins with the right code at the same moment make one session, … · twenty wrong codes at the same moment take exactly five tries |
| قفل advisory شماره موقع ارسال | `pg_advisory_xact_lock` حذف شد | `WebV1AuthSendOnDatabaseTest` › ten sends to one phone at the same moment send one SMS |
| قفل ردیف refresh token | `.forUpdate()` از `findTokenForUpdate` حذف شد | `WebV1AuthRefreshOnDatabaseTest` › five refreshes with one cookie at the same moment all get an access token, and only one a new cookie |
| قفل ردیف نشست | `.forUpdate()` از `findByIdForUpdate` حذف شد | ❌ **اول هیچ تستی قرمز نشد.** ✅ حالا: `WebV1AuthRefreshOnDatabaseTest` › a refresh that meets a revoke not committed yet waits for it, and never brings the session back |
| Outcome در verify | کد اشتباه داخل تراکنش `Failure` برگردوند | `WebV1AuthVerifyOnDatabaseTest` › a wrong code is 422 OTP_INVALID with 4 tries left, and the try is in the database, not rolled back · the fifth wrong code … · the tenth wrong code in a day … · twenty wrong codes at the same moment … |
| Outcome در تمدید (باطل‌سازی دزدی) | تمدید ردشده داخل تراکنش `Failure` برگردوند | `WebV1AuthRefreshOnDatabaseTest` › the old token after 30 seconds was copied, the session is revoked, the new token dies too, and it is recorded |
| تشخیص استفاده‌ی دوباره | توکن چرخیده همیشه «تب دوم» حساب شد | `AuthSessionTest` › a token rotated up to 30 seconds ago is a second tab, rotated longer ago it was copied · `WebV1AuthRefreshOnDatabaseTest` › the old token after 30 seconds was copied, … |
| چک Origin روی refresh | `requireAllowedOrigin` حذف شد | `WebV1AuthRefreshOnDatabaseTest` › refresh and logout answer only our frontends, so another site can't use the cookie |
| چک Origin روی logout | `requireAllowedOrigin` حذف شد | همون تست |
| `authenticate` روی یه route | `GET /me` از بلوک `authenticate` بیرون اومد | `RouteGuardsTest` › every endpoint under me and admin refuses a call without a token · سه تست `WebV1MeOnDatabaseTest` · `AuthLogScanOnDatabaseTest` › a login with a wrong try, … |
| ثابت بودن الگوریتم JWT | verifier الگوریتم رو از هدر توکن گرفت (`alg: none` با `Algorithm.none()`) | `AuthenticationTest` › a changed signature, alg none, another key or another audience is 401 UNAUTHORIZED |
| `x-roles` ِ پنل | OPERATOR به `GET /admin/customers` راه داده شد | `RouteGuardsTest` › every admin endpoint takes exactly the roles of its x-roles in the contract · `WebV1AdminCustomersOnDatabaseTest` › admin and support see the customers, an operator doesn't |
| `X-Forwarded-For` فقط پشت nginx | هدر همیشه قبول شد | `WebV1AuthSendOnDatabaseTest` › the 21st request from one address in ten minutes is 429, and a made-up X-Forwarded-For changes nothing |
| ماسک شماره تو لاگ | `SendLoginCodeUc` شماره‌ی کامل رو لاگ کرد | سه سناریوی `AuthLogScanOnDatabaseTest` |
| لاگ SQL بدون مقدار | لاگر پیش‌فرض Exposed برگشت | سه سناریوی `AuthLogScanOnDatabaseTest` |
| لاگ خطا بدون query | `uri` برگشت | `HttpEdgeTest` › the log names the path without its query string, … |

### ❌ → ✅ قفل نشست تست نداشت

`RefreshSessionUc` اول توکن و بعد نشست رو با `FOR UPDATE` می‌خونه. قفل توکن دو تمدید با یه کوکی رو پشت هم می‌ذاره. ولی قفل نشست جلوی چیز دیگه‌ای رو می‌گیره: «خروج از همه‌جا»، تغییر نقش یا تغییر شماره، که نشست رو باطل می‌کنن و هنوز commit نشدن. بدون قفل، تمدید نشست رو هنوز زنده می‌بینه. ذخیره‌ی خودش (`UPDATE … SET "revokedOn" = NULL …`) هم منتظر می‌مونه و بعد از commit ِ اون یکی، باطل‌سازی رو پاک می‌کنه. یعنی نشستی که کاربر بسته بود، زنده می‌شه.

تست جدید این مسابقه رو قطعی می‌سازه: باطل‌سازی رو نگه می‌داره، صبر می‌کنه تا تمدید تو `pg_stat_activity` منتظر قفل دیده بشه، و بعد commit می‌کنه. بدون قفل، تمدید `refreshed` جواب می‌گیره، نه `REFRESH_TOKEN_INVALID`.

### یه درس از خرابکاری JWT

اولین نسخه‌ی خرابکاری هیچ تستی رو قرمز نکرد. دلیلش خود خرابکاری بود، نه تست‌ها. verifier ِ `none` با ساعت سیستم ساخته شده بود، و تست‌ها با ساعت ثابت `2026-10-09` کار می‌کنن، پس توکن به‌خاطر انقضا رد می‌شد. نسخه‌ی واقعی این اشتباه با ساعت برنامه ساخته می‌شه، و اون تست رو قرمز کرد.

`JwtAccessTokensTest` › a token that says alg none is refused فقط خود verifier رو چک می‌کنه. اشتباه تو تنظیم Ktor رو فقط تست HTTP ِ `AuthenticationTest` می‌گیره. پس هر دو لازم‌ان.

---

## ۴. دور واقعی با مرورگر

یه صفحه‌ی آزمایشی موقت (بیرون از ریپو، commit نشده) روی `http://localhost:5173`، و بک‌اند روی `http://localhost:8080` با secretهای موقت و `KIVA_SMS_PROVIDER=log`. مرورگر: Chromium داخل اپ Claude. صفحه همون کد `docs/FRONTEND_AUTH.md` رو اجرا می‌کرد (`credentials: 'include'`، `navigator.locks`). بخش دو تب با `element.click()` اجرا شد، چون پنل مرورگر روی صفحه نبود. ولی همون `fetch`ها تو همون مرورگر اجرا شدن.

| قدم | نتیجه |
|---|---|
| باز شدن صفحه بدون کوکی | تمدید بی‌صدا `401 REFRESH_TOKEN_INVALID` و حالت مهمان. CORS جواب رو به صفحه رسوند |
| ارسال کد و تأیید (کد از خط `WARN` ِ `LogSmsSender`) | `200` با `isNewUser = true`، `expiresIn = 900` و `refreshExpiresIn = 2592000`. بعدش `GET /me` جواب `200` داد |
| رفرش صفحه | تمدید بی‌صدا `200`. مرورگر کوکی `Secure` رو روی `http://localhost` نگه داشت و از پورت 5173 به 8080 فرستاد (`SameSite=Strict`) |
| `document.cookie` | خالی. جاوااسکریپت کوکی رو نمی‌بینه |
| تب دوم | با کوکی مشترک، بی‌صدا وارد شد |
| تمدید هم‌زمان دو تب **با** قفل | هر دو `200`. دو چرخش، ۲۳ میلی‌ثانیه پشت هم |
| تمدید هم‌زمان دو تب **بدون** قفل | هر دو `200`. **فقط یه چرخش**، و تب دیگه جواب مهلت ۳۰ ثانیه رو گرفت. بعدش `GET /me` تو هر دو تب `200` داد |
| خروج تو تب اول | `204` و حالت مهمان. نشست با `LOGOUT` باطل شد |
| تب دوم بعد از خروج | `GET /me` با accessToken ِ قبلی هنوز `200` ـه (تا ۱۵ دقیقه، طبق کانترکت)، ولی تمدید `401 REFRESH_TOKEN_INVALID` و حالت مهمان |
| دیتابیس | ۶ توکن برای نشست، ۵ چرخیده، هر کدوم فقط هش ۳۲ بایتی. رویدادها: `USER_REGISTERED`، `LOGIN_SUCCEEDED` و `LOGOUT` |
| لاگ برنامه تو کل این دور | شماره‌ی کامل و کد فقط تو خط `LogSmsSender` (که فقط تو توسعه هست). ۵۶ خط SQL، همه با `?`. هیچ `Bearer`، `kiva_rt=` یا `Authorization` |

این دور، migrationهای V8 تا V10 رو روی دیتابیس محلی `kiva` اجرا کرد. یه حساب آزمایشی هم با شماره‌ی `09900000101` ساخت (user 1). رویدادهای امنیتی‌ش فقط‌اضافه‌ان و پاک نمی‌شن.

---

## ۵. چیزهایی که می‌مونه

### 🟢 یادداشت‌ها

- **خطای SQL که از تراکنش بیرون بزنه.** Exposed اون رو با مقدارهاش تو یه خط WARN (`Transaction attempt #… failed … Statement(s): …`) و همراه `Detail` ِ Postgres (مثلاً `Key (phone)=(…)`) لاگ می‌کنه. `StatusPages` هم پیام exception رو تو خط «Unhandled error» می‌نویسه. این فقط برای باگ یا قطعی اتفاق می‌افته. مسابقه‌هایی که انتظارشون رو داریم (شماره‌ی گرفته‌شده، کلید stream) داخل Repository گرفته می‌شن، قبل از این مرحله. اسکن لاگ این مسیر رو پوشش نمی‌ده. اگه لازم شد، یه لایه‌ی آخر ماسک تو logback (مثلاً `%replace` روی الگوی موبایل) اضافه کن.
- **تموم شدن سهمیه‌ی روزانه‌ی ارسال یه شماره** رویداد امنیتی ثبت نمی‌کنه. فقط `OTP_LOCKED` (۱۰ کد اشتباه) و `SMS_HOURLY_CAP_REACHED` ثبت می‌شن، چون جدول رویدادهای سند نوعی برای این نداره. اگه پشتیبانی بخواد «کسی سهمیه‌ی من رو تموم کرد» رو ببینه، یه `OTP_SEND_LIMIT_REACHED` اضافه کن.
- **تغییر شماره تست همزمانی جدا نداره.** `ConfirmPhoneChangeUc` از همون `findOpenForUpdate` ِ ورود استفاده می‌کنه، و تست‌های همزمانی ورود همین متد رو پوشش می‌دن.
- **چک‌لیست بخش ۱۵ سند** (secretها تو secret store، پنل پیامک واقعی، HTTPS و HSTS، `KIVA_ALLOWED_ORIGINS` ِ سرور، پاک کردن `KIVA_BOOTSTRAP_ADMIN_PHONE`، jobهای پاک‌سازی، هشدار روی `SMS_HOURLY_CAP_REACHED` و `REFRESH_REUSE_DETECTED`) مال فاز انتشاره و هنوز بازه.

---

## چطور تکرارش کنی

```bash
# 1) every test, really run (no cache)
./gradlew test --rerun-tasks

# 2) only the log scan
./gradlew :test --tests "*AuthLogScanOnDatabaseTest"
```

برای خرابکاری: یکی از ردیف‌های جدول بخش ۳ رو دستی تو کد اعمال کن، `./gradlew test --continue` بزن، ببین همون تست قرمز می‌شه، و کد رو برگردون.
