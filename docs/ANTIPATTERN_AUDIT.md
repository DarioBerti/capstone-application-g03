# Microservice Anti-Pattern Audit — EuroTransit (G03)

**Scope:** the five services under `services/` (catalog, inventory, orders, payments,
notifications) plus the GitOps config in `../capstone-configuration-g03`.

**Method:** static review of source, build files, Spring config (`application*.yml`),
Flyway migrations, and Helm values, cross-checked against five classic microservice
anti-patterns.

**Headline:** the codebase **deliberately avoids all five anti-patterns**. The only
actionable items are minor (a handful of `!!`) or already tracked as Phase 2 debt
(resilience decorators), plus one optional config-consistency cleanup.

## Verdict summary

| Anti-pattern | Verdict |
|---|---|
| Shared Database | ✅ Not violated — DB-per-service (cosmetic config inconsistency only) |
| Bleeding Bounded Contexts | ✅ Not violated |
| Synchronous Cascades | ⚠️ Present but intentional; one real gap, documented as Phase 2 debt |
| Field Injection | ✅ Not violated in production (test fixtures only) |
| "Java in Kotlin" | ✅ Resolved — the 5 `!!` usages have been fixed |

---

## 1. Shared Database — NOT violated

Each service owns its own database in-cluster; there are no cross-service table reads,
no cross-schema foreign keys, and no shared credentials.

- Helm provisions one database per service —
  `capstone-configuration-g03/deploy/charts/eurotransit/values.yaml:131-167`:
  `catalogdb`, `ordersdb`, `inventorydb`, `paymentsdb`, each with its own owner.
- Postgres datasource URLs confirm the isolation:
  - `services/orders/src/main/resources/application-postgres.yml:3` → `ordersdb`
  - `services/inventory/src/main/resources/application-postgres.yml:4` → `inventorydb`
  - `services/payments/src/main/resources/application-postgres.yml:4` → `paymentsdb`
  - `services/catalog/src/main/resources/application.yml:15` → `eurotransit` DB,
    owning the dedicated `catalog` schema (`:22-23`, `:34`)
- Notifications is stateless (no datasource).

**Cosmetic inconsistency (not the anti-pattern, optional cleanup):** catalog implements
isolation as *schema-per-service* inside the `eurotransit` database, while
orders/inventory/payments implement it as *database-per-service* on the default `public`
schema (their `application-postgres.yml` enable Flyway without an explicit `schemas:`
key). Catalog's local default `DB_NAME:eurotransit` also differs from Helm's `catalogdb`
(overridden by env at deploy). Both strategies isolate correctly; the mix is just
stylistically uneven.

---

## 2. Bleeding Bounded Contexts — NOT violated

Domain models stay internal to each service; coordination happens only through published
contracts (REST DTOs) and events.

- No `common` / `shared` / `domain` module exists — each service is an independent Gradle
  build (`services/*/settings.gradle.kts`).
- Every service imports only its own `it.g03.<service>.*` package; no service imports
  another service's classes.
- Cross-service contracts are **private local DTO mirrors**, not shared types —
  `services/orders/src/main/kotlin/it/g03/orders/gateway/Gateways.kt:18-21,39-47`
  ("Mirror of Inventory's response... strings to avoid duplicating the enum").
- Each service keeps its own copy of event shapes — explicit by design in
  `services/orders/src/main/kotlin/it/g03/orders/events/OrderEvents.kt:15`
  ("Each service keeps its OWN copy of the event shapes it touches (no shared library)").
- Domain enums are internal: `OrderStatus` / `RejectionReason`
  (`services/orders/.../entities/Order.kt:60,63`), `ReservationStatus`
  (`services/inventory/.../entities/Reservation.kt:52-67`) — none imported across services.

---

## 3. Synchronous Cascades — present but intentional; one real (documented) gap

A genuine synchronous chain exists on the order path, but it is consciously bounded and
fired from inside an asynchronous pipeline.

**The chain** — `services/orders/src/main/kotlin/it/g03/orders/service/OrderSaga.kt`:

