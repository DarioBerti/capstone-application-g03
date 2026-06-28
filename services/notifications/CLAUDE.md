# Notifications Service — EuroTransit

> Guidance for working in `notifications`. Cross-cutting conventions live in the root [CLAUDE.md](../../CLAUDE.md).
> Principle-based on purpose: the code is the source of truth for endpoints and config keys — don't
> mirror those here.

## What this service is

Sends confirmations. It is **fully asynchronous and stateless**: it consumes events off Kafka and
delivers notifications. It has **no database**. Package `it.g03.notifications`. Its defining property
is graceful degradation — it lives at the edge of the system, never on the critical path.

## Invariants to protect

- **Must degrade, never cascade.** Checkout must succeed even if notifications are entirely down. This
  service must never be something the money path waits on or calls back into. Keep it a pure downstream
  consumer.
- **At-least-once, so be idempotent-friendly.** Assume events can be redelivered; a duplicate delivery
  should not cause a duplicate user-visible action where that matters. Don't assume exactly-once.
- **Statelessness is a feature.** No database, no schema. Don't introduce persistent state unless a
  real requirement forces it — and if it ever does, it must not change the "checkout succeeds even if
  notifications are down" guarantee.
- **Failure stays local.** A delivery failure (or a downstream mail provider being down) must be
  contained and logged, not propagated upstream.

## Do

- Route logic through a **Service** if real behaviour appears (Controller → Service → Repository),
  even though there's no repository today; keep any HTTP surface thin and demo/inspection-only.
- Consume events resiliently; make handlers safe under redelivery.
- Keep readiness reflecting only this service's own state — nothing on the money path should depend on
  it being ready.
- Test consumer/handler logic with **fakes**; Kafka listeners are disabled in tests via properties, so
  handlers can be tested without a broker.

## Don't

- Don't make any money-path service block on, retry against, or fail because of notifications.
- Don't call back into Orders/Inventory/Payments — this service is a sink, not a participant.
- Don't add a database/Flyway/JPA unless genuinely required (it would break the stateless contract).
- Don't assume exactly-once delivery or that an event arrives only once.

## Source layout

Organize by layer, **one repository per file**, keeping small cohesive types grouped (mirrors the Catalog reference layout):

- `service/` — delivery/handler logic (thin today; add as real behaviour appears)
- `events/` — Kafka listeners and any event payloads
- `controller/` — only if a thin inspection/demo HTTP surface is added

Stateless by contract: **no `repository/` or `entities/`** (no database). Keep
`NotificationsApplication.kt` at the package root.

## Configuration & testing

Stateless: **no datasource, no Flyway** — only Kafka and the standard observability/actuator config,
env-driven with local-dev defaults (see root CLAUDE.md). Kafka listeners are disabled in tests via
properties. `just test notifications` is the local Definition-of-Done gate.
