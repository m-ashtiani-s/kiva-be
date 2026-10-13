# kiva-be

Backend of the Kiva shop. Kotlin · Ktor · Koin · Exposed · PostgreSQL · Flyway.

## Modules

| Module | Contains | Allowed to depend on |
|---|---|---|
| `common/` | Shared building blocks: Result, use case contracts, transactions, pagination, Money, mobile number. See [common/README.md](common/README.md) | nothing but Kotlin and slf4j-api |
| `domain/` | Entities, value objects, business rules, repository interfaces | `common` |
| `application/` | Use cases | `domain` |
| root `src/` | Ktor, Koin, Exposed, Flyway: the infrastructure and bootstrap | `application`, `domain`, `common` |

Framework-dependent helpers for the common pieces (respondResult, StatusPages, ExposedTransactionManager,
paginate) live in `src/main/kotlin/com/kiva/infrastructure/common`.

## Requirements

- JDK 21 (in IntelliJ: *Settings → Build Tools → Gradle → Gradle JVM* = 21)
- PostgreSQL 15+ with a `kiva` database (or `docker compose up -d`)
- For tests: a second database `kiva_test` (same user). Some tests write to it, so Postgres must be running
  before `./gradlew build`.

## Run

In IntelliJ: open `src/main/kotlin/com/kiva/infrastructure/Application.kt` and press ▶ next to `fun main`.
From a terminal:

```bash
./gradlew run
curl http://localhost:8080/health
```

Database settings come from `src/main/resources/application.conf` and can be overridden with
`KIVA_DB_URL`, `KIVA_DB_USER`, `KIVA_DB_PASSWORD` and `PORT`. Tests use `KIVA_TEST_DB_URL`,
`KIVA_TEST_DB_USER` and `KIVA_TEST_DB_PASSWORD` (defaults: local `kiva_test`, `kiva`/`kiva`).

The app doesn't start without its two login secrets. Make each one once (run the command twice: they must differ)
and keep them in *Run configuration → Environment variables* (they are never committed):

```bash
openssl rand -base64 32
```

| Variable | What |
| --- | --- |
| `KIVA_OTP_PEPPER` | Required. The key of the login codes' HMAC: base64 of at least 32 random bytes. |
| `KIVA_JWT_SECRET` | Required. The key access tokens are signed with: base64 of at least 32 random bytes, not the pepper. |
| `KIVA_SMS_PROVIDER` | `log` (the default) writes login codes to the log instead of sending them: development only. `faraz` sends them through Faraz SMS (below). |
| `KIVA_TRUSTED_PROXY_HOPS` | `1` only behind the shop's own nginx; `0` (the default) everywhere else. |
| `KIVA_BOOTSTRAP_ADMIN_PHONE` | Optional. This phone becomes ADMIN on startup, only while there is no ADMIN at all; remove it after the first start. |
| `KIVA_ALLOWED_ORIGINS` | Comma-separated frontends a browser may call the API from. Defaults to `localhost` and `127.0.0.1` on ports 3100 (kiva-fe), 3200 (the admin panel), 5173 and 3000; on a server, the shop's own `https://` addresses, the panel's (`https://admin.kiva.ir`) included. |

With the `log` provider, the code of `POST /api/v1/auth/otp/send` (and the two codes of a phone change) is in the log,
on a `WARN` line from `LogSmsSender`. Tests use their own fixed secrets and need none of these.

### SMS through Faraz SMS

With `KIVA_SMS_PROVIDER=faraz`, every SMS is a pattern approved in the Faraz SMS panel, sent through its web service
(`POST https://api.iranpayamak.com/ws/v1/sms/pattern`, the key in the `Api-Key` header; docs.iranpayamak.com). A message
whose pattern isn't set is not sent, and a `WARN` says so.