```
inventory.reserve  (OrderSaga.kt:45)
  → payments.authorize  (OrderSaga.kt:54)
    → inventory.confirm  (OrderSaga.kt:63)
  (on decline) → inventory.release  (OrderSaga.kt:57, compensation)
```

HTTP implementations in `.../gateway/Gateways.kt:57-111`, each carrying a 2s connect /
5s read timeout (no unbounded waits).

**Why this is acceptable, not a violation:**

- It runs behind an async Kafka boundary. `POST /api/orders` returns `202 Accepted`
  immediately (`OrdersController.kt:42-45`); the saga executes inside a `@KafkaListener`
  (`OrderPlacedListener.kt:22-28`) fed by a transactional outbox.
- The saga is idempotent and resumable (`OrderSaga.kt:33-44`); no DB transaction is held
  open across the remote calls.
- Downstream operations are idempotent (replay-safe), so at-least-once Kafka redelivery
  recovers from transient failures.
- Notifications is fully asynchronous and never calls back into the money path
  (`services/notifications/.../events/NotificationsListener.kt`), so it cannot fail checkout.
- The synchronous decision (reserve/authorize) is deliberately separated from the
  asynchronous notification (order-confirmed / notification-requested) — the design intent
  is documented at `Gateways.kt:12-14` ("a decision must be made NOW").

**Genuine gap (documented as Phase 2 debt — not fixed in this pass):**
Resilience4j is on the classpath (`services/orders/build.gradle.kts:48-50`,
`services/payments/build.gradle.kts:40-42`) but **no `@CircuitBreaker`, `@Retry`, or
bulkhead is applied** to the gateways. The cascade currently relies on timeouts plus Kafka
redelivery alone. This is explicitly deferred in-code at `Gateways.kt:14` ("Phase 2 wraps
these same gateways with Resilience4j (circuit breaker / retry+jitter / bulkhead)").
Until then, a slow downstream can block a Kafka consumer thread for up to ~7s per message.

---

## 4. Field Injection — NOT violated in production

All production services and controllers use constructor injection.

- `@Autowired lateinit var` appears **only in `src/test`** (`@SpringBootTest` fixtures),
  e.g. `OrderSagaTest.kt:73-83`, `PaymentsServiceTest.kt:28-30`,
  `InventoryServiceTest.kt:23-27`, `CatalogServiceTest.kt:22-23`,
  `NotificationsListenerTest.kt:19`. This is conventional for Spring integration tests.
- The no-arg constructors on JPA entities (`Product.kt:28`, `InventoryItem.kt:31`) are a
  pragmatic, documented Hibernate requirement — not field injection.

---

## 5. "Java in Kotlin" — minor only

The code is broadly idiomatic Kotlin (data classes, expression bodies, scope functions,
extension functions, coroutines in `OutboxPublisher`, default parameters, no `ArrayList`/
`HashMap` in production code).

- **5 `!!` non-null assertions — FIXED.** The only real style violation against the
  project's "no `!!`" rule has been remediated:
  - `services/orders/.../gateway/Gateways.kt` — the four `.body(...)!!` calls on RestClient
    responses now use `?: error("empty <op> response from <svc> for order=...")`, surfacing
    an explicit, contextual failure instead of an NPE.
  - `services/inventory/src/test/.../InventoryServiceTest.kt` — the test now uses
    `requireNotNull(...) { "expected a swept reservation ..." }`.
- **Stylistic `if/else` that could be `when`** — `PaymentsService.kt:76`,
  `InventoryService.kt:67,85`. Correct as written; optional polish, left untouched.

---

## 6. Conclusion

EuroTransit is a positive reference for all five anti-patterns: database-per-service,
locally-owned contracts and event shapes, constructor injection throughout, and an
outbox/saga design that confines the one synchronous cascade behind a message boundary
with bounded timeouts and idempotent retries.

Outstanding work is small and already understood:

1. Apply Resilience4j decorators to the order gateways (tracked Phase 2 debt).
2. ~~Replace the 5 `!!` usages with `?:` / `requireNotNull`.~~ **Done.**
3. (Optional) Unify the DB-isolation style across services for consistency.
