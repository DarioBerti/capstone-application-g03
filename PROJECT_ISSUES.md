# EuroTransit Marketplace (g03) — Current-State Issues & Gap Analysis

> **What this is.** A review of the **application repository** at its current state against the
> *CapstoneProject.pdf* requirements (the four pillars + DoD), the Controller‑Service‑Repository
> architecture described in `NOTE_IT.md`, and **correct Spring Data JPA usage**.
>
> **Scope note.** This repo is, by design, only the *application* half of the two‑repository GitOps
> model (see `README.md`). The **configuration repository** (Helm, Argo CD `Application`s, Strimzi,
> CloudNativePG, Traefik, kube‑prometheus, Chaos Mesh, SealedSecrets, SLO/alert rules, and the graded
> `docs/`) is **not present here** and cannot be verified from this repo. Items that live there are
> listed separately in **§B** so you know what still has to exist and be checked elsewhere.
>
> **Date:** 2026‑06‑22 · **Reviewed:** all 5 services (`catalog`, `orders`, `inventory`, `payments`,
> `notifications`), `justfile`, `.github/workflows/ci.yml`, Dockerfiles, Flyway migrations, tests.

---

## Legend

| Severity | Meaning |
|---|---|
| 🟥 **CRITICAL** | A graded pillar requirement is absent, or a money‑path correctness defect. Fix before the demo. |
| 🟧 **HIGH** | A real bug or a requirement only partially met; defendable only with a fix or a written justification. |
| 🟨 **MEDIUM** | Maintainability / correctness‑under‑load / pattern issue. Should fix. |
| 🟦 **LOW** | Minor / cosmetic / hardening. Optional. |
| ✅ **OK** | Already correct — listed so you don't break it and can defend it in the oral. |

Each finding is tagged with the **pillar** it affects (A = async design, B = consistency, C = resilience,
D = delivery/observability) and a `file:line` reference.

---

## Executive summary — pillar coverage scoreboard

| Pillar / area | State | Headline gap |
|---|---|---|
| **A — Async & coroutines** | 🟧 Partial | Coroutines/Flows used only for the outbox relay + reconciler; the **order pipeline stages run as blocking calls on a single Kafka thread**. Blocking work on `Dispatchers.Default`. |
| **B — Consistency / idempotency** | 🟧 Mostly solid | Oversell/double‑charge cores are **correct**. But: **no payment compensation** on late‑confirm (charge kept), and a **confirm‑vs‑reconciler double‑restock race** (no row lock). |
| **C — Resilience** | 🟥 Largely absent | **Resilience4j is a dependency but configured/used nowhere** — no circuit breaker, bulkhead, retry+backoff/jitter, time limiter, or 429/load‑shedding. Only bare HTTP timeouts exist. |
| **D — Delivery / observability / proof** | 🟥 Unverifiable here | Metrics/tracing wired in‑app (good), but GitOps, dashboards, SLOs, alerts, canary/blue‑green, and **all chaos experiments live in the config repo / `docs/` — none present here**. |
| **Docs / verification** | 🟥 Missing | No `docs/` at all → no `capstone-dod.md`, `design.md`, SLO defs, chaos reports, postmortem, **`agent-log.md` (explicitly graded)**, threat model. Code comments reference `docs/design.md` & `docs/agent-log.md` that don't exist. |
| **Controller‑Service‑Repository** | 🟨 Mostly clean | Orders/Payments are textbook. **Inventory & Catalog controllers reach into repositories directly** for reads. |
| **Spring Data JPA usage** | 🟨 Good core, some misuse | Atomic conditional UPDATE + outbox are textbook. But **OSIV left enabled**, **pre‑assigned IDs force `merge`+SELECT on every insert**, entities are `data class`. |

---

# §A — Issues inside this repository (fix here)

## 🟥 CRITICAL

### C1 — Pillar C resilience layer is essentially unimplemented
**Pillar C · `services/orders/build.gradle.kts:45‑48`, `services/payments/build.gradle.kts:37‑40`, `Gateways.kt`**

