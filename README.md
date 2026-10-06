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
