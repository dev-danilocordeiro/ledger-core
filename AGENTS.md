# AGENTS.md

Instructions for coding agents working in this repository. `CLAUDE.md` holds the same rules in
more detail and stays the source of truth; if the two ever disagree, follow `CLAUDE.md` and fix
this file. Every command below was run on `main` and passes.

## Setup

- JDK 21 (Gradle toolchain in `build.gradle.kts`, `temurin 21` in CI). Use the wrapper
  (`./gradlew`), never a system Gradle.
- Docker running: integration tests start their own Postgres and Kafka with Testcontainers.
- Kotlin, Spring Boot 4.1, plain `JdbcClient` (no JPA), Flyway, Kafka.

## Build and test

```bash
./gradlew test --rerun --tests '*Test'   # unit tests only: no Spring context, no Docker (~10s)
./gradlew test --rerun --tests '*IT'     # integration tests: real Postgres + Kafka via Testcontainers
./gradlew test --rerun                   # everything; this is what CI runs (./gradlew --no-daemon test)
./gradlew build                          # compile + all tests (unit and integration) + jar
docker build -t ledger-core:local .      # the image CI builds after the tests
```

- The Gradle build cache is on (`org.gradle.caching=true`): without `--rerun`, an unchanged
  `test` task is restored `FROM-CACHE` and nothing actually runs. Use `--rerun` when you need
  the tests to execute.
- Integration tests need Docker but **not** `docker compose`: Testcontainers picks random ports.
- There is no linter or formatter task (no ktlint, detekt or spotless). Follow
  `kotlin.code.style=official` and the style of the surrounding code.

## Running the service locally

```bash
docker compose up -d postgres kafka     # dependencies only
./gradlew bootRun --args='--spring.profiles.active=local'
docker compose up --build               # full stack (ledger, postgres, kafka, prometheus)
```

The compose file publishes fixed ports (5432, 9092, 8080, 9090). If 5432 is already taken on the
machine, `postgres` won't start; the tests are unaffected.

## Architecture

Hexagonal. Dependencies point inward:
`adapter.inbound -> application -> domain <- application <- adapter.outbound`.

- `domain/` imports only the Kotlin stdlib and `java.time`: no Spring, Jackson or JDBC.
- `application/` imports `domain` only. Transactions go through the `UnitOfWork` port, never
  `@Transactional`.
- `adapter/` is the only layer that knows about HTTP, SQL or Kafka.
- Ports live in `application/port/{input,output}` (not `in`/`out`).

## Domain rules (non-negotiable)

Changing any of these needs an ADR in `docs/adr/`, which is a decision for a human, not for an
agent:

1. Entries are append-only. Corrections are reversing transactions, never UPDATE/DELETE.
2. Every transaction sums to zero, in the domain **and** in the deferred constraint trigger
   `ledger_entry_must_balance`.
3. Accounts are locked in ascending id order.
4. Balance is read only after the row lock is held.
5. The outbox row is written in the same transaction as the entries.
6. An idempotency key is bound to a payload fingerprint; the same key with a different payload is
   a 409, never a silent success.

Money and data:

- Money is `Long` minor units (cents) wrapped in `Money`. Never `Double`, never raw `BigDecimal`.
- Migrations are Flyway and forward-only: add a new `V<n>__*.sql`, never edit one merged to
  `main`. Constraints that protect money belong in the schema, not only in Kotlin.

## Code and tests

- `val` and `data class` by default; a `var` in the domain needs a comment. No `!!`.
- Constructor injection only. Structured logging with `log.atInfo().addKeyValue(...)`, never
  values interpolated into the message.
- Unit tests (`*Test`): no Spring, no database, MockK for ports. Integration tests (`*IT`):
  extend `AbstractIntegrationTest`. **H2 is banned.**
- A change to locking, idempotency or the balance projection needs a concurrency test that fails
  without it. Test names are full sentences.
- No AI attribution anywhere (commits, PRs, comments, docs). See `CLAUDE.md`.

## Don't touch

Unless the issue explicitly asks for it:

- `.github/` (CI), `Dockerfile`, `docker/HealthCheck.java`, `docker-compose.yml` and
  `docs/prometheus.yml`.
- Migrations already on `main` (`src/main/resources/db/migration/`).
- The invariants above and `docs/adr/`.
- Gradle wrapper (`gradle/`, `gradlew*`), plugin versions and dependencies in
  `build.gradle.kts`: a new dependency is a decision for a human.