Resilience4j is declared as a dependency in Orders and Payments, but:
- **No `resilience4j` block in any `application.yml`** (confirmed: zero matches across all YAML).
- **No `@CircuitBreaker` / `@Retry` / `@Bulkhead` / `@TimeLimiter` / `@RateLimiter`** anywhere in main code.
- The Orders→Inventory/Payments gateways (`Gateways.kt:58‑111`) have **only** bounded connect/read
  timeouts (2s/5s). No breaker, no retry, no bulkhead, no fallback.

The capstone's Pillar C explicitly requires: circuit breakers on cross‑service sync calls (Orders→Payments)
with open/half‑open policy + safe fallback; bulkheads; **timeouts and bounded retries with backoff + jitter
on every remote call**; backpressure/load‑shedding (HTTP 429). **None of these exist.** The code comments
themselves defer all of it to *"Phase 2"* (`OrderSaga.kt:15`, `Gateways.kt:14`).

> ⚠️ **Misleading test.** `OrdersApplicationTests.kt:591‑598` asserts a `CircuitBreakerRegistry` bean exists
> and is named *"resilience4j auto‑configuration actually binds on Spring Boot 4"*. This proves only that the
> auto‑config bean is present — **not** that any breaker is configured or applied to a call. It gives false
> confidence that Pillar C is covered.

**Also note** the build files' own warning: `resilience4j-spring-boot3` (2.3.0) targets Spring Boot **3**;
this project is on Boot **4.0.3**. Even once you add annotations/config, verify the AOP advice actually
engages on Boot 4 (the registry binding is necessary but not sufficient).

**Fix:** add `resilience4j` config (circuit breaker + retry w/ `enableExponentialBackoff` + `randomizedWaitFactor`
for jitter + bulkhead + timelimiter) and annotate the gateway methods with fallbacks (e.g. Payments breaker‑open
→ park the order in a `PAYMENT_PENDING` state rather than hang/throw). Add a load‑shedding filter returning 429
on the synchronous entry (`POST /api/orders`) under overload.

---

### C2 — Money kept on a rejected order (no payment compensation)
**Pillar B · `services/orders/src/main/kotlin/it/g03/orders/OrderSaga.kt:57‑64`**

If `inventory.confirm()` returns non‑`CONFIRMED` (the hold expired during a slow payment) **after**
`payments.authorize()` already succeeded, the saga rejects the order (`RESERVATION_EXPIRED`) but **never voids
or refunds the authorized payment**:

```kotlin
val confirmation = inventory.confirm(id, order.productId)
if (confirmation.status != "CONFIRMED") {
    // TODO Phase 2: compensate the charge (refund/void on Payments) ...
    stateMachine.reject(orderId, RejectionReason.RESERVATION_EXPIRED)
    return
}
```

The customer is **charged for an order that is then rejected**. Payment *idempotency* is correct; what's
missing is the **compensating action**. This is a money‑path correctness defect on exactly the pillar
(B — "consistency & idempotency on the money path") that is weighted most heavily.

**Fix:** add a `void`/`refund` endpoint on Payments (idempotent, keyed by orderId) and call it in this branch
before `reject(...)`. Until then, document it loudly in `design.md` as a known, observable gap.

---

### C3 — No `docs/` — required, graded deliverables are missing
**Pillars all · repo root (no `docs/` directory)**

The capstone DoD and Deliverables require a `docs/` containing: `capstone-dod.md`, the design /
consistency (CAP/PACELC) justification, the SLO definitions + error‑budget statement, the chaos‑experiment
reports, the blameless postmortem, the **agent threat‑model paragraph**, and **`agent-log.md` with ≥3 concrete
caught‑agent‑mistake cases**. The PDF states *"Finding the agent's mistakes is the core skill the capstone
certifies"* and *"This is graded."*

None of these exist in this repo. Worse, code/build comments reference `docs/design.md` and `docs/agent-log.md`
as if they exist (`OrderSaga.kt:60`, `Gateways.kt:13`, `build.gradle.kts:30`, `payments/build.gradle.kts:23`) —
those are currently **dangling references**.

**Fix:** create `docs/` (here or in the config repo — confirm where your cohort expects it) and author all of
the above. The `agent-log.md` and threat model cannot be generated mechanically; they are the judgment artifacts
the grade keys on.

---

## 🟧 HIGH

### H1 — `Reservation` confirm‑vs‑reconciler double‑restock race (no row lock)
**Pillar B · `InventoryService.kt:92‑108` (confirm) / `133‑146` (expireOverdue) · `Reservation.kt` (no `@Version`)**

