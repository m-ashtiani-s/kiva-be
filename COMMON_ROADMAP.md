# نقشه‌ی راه کامن kiva

> هدف: ابزارهای پایه‌ای که تو `library-be` از کامن شرکت **استفاده** کردی، این بار **ساخته بشن**: کوچیک، تست‌شده و مال خودت.
> هر جلسه که تموم می‌شه، تیکش زده می‌شه و یه خط به «گزارش کار» آخر فایل اضافه می‌شه.

## قوانین کامن

1. **کوچیک.** فقط چیزی ساخته می‌شه که شاپ همین الان لازمش داره. «شاید بعداً لازم بشه» دلیل نوشتن نیست.
2. **دو بخش جدا:**
   - **ماژول `common`**: Kotlin خالص، بدون Ktor و Exposed. `domain` و `application` بهش وابسته‌ان.
   - **`src/.../infrastructure/common/`**: تیکه‌هایی که فریمورک لازم دارن، مثل `respondResult` و `ExposedTransactionManager`.
3. **هر تابع عمومی تست داره**، هم مسیر موفق و هم شکست.
4. **کامن شرکت فقط منبع ایده‌ست.** کد ازش کپی نمی‌شه. اول خودت طراحی می‌کنی، بعد با اون مقایسه می‌کنیم.
   - سورس کامن شرکت رو از پروژه‌ی `library-be` تو IntelliJ با `Ctrl+Click` روی هر کلاس می‌بینی (نسخه‌ی 4.4.10).

## روش هر جلسه

```
۱) خوندن ایده (کامن شرکت)  →  ۲) جواب سؤال‌های طراحی (تو)  →  ۳) طراحی نهایی با هم
→  ۴) کد + تست (تو)  →  ۵) ریویو (من)  →  ۶) ./gradlew build سبز  →  ۷) کامیت + تیک
```

سؤال‌های طراحی **قبل از** نوشتن کد جواب داده می‌شن. «نمی‌دونم» هم جواب قابل‌قبولیه، چون همون‌جا با هم بازش می‌کنیم.

---

## ساختار نهایی

```
common/src/main/kotlin/com/kiva/common/
├── railway/        Result, ResultFailure, NoFailure, Failure
├── usecase/        CommandUseCase, QueryUseCase
├── transaction/    TransactionManager
├── pagination/     Page, Pageable, Sort
├── text/           normalizeDigits
└── valueobject/    Money, IranianMobileNumber

src/main/kotlin/com/kiva/infrastructure/common/
├── persistence/    ExposedTransactionManager, handlePageable
└── http/           FailureHttpResponse, respondResult, StatusPages, pageable از query param, PageHttpRes
```

---

## جلسه‌ی ۰: ماژول `common` ✅

**می‌نویسی:**
- ماژول Gradle به اسم `common` (`settings.gradle.kts` و `common/build.gradle.kts`)
- `domain` با `api(project(":common"))` بهش وابسته بشه

**سؤال طراحی:**
- چرا `api` و نه `implementation`؟ اگه `implementation` بذاریم، کجا خراب می‌شه؟

**معیار پذیرش:**
- [x] `./gradlew build` سبز
- [x] `common` هیچ وابستگی‌ای جز Kotlin و JUnit (برای تست) نداره

---

## جلسه‌ی ۱: `Result`، بخش اول (ساختار) ✅

**بخون:** `Result.kt` کامن شرکت، **فقط** تعریف کلاس و `Success`/`Failure`. اون ۳۴ تا `zip` رو رد کن.

**سؤال‌های طراحی** (همون سه سؤال قبلی + دو تا جدید):
1. `Result<out T, out F>`: اون `out` چی کار می‌کنه؟ چرا `Success` از `Result<T, Nothing>` ارث می‌بره؟
2. چرا `sealed`؟ اگه `sealed` نباشه، `when` چه مشکلی پیدا می‌کنه؟
3. `F` باید محدود باشه (`F : ResultFailure`) یا هر چیزی می‌تونه باشه؟ مزیت و هزینه‌ی هر کدوم چیه؟

**می‌نویسی:**
- `sealed class Result<out T, out F : ResultFailure>` با `Success(value)` و `Failure(failure)`
- سازنده‌ها: `Result.success(x)`، `Result.failure(f)`
- `isSuccess`، `isFailure`، `getOrNull()`، `failureOrNull()`

