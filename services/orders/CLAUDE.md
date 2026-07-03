# Orders Service — EuroTransit

> Guidance for working in `orders`. Cross-cutting conventions live in the root [CLAUDE.md](../../CLAUDE.md).
> Principle-based on purpose: the code is the source of truth for endpoints, columns, and config keys —
> don't mirror those here.

## What this service is

The purchase **orchestrator** and the entry point to the money path. A booking is one journey — an outbound
leg (required) plus an optional return leg. The user-driven half is synchronous — create a DRAFT, add legs
(each **reserves seats on-add**; the first starts a backend-owned hold window), then **pay** within the window
— and the reply-driven half is the async saga (authorize → confirm every held leg → capture once → CONFIRMED,
or a terminal REJECTED with a reason). Package `it.g03.orders`. This is the critical path — SLOs, tracing, and
chaos center here.

## Invariants to protect

- **Atomic business-change + event.** The state change and the event announcing it must commit together.
  Use the **transactional outbox** — never publish to Kafka inside business logic and hope the commit
  follows, and never let an event escape for a change that rolled back.
- **Every saga step is idempotent and replay-safe.** A redelivered reply (`reservation-result`,
  `payment-result`, `seats-confirmed`, `payment-captured`) or a re-driven command must not re-reserve,
  double-authorize, double-capture, or advance an already-terminal order. Guard on the current order/leg
  status; rely on downstream idempotency keys.
- **The hold window is backend-authoritative.** `expires_at` is stamped on the first leg, re-checked
  server-side at pay, and enforced by the checkout-expiry sweeper — never trust a client countdown. At expiry
  the reason is dual-cause on `payment_initiated_at`: user inaction (`EXPIRED_NO_PAYMENT`) vs system fault
  (`SERVICE_UNAVAILABLE`). Keep the two timers distinct: the timeout sweeper RECOVERS a stuck post-pay step
  within the window; the expiry sweeper GIVES UP at the window.
- **Multi-leg is all-or-nothing after pay.** `confirm-seats` fans out to every reserved leg and
  `capture-payment` fires exactly once, when the last leg confirms (serialized by a pessimistic order lock;
  capture is idempotent downstream so a rare double-fire is harmless). If any leg expires at confirm, release
  the already-confirmed legs, cancel the authorization (void, no capture → no refund), and reject. A sold-out
  *return* is a one-way fallback, not a rejection.
- **No DB transaction held across a remote call.** Reserve/authorize are network calls — keep them
  outside open transactions so a slow/failed downstream can't pin connections, and so the work is
  resumable on redelivery.
- **Cooperative async lifecycle.** Background work (the `@Scheduled` timeout sweeper) is Spring-managed and
  stops cleanly on shutdown/SIGTERM — in-flight work finishes, never orphaned or double-processed. The outbox
  is drained externally by Debezium CDC, not by an in-process relay.
- **Terminal states are final.** Once confirmed or rejected, an order doesn't change; compensation
  (e.g. release a reservation after a decline) happens through the saga, not by mutating a terminal order.

## Do

- Route every change through **Controller → Service → Repository**; keep the controller HTTP-only.
- Return **DTOs**, never JPA entities; paginate any list/read endpoint (`Pageable` + `Specification`).
- Make downstream calls **resilient**: timeouts, bounded retries with backoff + jitter, circuit
  breaker with a safe fallback, bulkheads. A downstream failure must be contained, never propagated as
  an unbounded hang. (Resilience4j is on the classpath; wire it deliberately around the real calls.)
- Drive the saga off events/state; treat the synchronous Payments decision as the branch signal.
- Test the saga and the outbox write path with **fakes** for downstream calls and Kafka, on Testcontainers
  PostgreSQL; make idempotency/replay a first-class test.

## Don't

- Don't dual-write (business row + Kafka publish) outside the outbox.
- Don't treat a downstream timeout/5xx as success, and don't retry without bounds (no retry storms).
- Don't let a DECLINE or a downstream outage corrupt state — branch the saga and compensate.
- Don't block the synchronous accept on the whole saga; accept, then process asynchronously.
- Don't hardcode collaborator addresses or business amounts — take them from config.

## Source layout

Organize by layer, **one repository per file**, keeping small cohesive types grouped (mirrors the Catalog reference layout):

- `controller/` — REST controllers + the `GlobalExceptionHandler` (`@RestControllerAdvice`)
- `dto/` — request/response DTOs, `ErrorResponse`, and the `Order.toResponse()` mapper
- `service/` — business logic (the saga, state machine, timeout sweeper)
- `repository/` — Spring Data repositories, **one repository per file** (split today's `Repositories.kt`)
- `entities/` — JPA entities (`Order`, `OutboxEvent`, …), one per file
- `events/` — Kafka listeners, publishers, event payloads
- `gateway/` — outbound sync HTTP clients to Inventory/Payments

Name each file after the type it holds; keep `OrdersApplication.kt` at the package root.

## Configuration & testing

PostgreSQL everywhere, env-driven with local-dev defaults; Orders has a **dedicated** datasource (money
path → blast-radius isolation). Flyway owns the schema (its own service schema); Hibernate only
validates. Kafka publish/consume is disabled in tests via properties. Tests need Docker
(Testcontainers); `just test orders` is the local Definition-of-Done gate.
