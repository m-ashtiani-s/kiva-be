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
| `KIVA_SMS_PROVIDER` | `log` (the default) writes login codes to the log instead of sending them: development only. |
| `KIVA_TRUSTED_PROXY_HOPS` | `1` only behind the shop's own nginx; `0` (the default) everywhere else. |
| `KIVA_BOOTSTRAP_ADMIN_PHONE` | Optional. This phone becomes ADMIN on startup, only while there is no ADMIN at all; remove it after the first start. |
| `KIVA_ALLOWED_ORIGINS` | Comma-separated frontends a browser may call the API from. Defaults to `localhost` and `127.0.0.1` on ports 5173 and 3000; on a server, the shop's own `https://` addresses. |

With the `log` provider, the code of `POST /api/v1/auth/otp/send` (and the two codes of a phone change) is in the log,
on a `WARN` line from `LogSmsSender`. Tests use their own fixed secrets and need none of these.

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
data, and skips bags that are already there, so leaving it on is harmless. It is off by default and must stay
off on a server.

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
