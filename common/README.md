# کامن kiva: برگه‌ی تقلب

هر ابزار در یه خط، با یه مثال. نمونه‌ی کامل استفاده از همه‌شون با هم:
`src/test/kotlin/com/kiva/infrastructure/CommonShowcaseTest.kt`

## جعبه: `Result`

| ابزار | کارش | مثال |
|---|---|---|
| `Result.success(x)` / `Result.failure(f)` | ساختن جعبه‌ی سبز یا قرمز | `Result.failure(SkuFailure.Blank)` |
| `getOrNull()` / `failureOrNull()` | محتوا یا null (بیشتر تو تست) | `result.getOrNull()` |
| `mapOnSuccess { }` | محتوای جعبه‌ی سبز رو عوض کن | `.mapOnSuccess { BookDto.from(it) }` |
| `bind { }` | قدم بعدی که خودش جعبه می‌ده | `.bind { requirePositive(it) }` |
| `mapOnFailure { }` | برچسب جعبه‌ی قرمز رو عوض کن | `.mapOnFailure { InvalidQuantity(it) }` |
| `onSuccess { }` / `onFailure { }` | فقط یه نگاه، مثلاً لاگ | `.onFailure { logger.warn("$it") }` |
| `fold({ }, { })` | هر دو رنگ به یه مقدار | `.fold({ "ok" }, { "fail" })` |
| `unwrapOr { return ... }` | محتوا رو بده، اگه قرمز بود از تابع برو بیرون | `val id = X.makeNew(raw).unwrapOr { return Result.failure(it) }` |
| `Result.zip(a, b, ::Wrapper) { }` | چند ورودی با هم، **همه‌ی** خطاها رو جمع کن | فرم آدرس |
| `Result.zipAll(list, ::Wrapper)` | همون zip برای یه لیست هم‌جنس (تا هر تعداد) | رنگ‌های یه محصول |
| `forField("price")` | بگو این خطا مال کدوم فیلد فرمه | `Money.makeNew(toman = p).forField("price")` |

## خطا

```kotlin
sealed class SkuFailure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "sku") {
    data object Blank : SkuFailure("SKU_BLANK")          // code: حروف بزرگ و _
}
```

| `FailureKind` | HTTP | کِی |
|---|---|---|
| `INVALID` | 400 | فرمت ورودی غلطه (خالی، الگوی اشتباه، خارج از بازه) |
| `UNAUTHORIZED` | 401 | لاگین نکرده |
| `FORBIDDEN` | 403 | اجازه نداره |
| `NOT_FOUND` | 404 | پیدا نشد |
| `CONFLICT` | 409 | با داده‌ی موجود تداخل داره (تکراری، قیمت عوض شده) |
| `BUSINESS_RULE` | 422 | ورودی درسته ولی قانون بیزنس اجازه نمی‌ده (ناموجود، غیرقابل لغو) |
| `RATE_LIMITED` | 429 | زیادی تلاش کرده |

- `meta`: داده‌ی کمکی برای فرانت: `meta = mapOf("availableQuantity" to 3)`
- **پیام فارسی رو تو کد بیزنس ننویس.** فقط `code` بساز. متن فارسیش تو `infrastructure/common/http/ErrorMessages.kt` ـه.

`Result<T, Nothing>` یعنی «هیچ‌وقت شکست نمی‌خوره».

## UseCase و تراکنش

```kotlin
class AddProductUc(private val tx: TransactionManager, ...) : CommandUseCase<AddProductCmd, Long, AddProductFailure> {
    override suspend fun execute(command: AddProductCmd) = tx.inTransaction<Long, AddProductFailure> {
        ...                                   // 🟢 commit   🔴 rollback   💥 rollback + پرتاب
    }
}
```

- `CommandUseCase` چیزی رو عوض می‌کنه و **فقط id یا `Unit`** برمی‌گردونه.
- `QueryUseCase` فقط می‌خونه.
- `inTransaction` تودرتو به بیرونی می‌پیونده و فقط بیرونی‌ترین تصمیم می‌گیره. **جعبه‌ی قرمز رو هیچ‌وقت نادیده نگیر.**
- `private val logger = getLogger()`

## HTTP (تو `infrastructure/common/http`)

```kotlin
call.respondResult(result) { ProductHttpResponse.from(it) }        // 🟢 200 + JSON   🔴 status از kind
call.respondResult(result, HttpStatusCode.Created) { ... }         // 201
call.respondResult(unitResult)                                      // 🟢 204 بدون بدنه
call.respondFailure(failure)                                        // فقط خطا
```

همه‌ی routeهای API زیر `route(API_V1) { ... }` ان (`/api/v1`).

خطاها همیشه `application/problem+json`:
```json
{ "status": 422, "code": "QUANTITY_EXCEEDS_STOCK", "message": "فقط ۳ عدد از این کالا موجوده",
  "instance": "/api/v1/cart/items", "traceId": "4bf92f3577b34da6", "meta": { "availableQuantity": 3 } }
```
- خطای `INVALID` که فیلد داره: `400 VALIDATION_ERROR` + `errors[]` با مسیر کامل (`newAddress.postalCode`)
- بدنه‌ی خراب: `400 VALIDATION_ERROR` · غیر JSON: `415` · مسیر ناموجود: `404 NOT_FOUND`
- هر exception: `500 INTERNAL_ERROR` (جزئیات فقط تو لاگ، با همون `traceId`)

