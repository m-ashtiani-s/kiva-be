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
| `unwrapOr { return ... }` | محتوا رو بده، اگه قرمز بود از تابع برو بیرون | `val id = X.of(raw).unwrapOr { return Result.failure(it) }` |
| `Result.zip(a, b, ::Wrapper) { }` | چند ورودی با هم، **همه‌ی** خطاها رو جمع کن | فرم آدرس |
| `forField("price")` | بگو این خطا مال کدوم فیلد فرمه | `Money.ofRials(p).forField("price")` |

## خطا

```kotlin
sealed class SkuFailure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "sku") {
    data object Blank : SkuFailure("SKU_BLANK")          // code: حروف بزرگ و _
}
```

| `FailureKind` | HTTP |
|---|---|
| `INVALID` | 400 |
| `NOT_FOUND` | 404 |
| `CONFLICT` | 409 |
| `UNAUTHORIZED` | 401 |
| `FORBIDDEN` | 403 |

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

- بدنه‌ی خراب: `400 MALFORMED_REQUEST`
- هر exception: `500 INTERNAL_ERROR` (جزئیات فقط تو لاگ)

## صفحه‌بندی

```kotlin
// Route
val pageable = call.pageable(allowedSorts = sortColumns.keys).unwrapOr { return@get call.respondFailure(it) }
call.respondResult(list.execute(pageable)) { page -> page.toHttpResponse { ItemHttpResponse.from(it) } }

// Repository
Table.selectAll().paginate(pageable, sortColumns, tiebreaker = Table.id) { row -> ... }
```

- `?page=2&size=20&sort=-price,name`. صفحه از ۱ شروع می‌شه و `size` حداکثر ۱۰۰ ـه.
- `tiebreaker` اجباریه. بدونش، کالاها بین صفحه‌ها تکرار یا گم می‌شن.

## Value Objectها

| ابزار | کارش |
|---|---|
| `"۰۹۱۲".normalizeDigits()` | رقم فارسی و عربی رو انگلیسی کن (`"0912"`) |
| `IranianMobileNumber.of(raw)` | هر شکل از شماره رو قبول می‌کنه و همیشه `09xxxxxxxxx` ذخیره می‌کنه |
| `Money.ofRials(n)` | پول به ریال (`Long`)، هیچ‌وقت منفی. `+`، `* qty`، `.sum()` و مقایسه داره، و overflow باعث توقف می‌شه |
