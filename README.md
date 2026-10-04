# kiva-be

Backend of the Kiva shop. Kotlin · Ktor · Koin · Exposed · PostgreSQL · Flyway.

## Modules

| Module | Contains | Allowed to depend on |
|---|---|---|
| `domain/` | Entities, value objects, business rules, repository interfaces | nothing (pure Kotlin) |
| `application/` | Use cases | `domain` |
| root `src/` | Ktor, Koin, Exposed, Flyway: the infrastructure and bootstrap | `application`, `domain` |

## Requirements

- JDK 21 (in IntelliJ: *Settings → Build Tools → Gradle → Gradle JVM* = 21)
- PostgreSQL 15+ with a `kiva` database (or `docker compose up -d`)

## Run

```bash
./gradlew run
curl http://localhost:8080/health
```

Database settings come from `src/main/resources/application.conf` and can be overridden with
`KIVA_DB_URL`, `KIVA_DB_USER`, `KIVA_DB_PASSWORD` and `PORT`.

## Database migrations

Schema changes are SQL files in `src/main/resources/db/migration`, named `V1__create_product.sql`,
`V2__...`. Flyway applies the pending ones on startup. Never edit a migration that has already run;
add a new one instead.
