# CLAUDE.md

Guidance for AI coding assistants working in this repository.

## Attribution

- Do **not** add `Co-Authored-By:` trailers, `Generated with Claude Code` footers, or
  any reference to an AI assistant in commit messages, PR descriptions, code comments
  or documentation.
- Authorship of this repository belongs solely to its owner.

## Scope

`ledger-core` is a double-entry ledger service. Its single responsibility is recording
balanced financial transactions exactly once, with an auditable entry log and an
at-least-once event stream for downstream consumers.

Out of scope: payment orchestration, KYC, currency conversion, reporting UI.

## Commands

```bash
./gradlew build                       # compile + unit tests
./gradlew test                        # all tests (integration tests need Docker)
./gradlew test --tests '*Test'        # unit tests only
./gradlew test --tests '*IT'          # integration tests only
./gradlew bootRun --args='--spring.profiles.active=local'

docker compose up -d postgres kafka   # dependencies only
docker compose up --build             # full stack
docker build -t ledger-core:local .
```

## Architecture rules

Hexagonal. Dependencies point inward, always:

```
adapter.inbound  ->  application  ->  domain  <-  application  <-  adapter.outbound
```

- `domain/` imports nothing outside the Kotlin stdlib and `java.time`. No Spring, no
  Jackson, no JDBC. If you need an annotation here, the design is wrong.
- `application/` imports `domain` only. Transaction boundaries go through the
  `UnitOfWork` port, never `@Transactional`.
- `adapter/` may import anything. This is the only layer allowed to know about HTTP,
  SQL or Kafka.
- Ports live in `application/port/{input,output}`. Not `in`/`out` — those are Kotlin
  soft keywords and force backticks at every import site.

## Code style

- `val` and `data class` by default. A `var` in the domain layer needs a comment
  explaining why.
- Constructor injection only. No field injection, no `@Autowired` on properties.
- Money is `Long` minor units wrapped in `Money`. Never `Double`, never raw `BigDecimal`.
- Validate at the edge: bean validation on request DTOs, domain invariants in
  constructors and factory methods.
- Structured logging via `log.atInfo().addKeyValue(...)`. Never string-interpolate
  values into log messages — they become ungreppable in JSON output.
- No `!!`. If a value can be null, model it.

## Testing rules

- Unit tests (`*Test`): no Spring context, no database. MockK for ports.
- Integration tests (`*IT`): extend `AbstractIntegrationTest`, real Postgres and Kafka
  via Testcontainers. **H2 is banned** — deferred constraint triggers, `FOR UPDATE SKIP
  LOCKED` and generated columns do not exist there, so a green H2 suite would be a lie.
- Any change to locking, idempotency or the balance projection requires a concurrency
  test that would fail without it.
- Test names are full sentences describing behaviour, not method names.

## Non-negotiable invariants

Changing any of these requires an ADR in `docs/adr/`:

1. Entries are append-only. Corrections are reversing transactions, never UPDATE/DELETE.
2. Every transaction sums to zero, enforced in the domain **and** by the deferred
   constraint trigger `ledger_entry_must_balance`.
3. Accounts are locked in ascending id order. Any other order reintroduces deadlocks.
4. Balance is read only after the row lock is held.
5. The outbox row is written in the same transaction as the entries.
6. An idempotency key is bound to a payload fingerprint. A key reused with a different
   payload is a 409, never a silent success.

## Database

- Flyway, forward-only. Never edit a migration that has been merged to `main`.
- Constraints that protect money belong in the schema, not only in Kotlin.