## صفحه‌بندی

```kotlin
// Repository: معنی هر گزینه‌ی sort تو دیتابیس
val sortOrders: SortOrders = mapOf("price_asc" to listOf(Products.price to SortOrder.ASC), ...)
Products.selectAll().paginate(pageable, sortOrders, tiebreaker = Products.id) { row -> ... }

// Route: قوانین همون endpoint از کانترکت
val pageable = call.pageable(allowedSorts = sortOrders.keys, defaultSort = "newest", defaultSize = 24, maxSize = 60)
    .unwrapOr { return@get call.respondFailure(it) }
call.respondResult(list.execute(pageable)) { page -> page.toHttpResponse { ItemHttpResponse.from(it) } }
```

- `?page=2&size=24&sort=price_asc`. صفحه از ۱ شروع می‌شه. پیش‌فرض و سقف `size` مال هر endpoint ـه (عمومی: ۲۰ و ۱۰۰).
- جواب: `{ items: [...], meta: { page, size, totalItems, totalPages, hasNext } }`
- `tiebreaker` اجباریه. بدونش، کالاها بین صفحه‌ها تکرار یا گم می‌شن.

## Value Objectها

| ابزار | کارش |
|---|---|
| `"۰۹۱۲".normalizeDigits()` | رقم فارسی و عربی رو انگلیسی کن (`"0912"`) |
| `"كيف".normalizePersian()` | برای جستجو: `ي→ی`، `ك→ک`، رقم‌ها، نیم‌فاصله و فاصله‌های اضافه |
| `IranianMobileNumber.makeNew(raw)` | هر شکل از شماره رو قبول می‌کنه و همیشه `09xxxxxxxxx` ذخیره می‌کنه (فیلد `phone`، کد `PHONE_INVALID`) |
| `Money.makeNew(toman = n)` | پول به **تومان** (`Long`)، هیچ‌وقت منفی. `+`، `* qty`، `.sum()`، مقایسه، و `toRials()` فقط برای درگاه |
| `Slug.makeNew(raw)` | آدرس خوانا (`mahak-shoulder-bag`): حروف کوچک، رقم و `-`، حداقل یه حرف، حداکثر ۱۰۰. ورودی رو عوض نمی‌کنه (کد `SLUG_INVALID`، بدون field) |
| `UserId.makeNew(id)` / `UserId.SYSTEM` | کی انجامش داده: شناسه‌ی حساب (از ۱)، یا `SYSTEM` (۰) وقتی آدمی پشتش نیست (seeder، کار زمان‌بندی‌شده). هیچ‌وقت id یه آدم واقعی رو جای سیستم نذار |

`constructFromPersisted(value)` برای خوندن از ذخیره‌سازی ـه (ردیف جدول، رویداد). ورودی کاربر همیشه از `makeNew` رد می‌شه و
خطاش `Failure` ـه. `init { require(...) }` دیوار دومه: مقدار خراب از **هر** راهی exception می‌ده، چون داده‌ی ذخیره‌شده‌ی خراب یعنی باگ.

## Event Sourcing (`com.kiva.common.event`)

نمونه‌ی کامل و کوچیک: `Lamp` تو `common/src/test/.../event/EventSourceEntityTest.kt`.

```kotlin
class Product private constructor() : EventSourceEntity<ProductDomainEvent>() {
    var id: ProductId by Delegates.notNull()        // value class: lateinit مجاز نیست
        private set

    constructor(events: List<ProductDomainEvent>) : this() { applyEventStream(events) }   // خوندن: replay

    fun archive(by: UserId): Result<Product, ArchiveFailure> {
        if (status == ARCHIVED) return Result.failure(ArchiveFailure.AlreadyArchived)    // اول قانون
        raiseEvent(ProductArchived(..., sequence = nextSequence(), occurredByUserId = by)) // بعد رویداد
        return Result.success(this)
    }

    override fun apply(event: ProductDomainEvent) { ... }   // فقط فیلد پر کن؛ نه قانون، نه ساعت
}
```

- `raiseEvent`: رویداد رو اعمال و ثبت می‌کنه. شماره‌ش باید `nextSequence()` باشه، وگرنه exception.
- `applyEventStream`: replay. رویدادهای replay‌شده دوباره ثبت نمی‌شن. جریان خالی، شماره‌ی جاافتاده یا تکراری، یا رویداد یه Aggregate دیگه، یعنی داده‌ی خراب و exception.
- `getRecordedEvents()`: رویدادهای تازه، همونی که Repository ذخیره می‌کنه.
- برخلاف کلاس پایه‌ی شرکت، شماره با هر رویداد جلو می‌ره. پس یه Command می‌تونه چند تا رویداد بسازه (۱، ۲، ۳).
- `ConcurrentChangeException`: دو نفر یه Aggregate رو خوندن و هر دو عوض کردن. اولی ذخیره می‌شه و دومی رد می‌شه، چون شماره‌ی رویدادش تکراریه (قید یکتای event store). Result نیست، چون کاربر کار اشتباهی نکرده؛ تراکنش rollback می‌شه و هیچی از دومی نمی‌مونه.