The seat *counter* is protected by the atomic conditional UPDATE (correct). The reservation **status
transitions are not.** There is **no `@Version` and no `@Lock`** on `Reservation` (confirmed: zero matches).
Both `confirm()` and the reconciler's `expireOverdue()` do:

```kotlin
if (r.status == ReservationStatus.RESERVED) { r.status = …; reservations.save(r); items.restock(qty) }
```

Under READ_COMMITTED two concurrent transactions (a `confirm()` arriving right at TTL, and the 15s sweep
processing the same row) can **both** read `RESERVED`, **both** pass the guard, and **both** call
`items.restock(+qty)` → **seats restocked twice → phantom seats → oversell** (the same shape exists for
`release()` vs the sweep). The window is narrow, but it is precisely what chaos experiment #2 (pod‑kill
mid‑reservation) is designed to surface.

**Fix (on‑pattern with your own `tryDecrement`):** make the transition a conditional UPDATE that returns the
affected‑row count, and restock **only** when this transaction actually performed the transition:
`UPDATE reservations SET status=:new WHERE id=:id AND status='RESERVED'` → if `1` row changed, restock; else
it was already transitioned by someone else. (Alternatively add `@Version` for optimistic locking.)

---

### H2 — Outbox relay is unsafe under horizontal scaling
**Pillar A/B · `OutboxPublisher.kt:69‑86`**

`drainOnce()` does `findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()` with **no row claiming**
(`FOR UPDATE SKIP LOCKED`) and **no leader election**. With a single Orders pod this is fine. With **>1
replica** (which HPA / availability under chaos implies), **every replica publishes every unpublished row** →
N× duplicate events and the per‑order ordering guarantee (the "stop the batch on failure" logic at
`OutboxPublisher.kt:75‑79`) holds only *within* one instance, not across replicas. Consumers are idempotent so
it's *tolerable*, but it contradicts the ordering claim and amplifies load on the money path.

**Fix:** claim rows with `@Query(... FOR UPDATE SKIP LOCKED)` + `@Modifying` (mark "in‑flight" or delete‑on‑publish),
or run the relay as a single leader (e.g. ShedLock), or accept it explicitly and document the ordering caveat.

---

### H3 — Stranded orders never recover; Kafka error handling ≠ documented behavior
**Pillar C · `OrderPlacedListener.kt:11,21` · `OrderSaga.kt:13‑15`**

The saga relies entirely on Kafka redelivery and there is **no recovery sweep / `@Scheduled` job** on the
Orders side (confirmed: only a comment saying *"Phase 2 adds … a recovery sweep for stranded orders"*).
There is also **no explicit Kafka error handler / DLT** (confirmed: zero matches) — so the **framework default
applies**: Spring Kafka's `DefaultErrorHandler` retries with `FixedBackOff(0, 9)` — **10 attempts with *no*
back‑off interval**, then logs and **skips** (commits the offset). Consequences:
- The saga comment claims *"retries with backoff, then logs & skips"* — but the default has **no real backoff**
  and **no jitter** (Pillar C wants backoff+jitter on *every* remote path), and **no dead‑letter topic**, so a
  poison `order-placed` is silently dropped after 10 instant retries.