**معیار پذیرش:**
- [x] تست: یه `Result<Int, X>` بدون cast به یه `Result<Number, X>` اختصاص داده بشه (اثبات `out`)
- [x] تست: `when` روی Result بدون شاخه‌ی `else` کامپایل بشه
- [x] **`getOrThrow` وجود نداره**، و دلیلش رو بتونی توضیح بدی

---

## جلسه‌ی ۲: `Result`، بخش دوم (تبدیل‌ها)

**بخون:** تو `Result.kt` کامن شرکت، `mapOnSuccess`، `mapOnFailure`، `bind`، `onSuccess`، `onFailure`، `fold` و `unwrapOr`.

**سؤال‌های طراحی:**
1. فرق `mapOnSuccess` و `bind` چیه؟ یه مثال از `library-be` بزن که `map` اشتباه بود و `bind` لازم بود.
2. `unwrapOr` با الگوی `.let { when ... }` که تو `GetBookUc` نوشتی چه ربطی داره؟
3. چرا `unwrapOr` باید `inline` باشه؟ (راهنما: `return` از داخل lambda)
4. `onSuccess` و `onFailure` چی برمی‌گردونن؟ چرا خود Result رو برمی‌گردونن؟

**می‌نویسی:**
- `mapOnSuccess`، `mapOnFailure`، `bind`، `onSuccess`، `onFailure`، `fold`، `unwrapOr`
- `zip` برای ۲ ورودی، برای ترکیب نتیجه‌ی دو تا Value Object

**معیار پذیرش:**
- [ ] برای هر تابع دو تست: روی `Success` و روی `Failure`
- [ ] تست: `bind` بعد از اولین `Failure`، lambdaهای بعدی رو **اجرا نکنه** (با یه شمارنده ثابتش کن)
- [ ] تست: `unwrapOr { return ... }` داخل یه تابع، از خود تابع بیرون بزنه

---

## جلسه‌ی ۳: مدل خطا

**بخون:** `ResultFailure.kt`، `NoFailure.kt` و `ParvanFailure.kt` تو کامن شرکت. ببین `ParvanFailure` چند تا سازنده داره و `causes` چطور پر می‌شه.

**سؤال‌های طراحی:**
1. از ۸ فیلد `ParvanFailure`، کدوم‌ها رو نگه می‌داریم؟ (پیشنهاد من: `code`، `field`، `value`، `causes`). دلیل حذف بقیه؟
2. **خطا چطور به status code در HTTP تبدیل می‌شه؟** کامن شرکت این تصمیم رو تو **هر Route** با یه lambda (`mapFailureStatusCode`) می‌گیره. راه دیگه اینه که خود Failure یه `kind` داشته باشه (`VALIDATION`، `NOT_FOUND`، `CONFLICT`، ...). کدوم؟ چرا؟
3. `value` (ورودی خراب کاربر) تو پاسخ HTTP برگرده یا نه؟ (فکر کن: اگه ورودی پسورد یا کد OTP باشه چی؟)
4. نام‌گذاری `code`: `BookIdIsBlank` یا `book.id.blank`؟ یه قرارداد انتخاب کن.

**می‌نویسی:**
- `abstract class ResultFailure(val failure: Failure)`
- `object NoFailure`
- `data class Failure(code, field, value, causes, [kind])`

**معیار پذیرش:**
- [ ] تست: یه Failure با `cause` ساخته بشه و `code` لایه‌ی داخلی از بیرون قابل‌خوندن باشه (همون الگوی `cause` تو `library-be`)
- [ ] قرارداد نام‌گذاری `code` تو KDoc نوشته بشه

---

## جلسه‌ی ۴: UseCase و Logger

**بخون:** `CommandUseCase.kt` و `QueryUseCase.kt` کامن شرکت.

**سؤال‌های طراحی:**
1. چرا Command خروجی `Unit` داره و Query خروجی داره؟ اگه بعد از ثبت سفارش، شماره‌ی سفارش رو لازم داشته باشیم چی؟
2. چرا `execute` تابع `suspend` ـه؟

**می‌نویسی:**
- `CommandUseCase<C, R, F>` و `QueryUseCase<Q, R, F>`، بر اساس جواب سؤال ۱
- `getLogger()` با slf4j-api (تنها وابستگی خارجی مجاز کامن)

