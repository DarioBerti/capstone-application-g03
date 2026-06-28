# Payments Service — EuroTransit

> Guidance for working in `payments`. Cross-cutting conventions live in the root [CLAUDE.md](../../CLAUDE.md).
> Principle-based on purpose: the code is the source of truth for endpoints, columns, and config keys —
> don't mirror those here.

## What this service is

Authorizes payment for an order on the money path. Orders calls it **synchronously** because the
decision must be made *now*; the AUTHORIZED/DECLINED answer travels back in the response and is the
saga's branch signal. Package `it.g03.payments`. The one rule above all others: **must not
double-charge** (Pillar B).

## Invariants to protect

- **Exactly one charge per order.** A retried or concurrently-duplicated authorize must charge once.
  Enforce with a **uniqueness constraint on the order key** plus fast-path replay of the stored
  outcome; on a concurrent race, exactly one insert commits and the loser rolls back (charge included)
  and replays. Don't weaken this to a check-then-insert.
- **DECLINED is a business outcome, not an error.** Return it as a normal success response with a
  status field. Returning an HTTP error for a decline would trip the caller's retries/circuit breaker
  for something that isn't a failure.
- **Events are informational.** The load-bearing signal is the synchronous response; any emitted event
  is published **after commit** and best-effort — losing one must not affect correctness.
- **The decision is deterministic and configurable.** The (mock) authorization rule is driven by
  config so the saga's compensation path can be exercised on demand. Inject failure/latency at the
  network/chaos layer, not by making the decision itself flaky.

## Do

- Route every change through **Controller → Service → Repository**; keep the controller HTTP-only.
- Return **DTOs**, never JPA entities; paginate any list/read endpoint (`Pageable` + `Specification`).
- Make authorize fully idempotent on the order key; expose a lookup so a charge can be verified once.
- Publish events **after** the transaction commits.
- Test idempotency under **real concurrency** on **Testcontainers PostgreSQL** — the
  unique-constraint race is materially more faithful on a real database than on H2.

## Don't

- Don't allow a path that can charge twice (no check-then-insert without the unique constraint as the
  real guard).
- Don't map a decline to a 4xx/5xx; don't leak whether a duplicate hit the replay path as an error.
- Don't make correctness depend on an event being delivered.
- Don't return raw entities; don't let Hibernate own the schema (`validate` only).
- Don't bake real PSP credentials or amounts into code — take them from config/secrets.

## Source layout

Organize by layer, **one repository per file**, keeping small cohesive types grouped (mirrors the Catalog reference layout):

- `controller/` — REST controllers + the `GlobalExceptionHandler` (`@RestControllerAdvice`)
- `dto/` — request/response DTOs, `ErrorResponse`, and the `PaymentAuthorization.toResponse()` mapper
- `service/` — authorization/business logic
- `repository/` — Spring Data repositories, **one repository per file**
- `entities/` — JPA entities (`PaymentAuthorization`), one per file
- `events/` — Kafka publishers and event payloads

Name each file after the type it holds; keep `PaymentsApplication.kt` at the package root.

## Configuration & testing

PostgreSQL everywhere, env-driven with local-dev defaults; Payments has a **dedicated** datasource
(financial / must-not-double-charge → blast-radius isolation). Flyway owns the schema; Hibernate
validates. Kafka publishing is disabled in tests via properties. Tests need Docker (Testcontainers);
`just test payments` is the local Definition-of-Done gate.
