# Payments Service — EuroTransit

> Guidance for working in `payments`. Cross-cutting conventions live in the root [CLAUDE.md](../../CLAUDE.md).
> Principle-based on purpose: the code is the source of truth for endpoints, columns, and config keys —
> don't mirror those here.

## What this service is

Authorizes then captures payment for an order on the money path. Orders drives it **asynchronously over
Kafka** (commands `authorize-payment` / `capture-payment` / `cancel-authorization`; replies
`payment-result` / `payment-captured` written to a transactional **outbox** drained by Debezium CDC). The
AUTHORIZED/DECLINED outcome is the saga's branch signal. The PSP is pluggable behind a **`PaymentGateway`**
(config `payments.provider`): the real **PayPal sandbox** (Orders v2, server-to-server card flow) in-cluster,
or a deterministic **mock** for dev/tests. Package `it.g03.payments`. The one rule above all others: **must
not double-charge** (Pillar B).

## Invariants to protect

- **Exactly one charge per order.** A retried or concurrently-duplicated authorize must charge once.
  Enforce with a **uniqueness constraint on the order key** plus fast-path replay of the stored
  outcome; on a concurrent race, exactly one insert commits and the loser rolls back (charge included)
  and replays. Don't weaken this to a check-then-insert.
- **Compensation is order-independent (cancel tombstone).** `cancel-authorization` and `authorize-payment`
  arrive on **separate topics with no cross-topic ordering**. A cancel for an order with no prior
  authorization inserts a **VOIDED tombstone** (amount 0) instead of failing; a later `authorize` that finds
  a VOIDED row refuses to charge and replies DECLINED. Either ordering converges to VOIDED — never a dangling
  AUTHORIZED hold. `UNIQUE(order_id)` is the arbiter (no `@Version` needed: every case is an insert race).
- **DECLINED is a business outcome, not an error.** A processor decline is a valid AUTHORIZED/DECLINED
  result the saga branches on — never an exception. A **transient** transport failure (5xx / timeout /
  network) is the opposite: throw `TransientPspException` so the transaction rolls back and the Kafka
  retry/DLT + timeout sweeper re-drive; do NOT record it as a DECLINE.
- **Replies are load-bearing and transactional.** The outcome travels back as a Kafka reply written to the
  **outbox in the same transaction** as the state change (Debezium drains it, at-least-once). Re-emit the
  reply on idempotent replay so a lost reply self-heals — correctness must not depend on a single delivery.
- **The PSP lives behind a gateway.** `PaymentGateway` (`payments.provider` = `paypal` | `mock`) abstracts
  the provider; the service maps its result onto AUTHORIZED/DECLINED and stores the PSP references
  (`pspReference` = authorization id, `captureReference` = capture id). PayPal calls are made idempotent via
  the `PayPal-Request-Id` header on top of the `UNIQUE(order_id)` guard. The mock's threshold rule stays
  config-driven so the compensation path can be exercised on demand; inject real failure/latency at the
  network/chaos layer, not by making the decision itself flaky.

## Do

- Route every change through **Controller → Service → Repository**; keep the controller HTTP-only.
- Return **DTOs**, never JPA entities; paginate any list/read endpoint (`Pageable` + `Specification`).
- Make authorize/capture/cancel fully idempotent on the order key; expose a lookup so a charge can be verified once.
- Write replies to the **transactional outbox inside** the state-change transaction (Debezium drains them);
  re-emit the reply on idempotent replay.
- Keep the outbound PSP call **bounded** (connect/read timeouts + Resilience4j `@Retry`/`@CircuitBreaker`);
  a slow/broken PayPal must surface as a retryable throw, never an unbounded hang.
- Test idempotency under **real concurrency** on **Testcontainers PostgreSQL** (the unique-constraint race is
  materially more faithful than H2); stub PayPal with **MockRestServiceServer** (provider defaults to `mock`).

## Don't

- Don't allow a path that can charge twice (no check-then-insert without the unique constraint as the
  real guard).
- Don't turn a transient PSP/transport failure into a DECLINE — throw so the saga re-drives; only a real
  processor decline is DECLINED.
- Don't make correctness depend on a single reply delivery (rely on idempotent replay + the sweeper).
- Don't return raw entities; don't let Hibernate own the schema (`validate` only).
- Don't bake real PSP credentials or amounts into code — take them from config/secrets (the `paypal-api`
  SealedSecret in-cluster; `${PAYPAL_*}` placeholders in `application.yml`).

## Source layout

Organize by layer, **one repository per file**, keeping small cohesive types grouped (mirrors the Catalog reference layout):

- `controller/` — REST controllers + the `GlobalExceptionHandler` (`@RestControllerAdvice`)
- `dto/` — request/response DTOs, `ErrorResponse`, and the `PaymentAuthorization.toResponse()` mapper
- `service/` — authorization/business logic
- `repository/` — Spring Data repositories, **one repository per file**
- `entities/` — JPA entities (`PaymentAuthorization`), one per file
- `events/` — Kafka listeners and event payloads
- `gateway/` — the outbound PSP seam: the `PaymentGateway` contract + result types/exceptions and the mock
  provider, with all **PayPal-specific** files (client, token provider, DTOs, RestClient config) grouped under
  `gateway/paypal/`

Name each file after the type it holds; keep `PaymentsApplication.kt` at the package root.

## Configuration & testing

PostgreSQL everywhere, env-driven with local-dev defaults; Payments has a **dedicated** datasource
(financial / must-not-double-charge → blast-radius isolation). Flyway owns the schema; Hibernate
validates. Kafka listeners are disabled in tests via properties and PayPal is stubbed (provider defaults to
`mock`), so no broker or live PSP is needed. The PSP provider is selected by `payments.provider` (`mock` in
dev/tests; `paypal` in-cluster, wired via the config repo's `paypal-api` secret). Tests need Docker
(Testcontainers); `just test payments` is the local Definition-of-Done gate.