- A skipped order is left **forever in `PENDING`/`RESERVED`** with no terminal state and no customer notice.
  (Inventory's reconciler reclaims the *seats*, which is good — but the *Order* row stays stuck.)

**Fix:** configure a `DefaultErrorHandler` with `ExponentialBackOffWithMaxRetries` + a dead‑letter recoverer
(`DeadLetterPublishingRecoverer`), and add a periodic recovery sweep that re‑drives or terminally rejects
orders stuck in `PENDING`/`RESERVED` past a deadline.

---

### H4 — Configuration‑repo deliverables can't be verified from here
**Pillars C & D · not in this repo**

See **§B**. Flagged HIGH because Pillars C (K8s‑level: probes wiring, PDBs, HPA, NetworkPolicies) and D
(GitOps, dashboards, SLOs, alerts, canary + blue/green, Strimzi, CloudNativePG, Chaos Mesh) are *graded* and
**none of it is present here**. If it exists in the config repo, verify completeness there; if not, it's missing.

---

## 🟨 MEDIUM

### M1 — Controller → Repository layering violation (your explicit C‑S‑R ask)
**Architecture · `InventoryController.kt:35‑38,60‑65` · `CatalogController.kt:17,20‑26`**

You asked for a strict **Controller → Service → Repository** layering. Two controllers bypass the Service:
- **`InventoryController`** injects **both** `InventoryService` *and* `InventoryItemRepository`, and serves
  `GET /items` / `GET /items/{id}` straight from the repository — the read path skips the service layer.
- **`CatalogController`** injects `ProductRepository` directly and has **no Service at all**. (`NOTE_IT.md`
  defends this as "no business logic," which is a legitimate position — but it is inconsistent with the
  Controller‑Service‑Repository pattern you want, and it means the only seam for future caching / staleness
  policy is in the wrong layer.)

Orders and Payments are clean (`Controller → Service → Repository`). 

**Fix:** add read methods to `InventoryService` (and a thin `CatalogService`) and remove the repository
dependency from the controllers. Small change, makes the layering uniform and defensible.

### M2 — Open‑Session‑In‑View left enabled (Spring Data JPA)
**Pillar C / JPA · all `application.yml` (no `spring.jpa.open-in-view` set → defaults to `true`)**

Spring Boot defaults `open-in-view=true`, and none of the services disable it. OSIV holds the persistence
context **and a DB connection** open for the entire request (through view rendering). These controllers return
already‑loaded flat entities, so OSIV buys nothing and only **holds connections longer than necessary** — which
directly undermines the connection‑pool **bulkhead** isolation Pillar C asks for and makes pool exhaustion under
load more likely. (Spring even logs a warning about this by default.)

**Fix:** set `spring.jpa.open-in-view: false` in every service and add `@Transactional(readOnly = true)` on the
read paths (see M5).

### M3 — Pre‑assigned UUID IDs force `merge` + an extra SELECT on every insert (Spring Data JPA)
**JPA perf · all entities (`@Id val id: UUID = UUID.randomUUID()`, no `@GeneratedValue`, no `Persistable`)**

Assigning IDs in the app (deliberate, to avoid sequence contention — good) has a subtle cost: Spring Data's
`SimpleJpaRepository.save()` calls `entityInformation.isNew(entity)`, which is `false` for a non‑null,
pre‑populated `@Id`. So **every `save()` of a brand‑new row runs as a `merge`**, which issues a **SELECT before
the INSERT** to check existence. On the **high‑volume outbox** (1–2 rows per business op, `OutboxEvent.kt`) and
the orders/reservations/payments inserts on the money path, that's an avoidable round‑trip per write.

**Fix:** implement `Persistable<UUID>` with a `@Transient` "isNew" flag (set true on construction, false on
`@PostLoad`/`@PostPersist`), so new entities `persist` instead of `merge`. Removes the extra SELECT.

### M4 — Tests run on H2 (`ddl-auto: update`); the real Postgres/Flyway schema is never exercised
**Pillar B / JPA · all `*Test` use `@SpringBootTest` on H2 · `application.yml` (`flyway.enabled: false`, `ddl-auto: update`)**

The Pillar‑B proofs (`InventoryConcurrencyTest`, `PaymentsServiceTest` race tests, idempotency tests) run against
**H2 with Hibernate‑generated schema**, not the Flyway Postgres schema. So the `CHECK (available >= 0)`, the
`UNIQUE` constraints, the FK, and the partial index from `db/migration/V1__baseline.sql` are **never tested**, and
the concurrency invariants are validated on an engine whose locking/MVCC differs from Postgres. `ddl-auto: validate`
on the cluster profile only runs in‑cluster — a mapping drift wouldn't be caught by CI.

**Fix:** run the consistency tests against **Testcontainers‑Postgres** with Flyway enabled. Also add coverage
tooling (Kover/JaCoCo) — there is no coverage gate, despite the 80% target.

### M5 — Read paths not marked `@Transactional(readOnly = true)`; list endpoints unbounded (Spring Data JPA)
**JPA · `OrderService.get`, `PaymentsService.find`, `CatalogController.all/byId`, `InventoryController.items`**

Read methods aren't `readOnly`, so Hibernate keeps them in a read‑write context (dirty‑checking, no read‑only
hints). And `CatalogController.all()` / `InventoryController.items()` do `findAll()` with **no pagination** — fine
for the demo seed, unbounded in principle.