| Variable | What |
| --- | --- |
| `KIVA_FARAZ_API_KEY` | Required with `faraz`: the panel's access key. A secret: in the run configuration or the server's secret store, never in git; it is in no log line and not in any `toString`. The app doesn't start without it. |
| `KIVA_FARAZ_CODE_PATTERN` | The pattern of login and phone-change codes, «کد ورود: %code% / وبسایت کیوا». Default `vdXmsAfAQs`. Its variable is `code`. |
| `KIVA_FARAZ_LINE_NUMBER` | The line patterns are sent from (the panel's «خطوط»). Default `90008361`, the panel's shared service line. |
| `KIVA_FARAZ_PHONE_CHANGED_PATTERN` | Optional: «your account moved to the number ending in …», to the old phone after a phone change. Its variable is `ending`. |
| `KIVA_FARAZ_ORDER_PAID_PATTERN` | Optional: «پرداخت موفق». Its variable is `order` (the order's code, `KV-218340`); the link to follow it goes in the pattern's own text, `https://kiva.ir/track?order=%order%`. |
| `KIVA_FARAZ_BASE_URL` | The web service's address, default `https://api.iranpayamak.com`. |

A code that can't be sent makes `POST /auth/otp/send` answer 500, and the log says why (the provider's own words, with
no number or code in them).

### Logging in from a local frontend

- The frontend runs on one of the allowed origins above. Another origin gets no CORS headers, and `/auth/refresh` and
  `/auth/logout` answer it 403.
- Every call to `/api/v1/auth/*` goes with credentials (`fetch(url, { credentials: 'include' })`), or the browser
  neither keeps nor sends the `kiva_rt` cookie that holds the refresh token.
- The cookie is `Secure` and still works on `http://localhost`, because browsers treat localhost as secure. On any
  other plain-HTTP address (a phone on the LAN calling a laptop) the browser drops it, so the login doesn't stick.
- [docs/FRONTEND_AUTH.md](docs/FRONTEND_AUTH.md) is the whole flow for the frontend. [AUTH_REVIEW.md](AUTH_REVIEW.md)
  lists every attack the login defends against, with the test that proves each defense.

The SQL log (`Exposed` at DEBUG in `logback.xml`) shows each statement with `?` where its values go, so phone
numbers, names and emails stay out of the log.

To fill an empty local catalog with the prototype's 16 bags, start once with `KIVA_SEED_ENABLED=true`
(in IntelliJ: *Run configuration → Environment variables*). The seeder goes through the use cases, like real
data, and skips bags that are already there, so leaving it on is harmless. It also makes the prototype's three
discount codes (`KIVA10`, `WELCOME`, `PAEEZ15`) as rows, the way a server's codes are made by SQL until the panel
has discount codes. It is off by default and must stay off on a server.

### Buying: payments and jobs

| Variable | What |
| --- | --- |
| `KIVA_PAYMENT_PROVIDER` | `fake` (the default, development only) or `zarinpal`. With `fake`, no money moves: the "bank" is a page on this server (`/dev/fake-gateway/…`) with «پرداخت موفق» and «انصراف», and a `WARN` on startup says so. |
| `KIVA_ZARINPAL_MERCHANT_ID` | Required with `zarinpal`: the 36-character merchant id from the Zarinpal panel. A secret: never in git, never in a log. The app doesn't start without it, or with one that isn't a UUID. |
| `KIVA_ZARINPAL_SANDBOX` | `true` (the default) uses Zarinpal's test host; `false` on the server, for real money. |
| `KIVA_API_PUBLIC_URL` | This API's public address, without a `/` at the end (default `http://localhost:8080`). The gateway sends the shopper back to `{it}/api/v1/payment-callbacks/…`; on the server `https://api.kiva.ir`, whose domain must be the one registered with Zarinpal. |
| `KIVA_PAYMENT_RESULT_URL` | The frontend's page that shows how the payment went (default `http://localhost:3100/checkout/result`); `?paymentId=…` is added. On the server `https://kiva.ir/checkout/result`. |
| `KIVA_JOBS_ENABLED` | `true` (the default). Expiring unpaid orders (every minute), confirming payments left half-done (every 2 minutes), letting go of 4-day reservations (every minute), and cleaning up guests' carts (daily, 04:00 Tehran) and Idempotency-Keys (hourly). Keep it on, on the server too: without the jobs an unpaid order holds its stock for good. Tests turn it off and run the jobs themselves. |

The shop's own rules (shipping costs, free shipping, the payment window, the gateways on offer, reservations) are in
the database (`shopsettings_tbl`), not in the config. Locally, a shorter payment window shows an order expiring in a
few minutes: `UPDATE shopsettings_tbl SET "paymentPendingMinutes" = 5` (5 to 60; the default is 15), and back to 15
afterwards.

A local purchase end to end: start with `KIVA_SEED_ENABLED=true`, run kiva-fe on `http://localhost:3100`, log in with
the code from the `LogSmsSender` line, place an order, and press «پرداخت موفق» on the pretend bank's page; it sends
the browser back through the callback to the frontend's result page. [docs/FRONTEND_PURCHASE.md](docs/FRONTEND_PURCHASE.md)
is the whole flow for the frontend; [PURCHASE_REVIEW.md](PURCHASE_REVIEW.md) lists every risk the purchase defends
against, with the tests that prove each defense.

## Test

```bash
./gradlew build
```

`src/test/kotlin/com/kiva/infrastructure/CommonShowcaseTest.kt` is a complete small feature (add and list
products) built only from the common pieces. Use it as the template for new features.

## Database migrations

Schema changes are SQL files in `src/main/resources/db/migration`, named `V1__create_product.sql`,
`V2__...`. Flyway applies the pending ones on startup. Never edit a migration that has already run;
add a new one instead.