**معیار پذیرش:**
- [ ] یه UseCase نمونه تو تست که هر دو interface رو پیاده کنه و صدا زده بشه

---

## جلسه‌ی ۵: تراکنش

**بخون:** `TransactionManager.kt` و `ExposedTransactionManager.kt` کامن شرکت. **دقت کن** وقتی تراکنشی از قبل باز باشه چی می‌شه.

**سؤال‌های طراحی:**
1. چرا `TransactionManager` تو `common` ـه، ولی پیاده‌سازیش تو infrastructure؟
2. امضای `performInTransaction(block: (Transaction) -> T)` نوع Exposed رو به UseCase نشون می‌ده. چطور جلوی این نشت رو بگیریم؟
3. تراکنش تودرتو: اگه داخلی خطا بده و بیرونی ادامه بده، چی باید بشه؟
4. فرق نسخه‌ی عادی و `suspend` چیه؟ JDBC روی کدوم thread اجرا بشه؟

**می‌نویسی:**
- `common`: `TransactionManager` با `inTransaction` و `inSuspendTransaction`
- infrastructure: `ExposedTransactionManager` + ثبتش تو Koin
- دیتابیس `kiva_test` برای تست‌ها

**معیار پذیرش** (تست روی Postgres واقعی):
- [ ] خطا وسط تراکنش، همه‌چیز rollback بشه
- [ ] تراکنش تودرتو **یه تراکنش** باشه: اگه داخلی خطا بده، نوشته‌های بیرونی هم برگرده
- [ ] **اگه block یه `Result.Failure` برگردونه، rollback بشه یا نه؟** تصمیمش رو بگیر و براش تست بنویس

---

## جلسه‌ی ۶: لبه‌ی HTTP

**بخون:** `respondResult` (تو `MessageLoader.kt`)، `FailureHttpResponse.kt` و `installStatusPages` کامن شرکت.

**سؤال‌های طراحی:**
1. شکل JSON خطا: چه فیلدهایی؟ `traceId` لازمه؟
2. exception غیرمنتظره: کاربر چی ببینه و لاگ چی ثبت کنه؟
3. JSON خراب یا فیلد اجباری خالی تو بدنه‌ی درخواست 400 برگردونه، نه 500. چطور؟

**می‌نویسی** (تو infrastructure):
- `FailureHttpResponse`
- `respondResult(result, successStatus) { mapper }`، با تبدیل status بر اساس تصمیم جلسه‌ی ۳
- StatusPages: `BadRequestException` و خطای deserialization به 400، و بقیه‌ی `Throwable`ها به 500

**معیار پذیرش** (تست با `testApplication`):
- [ ] `Failure` به 4xx با `code` درست تبدیل بشه
- [ ] JSON خراب 400 برگردونه
- [ ] exception پرت‌شده 500 برگردونه، و **stack trace و پیام exception تو پاسخ نباشه**، فقط تو لاگ

---

## جلسه‌ی ۷: صفحه‌بندی

**بخون:** `Page`، `Pageable`، `Sort`، `getPageableFromQueryParams`، `handlePageable` و `PageHttpRes` تو کامن شرکت.

**سؤال‌های طراحی:**
1. صفحه از ۰ شروع بشه یا ۱؟
2. اگه کاربر `size=1000000` بفرسته؟
3. اگه کاربر `sort=password` بفرسته؟ (sort فقط روی ستون‌های **مجاز**)
4. `totalElements` یه `COUNT(*)` جدا لازم داره. کی ارزشش رو داره؟

**می‌نویسی:**
- `common`: `Page<T>`، `Pageable`، `Sort`
- infrastructure: خوندن `?page&size&sort`، `Query.paginate(pageable, allowedSorts)` و `PageHttpRes`

**معیار پذیرش:**
- [ ] `size` بیشتر از حداکثر به حداکثر محدود بشه، یا 400 برگردونه (یکی رو انتخاب کن)
- [ ] sort روی ستون غیرمجاز 400 برگردونه
- [ ] تست روی `kiva_test`: ۲۵ ردیف، صفحه‌ی ۳ با `size=10`، ۵ ردیف و `totalElements=25`

---

## جلسه‌ی ۸: Value Objectهای مشترک

**بخون:** `MoneyIRR.kt`، `IranianMobileNumber.kt` و `normalizeDigits` تو کامن شرکت.