**Fix:** annotate reads `@Transactional(readOnly = true)`; add `Pageable` to the list endpoints.

### M6 — Blocking JDBC/Kafka work on `Dispatchers.Default`
**Pillar A · `OutboxPublisher.kt:37` · `ReservationReconciler.kt:33`**

Both background loops run on `Dispatchers.Default` (a pool sized to CPU cores, meant for CPU‑bound work) but do
**blocking** JPA queries and `KafkaTemplate.get()`. This is the exact blocking‑vs‑suspending distinction Pillar A
grades: blocking on the CPU dispatcher can starve genuinely CPU‑bound coroutines.

**Fix:** use `Dispatchers.IO` (or a dedicated pool) for these loops. Cheap, and directly defensible in the oral.

### M7 — CI pushes desired state straight to config‑repo `main` (no review gate)
**Pillar D / agentic threat model · `ci.yml:120‑157`**

`update-gitops` commits and `git push`es to the configuration repo's default branch with a write‑scoped PAT —
**no PR, no policy‑as‑code check, no human review** on the change Argo CD will reconcile. The capstone's agentic
section asks you to reason about exactly this ("whether its changes require human or policy‑as‑code review before
merge … the worst case if it proposes a bad change"). Also: with `fail-fast: false`, a **single** service build
failure makes `needs.build.result == 'failure'` and **skips the GitOps bump for *all* services** in the run
(`ci.yml:123`).

**Fix:** open a PR against the config repo (or gate with branch protection + a policy check) instead of pushing to
`main`; and decide whether a partial‑success run should still ship the services that did build.

---

## 🟦 LOW / hardening

| # | Finding | Where |
|---|---|---|
| L1 | Entities are Kotlin **`data class`** → `equals`/`hashCode`/`copy`/`toString` derived from *all* (incl. mutable) fields; unsafe if ever put in hash collections or compared across persistence states. Low impact today (never done), but not idiomatic JPA. | all `@Entity` files |
| L2 | `outbox_events.payload` is **`VARCHAR(4000)`** — a larger event would fail to persist. Use `TEXT`. | `OutboxEvent.kt:29`, orders `V1__baseline.sql` |
| L3 | `qty` only validated `> 0` (no upper bound) → huge `qty` flows into `amountCents = qty * unitPriceCents`. | `OrderService.kt:29‑33` |
| L4 | Notification recipient hardcoded `customer@example.org`. | `OrderStateMachine.kt:48,70` |
| L5 | Actuator `health show-details: always` exposes DB sub‑status; ensure `/actuator/**` isn't routed through the public ingress. | all `application.yml` |
| L6 | CI also pushes a mutable **`latest`** tag alongside the immutable SHA (GitOps uses SHA, so harmless, but against immutability discipline). | `ci.yml:113‑115` |
| L7 | `justfile install-hooks` points `core.hooksPath` at a **non‑existent `.githooks/`** → silently disables hooks; the promised "pre‑push verify" doesn't exist. | `justfile:144‑146` |
| L8 | No `gradle.properties` (`kotlin.code.style=official`), no ktlint/Detekt, no SAST/dependency/image scan or SBOM/signing in CI. | repo‑wide |
| L9 | Kafka topics aren't created in‑app; relies on Strimzi/config‑repo. With `acks=all` + `min.insync.replicas=2` (per comments), broker auto‑create would make unsafe single‑replica topics — ensure topics are declared with replication in the config repo. | config repo |

---

# §B — Cross‑repo / deliverables to verify (not in this repository)

These are *required* by the capstone but, per the two‑repo model, are expected in the **configuration repo**
and/or `docs/`. They cannot be confirmed from here — verify they exist and are complete:

- **GitOps / delivery (Pillar D):** Argo CD `Application`(s); Helm charts for the 5 services + the image‑tag
  `values.yaml` the CI bumps (`ci.yml:142`); **canary** (TraefikService) and **blue/green** manifests + a written
  note on why all‑at‑once/rolling weren't used on the critical path.
- **Platform (shared infra):** Traefik ingress, cert‑manager, **Strimzi** Kafka + KafkaTopics, **CloudNativePG**
  cluster for Orders, kube‑prometheus‑stack, **Chaos Mesh**, **SealedSecrets**.
- **K8s‑level resilience (Pillar C):** the actual probe wiring (paths/initialDelay/period/threshold — the app only
  *exposes* the groups), **PodDisruptionBudgets**, HPA, topology spread, NetworkPolicies.
- **Observability (Pillar D):** per‑service **RED** dashboards, USE/Golden‑Signals, **symptom‑based alerts**,
  latency + success‑rate **SLOs for checkout** with the SLIs and an **error‑budget** statement, distributed‑tracing
  views across `gateway → Orders → Inventory/Payments → Kafka`.
- **Chaos experiments (5):** latency into Payments; pod‑kill on Inventory mid‑reservation; node/AZ disruption;
  network‑partition/Kafka; CloudNativePG primary failover — each with hypothesis, steady state, observation,
  verdict, and what changed.
- **`docs/`:** `capstone-dod.md`, `design.md` (incl. CAP/PACELC justification), SLO defs, chaos reports,
  postmortem, agent **threat‑model** paragraph, **`agent-log.md`** (≥3 caught agent mistakes — graded).

---

# §C — What is already correct (defend these; don't refactor them)

- ✅ **Never‑oversell core (Pillar B):** atomic conditional `UPDATE … WHERE available >= :qty` returning rows‑affected
  (`InventoryItemRepository.tryDecrement`) + DB `CHECK (available >= 0)` as a backstop. Textbook CP‑under‑contention.
  Proven by `InventoryConcurrencyTest` (8 threads, 1 seat → exactly 1 winner).
- ✅ **Never‑double‑charge core (Pillar B):** `UNIQUE(order_id)` + in‑transaction dedup + replay of the stored
  outcome; loser of the insert race rolls back its whole transaction. Proven by `PaymentsServiceTest`.
- ✅ **Idempotent, resumable saga:** terminal‑state guards make redelivery a no‑op; PENDING/RESERVED resume cleanly.
- ✅ **Transactional outbox** for Orders' load‑bearing events (no dual‑write); informational events in Inventory/
  Payments published **after commit** via `TransactionSynchronizationManager` (no ghost events). Good distinction.
- ✅ **Spring Data JPA done right in places:** `@Modifying(flushAutomatically, clearAutomatically)` with correct,
  documented flush/clear reasoning; **Flyway owns schema + Hibernate `validate`** on Postgres; **no mapped
  relationships → no N+1 / lazy‑loading traps** (clean DB‑per‑service modeling); app‑assigned UUIDs to avoid
  sequence contention.
- ✅ **Self‑invocation pitfall handled:** transitions live in a separate `@Transactional` bean (`OrderStateMachine`)
  so the saga doesn't hold a DB transaction across remote calls — a common Spring mistake, correctly avoided.
- ✅ **Cooperative shutdown (Pillar A):** `DisposableBean.destroy()` → `scope.cancel()`; suspension points kept
  outside `runCatching`/`try` so `CancellationException` isn't swallowed.
- ✅ **Readiness ≠ liveness:** readiness includes `db` (can't serve without own DB); Kafka deliberately excluded so
  broker blips don't flap the sync entry API; Notifications readiness is app‑only (graceful degradation by design).
- ✅ **CI shape:** path‑filtered build matrix, immutable SHA image tags, pushes to ACR, updates GitOps via a
  config‑repo‑scoped PAT, **no cluster credentials in CI** (Argo CD reconciles). Matches the required model.
- ✅ **Containers:** multi‑stage build, non‑root runtime user, JRE‑only final stage.

---

## Suggested priority order

1. **C3 / agent‑log** + **design.md** (graded judgment artifacts — start now, they take longest).
2. **C1** — wire Resilience4j (breaker+retry/jitter+bulkhead+timelimiter+429) — the biggest pillar gap.
3. **C2** + **H1** + **H3** — money‑path correctness (payment compensation, restock race, stranded‑order recovery).
4. **§B** — confirm/complete the configuration repo (GitOps, SLOs, dashboards, alerts, 5 chaos experiments).
5. **M1–M7** — layering, OSIV, Persistable, Testcontainers, IO dispatcher, CI review gate.
6. **L1–L9** — hardening.