**سؤال‌های طراحی:**
1. `Money` با `Long` (ریال) یا `BigDecimal`؟ (پیشنهاد من: `Long`). اگه تخفیف ۱۵٪ روی ۹۹۹ ریال بخوره، گرد کردن کجا و چطور انجام بشه؟
2. جمع دو `Money` بزرگ overflow بشه، چی برگرده؟
3. چه شکل‌هایی از شماره موبایل قبول بشه؟ `0912...`، `912...`، `+98912...`، `0098912...`، با رقم فارسی، با فاصله و خط تیره؟ شکل ذخیره‌شده‌ی نهایی چیه؟

**می‌نویسی:**
- `normalizeDigits`: تبدیل رقم فارسی و عربی به انگلیسی
- `IranianMobileNumber.of(raw): Result<IranianMobileNumber, ...>`
- `Money.ofRials(Long): Result<Money, ...>` + `plus`، `times(qty)` و `compareTo`

**معیار پذیرش:**
- [ ] `"۰۹۱۲ ۱۲۳ ۴۵۶۷"` و `"+989121234567"` به **یه مقدار** برسن
- [ ] شماره‌ی ۱۰ رقمی یا با پیش‌شماره‌ی غیر `9` رد بشه
- [ ] `Money` منفی ساخته نشه. overflow تشخیص داده بشه (`Math.addExact`)

---

## جلسه‌ی ۹: جمع‌بندی

- [ ] مرور کل API کامن: چیزی هست که هیچ‌جا استفاده نشده؟ پاکش کن
- [ ] KDoc کوتاه برای هر تابع عمومی
- [ ] یه مسیر نمونه‌ی واقعی با کامن خودت: `GET /health` یا یه endpoint تست، که از `respondResult` استفاده کنه
- [ ] کامیت نهایی کامن

---

## چیزهایی که عمداً **نمی‌نویسیم**

| تو کامن شرکت | چرا نه |
|---|---|
| `getSuccessOrException` و `getFailureOrException` | Result رو به exception تبدیل می‌کنه و جواب 500 می‌ده (تله‌ی تسک ۷) |
| ۳۴ نسخه‌ی `zip` | فقط `zip` با ۲ ورودی، بقیه وقتی واقعاً لازم شد |
| `ContextHolder`، `UserContextPlugin` | کاربر فعلی صریحاً از Route به Command پاس داده می‌شه |
| `initKtor` و DSLش | `Application.kt` صریح خودمون رو داریم |
| `ParvanLogger` | مستقیم slf4j |
| `RequestSanitizer`، `htmlEscape` | دفاع درست: کوئری پارامتری + اعتبارسنجی تو VO + escape موقع نمایش |
| i18n، `Localization` | فرانت از روی `code` پیام فارسی نشون می‌ده |
| `EventSourceEntity` و ماژول `event` | event sourcing و Kafka نداریم |
| `JalaliDate`، `UserLocalized*` | سرور `Instant` نگه می‌داره و فرانت شمسی نشون می‌ده |
| `PageUtil`، caching، geometry، ecourier-base، excel | شاپ لازمشون نداره |

## بعداً، وقتی شاپ بهش رسید (نه تو این نقشه)

`wrapInOneResult` (موقع checkout)، `UpdatableAuditInfo`، `Address` ساخت‌یافته، serializer برای `Instant`، JWT، X-Forwarded، job پس‌زمینه، Outbox، http-client.

---

## گزارش کار

| تاریخ | جلسه | چی شد |
|---|---|---|
| 2026-10-04 | - | فهرست کامن شرکت (4.7.0) بررسی شد و این نقشه نوشته شد |
| 2026-10-04 | ۰ | ماژول `common` ساخته شد و `domain` با `api` بهش وصل شد. build سبزه و زنجیره‌ی root → application → domain → common برقراره |
| 2026-10-04 | ۱ | `Result` (sealed، `out T`/`out F`، `Success`/`Failure`، `success`/`failure`، `isSuccess`/`isFailure`، `getOrNull`/`failureOrNull`) + `ResultFailure` خالی نوشته شد. ۱۱ تست سبز. با حذف آزمایشی `out` و `sealed`، خطای کامپایلر ثابت کرد که هر دو لازمن. این جلسه رو من نوشتم و توضیح دادم |
