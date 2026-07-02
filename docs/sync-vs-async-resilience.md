# Sync vs Async Saga — Trade-offs & Resilience Under Chaos

> Study / decision doc. Compares the **current synchronous-HTTP orchestration** (implemented) with the
> **proposed two-phase async command/reply** redesign, focused on *how each behaves under chaos* and
> *where each resilience pattern belongs*. Pairs with `../../capstone-configuration-g03/docs/design.md`
> (decision 7), `capstone-dod.md` (Pillar C), and the chaos experiments in
> `../../capstone-configuration-g03/docs/chaos/`.

## 1. The two designs

**Current — synchronous orchestration (what runs today).** `POST /api/orders` persists `PENDING` + an
outbox row and returns `202`; the `order-placed` event (the *only* load-bearing topic) triggers the saga
in a coroutine. Inside that pipeline the steps are **synchronous HTTP** calls — `reserve → authorize →
confirm` to Inventory/Payments via `RestClient` — each wrapped by `GatewayResilience`
(timeout → retry → circuit breaker → bulkhead) with a `PAYMENT_PENDING` park + `OrderRecoverySweeper`
fallback. Resilience lives at the **call site**.

**Proposed — two-phase async command/reply.** Orders emits **commands** to Kafka and reacts to **replies**;
Inventory/Payments consume commands and reply via their own transactional outbox. Two phases: Phase 1
*holds* both resources (`reserve-seats`, `authorize-payment` = funds hold), Phase 2 *commits* both
(`confirm-seats`, then `capture-payment` = **capture-last**). Resilience lives in the **transport + the
consumers** (Kafka buffering, idempotency, DLT, a timeout sweeper). See the
[star diagram](https://claude.ai/code/artifact/ee7010d9-6833-4f62-a377-0f04c2c073c9) and the design plan.

## 2. Pros & cons

| | Synchronous orchestration (current) | Async command/reply (proposed) |
|---|---|---|
| **Latency to confirmed** | Low (seconds, steps in series) | Higher (more Kafka hops; two-phase ≈ 4 round-trips) — hidden behind the `202` |
| **Spike handling** | Sheds at the edge → HTTP 429 (burst = *rejected* customers) | Kafka absorbs → short backlog (burst = *delayed* customers) |
| **Downstream outage** | Breaker opens → park → recovery sweep; or shed | Commands buffer in Kafka; saga resumes on recovery |
| **Kafka outage** | Mild — Kafka only carries the trigger + notify; in-flight sagas finish on HTTP | **Severe** — Kafka is load-bearing for every step; all progress stalls (buffered) |
| **Operational surface** | Small (no DLT, no step-level lag ops) | Large (Kafka critical infra, DLTs, lag dashboards, schema evolution) |
| **Failure-mode surface** | Contained; well-understood | Wider (poison/HoL, stuck-vs-slow, contracts) — see plan F1–F10 |
| **Capstone grading** | Keeps the ✅ Pillar C circuit breaker (DoD item) | **Retires** it (resilience story shifts to buffering + DLT + sweeper) |
| **Best when** | Traffic is high-but-smooth, downstreams reliable | Traffic is bursty, must keep accepting through outages |

Throughput is **not** a discriminator — both scale horizontally (sync via replicas/HPA + bulkhead tuning;
async via partitions + consumers). See the Pillar A §6 analysis for the full reasoning.

## 3. Resilience-pattern placement map

*What each pattern solves, and where it lives in each design.* `→` = where it is wired today.

| Pattern | Solves | In **sync** (current) | In **async** (proposed) |
|---|---|---|---|
| **Rate limiter / load-shedding** | Backpressure: refuse excess at the edge instead of queueing to collapse | `@RateLimiter("orders-intake")` on `OrdersController.place`; over budget → `RequestNotPermitted` → 429 + `Retry-After` in `GlobalExceptionHandler` (`limit-for-period 20`) | **Same, unchanged** — still the edge guard that bounds *accepted* work. (Kafka absorbs accepted work; the limiter caps how much you accept.) |
| **Circuit breaker** | Stop hammering a failing/slow downstream; fail fast | `resilience4j` `circuitbreaker.instances.payments` (COUNT_BASED, opens on failure/slow-call rate, ignores `BulkheadFullException`) via `GatewayResilience`; fallback → `PAYMENT_PENDING` | **N/A for the steps** — there is no synchronous call to trip. Its job is replaced by Kafka buffering + the timeout sweeper |
| **Bulkhead** | Isolate pools so a slow downstream can't exhaust shared threads/conns | `bulkhead.instances` `payments=8`, `inventory=16` (SEMAPHORE, `max-wait PT0S` fail-fast) | **N/A for the steps** — isolation comes from *separate topics/consumer groups* per dependency instead |
| **Timeout** | Bound how long a remote call may block | `RestClient` connect 2s / read 5s in `Gateways.kt` | **N/A for the steps**; instead a per-saga **deadline** (timeout sweeper) bounds the whole flow |
| **Retry (backoff + jitter)** | Absorb transient transport faults inline | `retry.instances` `payments`/`inventory` (max-attempts 3, 0.2s ×2 exp + jitter; **transport faults only**, never a 4xx) | **Consumer retry** (Spring Kafka `DefaultErrorHandler` backoff) before DLT; same jitter idea |
| **Idempotency / dedup** | Safe at-least-once: a retry/redelivery has one effect | `UNIQUE(order_id)` (payments) / `UNIQUE(order_id, product_id)` (inventory) + replay of stored outcome | **Same, and now load-bearing on every step** — every command keyed by `orderId`; replies guarded by state |
| **Transactional outbox** | No dual-write: emit the event in the same txn as the work | `Outbox` / `OutboxPublisher` in Orders (for `order-placed` + terminal events) | **Extended to Inventory & Payments** — replies become durable, not best-effort |
| **DLT + non-blocking retry** | Contain a poison message; stop head-of-line blocking | — (no step-level Kafka) | **NEW** — `DeadLetterPublishingRecoverer` / `@RetryableTopic`: retry N → `<topic>.DLT` + alert |
| **Consumer-lag autoscaling** | Scale consumers to drain a backlog | — | **NEW** — HPA on consumer-group lag (e.g. KEDA) so a spike drains faster |
| **Timeout / recovery sweeper** | Re-drive work stuck in a non-terminal state | `OrderRecoverySweeper` (poll `PT5S`, `max-park PT2M`) re-drives/parks `PAYMENT_PENDING`; rejects `RESERVATION_EXPIRED` **without re-authorizing** | **Repurposed** — sweeps sagas whose reply never arrived; **re-drives** (idempotent) rather than compensating on doubt; can query the downstream to replace the guess |
| **Hold-TTL reconciler** | Auto-release holds that never got confirmed | `ReservationReconciler` (inventory) expires holds past `reservation.ttl PT2M` | **Same** — and the backstop for a lost `release-seats` (F6) |
| **Probes / PDB / topology-spread / HPA** | Survive pod/node loss; spread blast radius | Chart-level (validated live; liveness independent of downstreams; Kafka & breaker/limiter kept **out** of readiness) | **Same**, plus consumer-group rebalance on pod loss |
| **Graceful shutdown** | Drain in-flight work on SIGTERM, no orphans | `terminationGracePeriodSeconds 30` + preStop; readiness flips to "refusing traffic"; scope cancel | **Same**; uncommitted offsets → redelivery elsewhere (idempotency dedups) |

**The key asymmetry:** in sync, the heavy resilience machinery (breaker, bulkhead, call timeout) lives at
the **synchronous call site** because a blocking call to a sick downstream is the hazard. In async those
three largely **disappear** — there is no blocking call to protect — and are replaced by **Kafka buffering
+ idempotency + DLT + a timeout sweeper**. The **rate limiter, outbox, idempotency, TTL reconciler, and
the k8s patterns carry over** to both.

## 4. Chaos experiments — sync vs async, side by side

Aligned to `../../capstone-configuration-g03/docs/chaos/`. Each: *steady state → fault → hypothesis → how
each design behaves (which pattern saves it)*.

### 01 — Payments latency spike (`01-payments-latency.md`)
*Fault:* inject latency into Payments. *Hypothesis:* checkout stays bounded; no thread/queue blow-up.
- **Sync:** read **timeout** (5s) bounds each call; **retry** absorbs blips; sustained slowness trips the
  **circuit breaker** (`payments`) → fallback parks the order in `PAYMENT_PENDING`; the **bulkhead** (8)
  caps concurrent slow calls so they can't starve Inventory's pool; the **rate limiter** sheds new intake.
  `OrderRecoverySweeper` retries parked orders. **Many patterns engage — this is the breaker's showcase.**
- **Async:** latency just grows **consumer lag** on `authorize-payment`; Orders is never blocked. The saga
  waits in `RESERVED`; **lag-based autoscaling** spins up Payments consumers to drain. If latency exceeds
  the hold TTL, `confirm-seats` returns `EXPIRED` → `cancel-authorization` (no capture, no refund).
  **The buffer absorbs it; breaker/bulkhead are not needed.**

### 02 — Inventory pod kill (`02-inventory-podkill.md`)
*Fault:* kill an Inventory pod. *Hypothesis:* never oversell; orders still complete.
- **Sync:** the in-flight `reserve` call fails (connection refused) → **retry** bridges to a surviving
  replica; **PDB + topology-spread + HPA** keep capacity; k8s reschedules. Correctness held by the atomic
  conditional decrement + `UNIQUE(order_id, product_id)`.
- **Async:** the `reserve-seats` command simply **buffers in Kafka** until a replica returns and consumes;
  the **consumer group rebalances** to survivors. No failed call, just a brief delay. Same oversell guard.
  **Async rides this more gracefully (no error path at all).**

### 03 — Node disruption / drain (`03-node-disruption.md`)
*Fault:* drain/lose a node. *Hypothesis:* no orphaned work, no double-processing.
- **Sync:** **topology-spread** keeps replicas off a single node; **PDB** preserves a minimum; **graceful
  shutdown** drains in-flight HTTP (readiness flips first); a dropped call → **retry** on another pod.
- **Async:** the dying consumer's uncommitted offsets are **redelivered** to another pod (at-least-once);
  **idempotency** makes the reprocess a no-op. Same k8s patterns underneath.

### 04 — Kafka partition / broker loss (`04-kafka-partition.md`)
*Fault:* partition the brokers. *Hypothesis:* the system degrades safely and recovers without data loss.
- **Sync:** **mild.** Kafka only carries `order-placed` + terminal/notify events, and **readiness
  deliberately excludes Kafka**. New saga *starts* are delayed (held in the outbox, replayed on recovery),
  but **in-flight sagas finish over HTTP, unaffected**. The synchronous money path doesn't depend on Kafka.
- **Async:** **severe (this is F7).** Kafka is load-bearing for *every* step → **all saga progress stalls**
  for the duration; commands/replies sit in the outbox until brokers return. Survived (no loss) only if
  `acks=all` + `min.insync.replicas≥2` + idempotent producer + no unclean leader election. **The biggest
  contrast: the current design is markedly more robust to a Kafka outage than the async one.**

### 05 — Postgres failover (`05-pg-failover.md`)
*Fault:* fail over a CNPG primary. *Hypothesis:* no lost commits; brief unavailability, clean recovery.
- **Both:** each service owns its DB; **readiness depends on the own database**, so a pod flips not-ready
  during failover and traffic routes away. **CNPG synchronous replication** (Payments dedicated / inventory
  CP-leaning) prevents data loss; **retry/redelivery + idempotency** replay the interrupted write after
  recovery. Sync bridges via gateway **retry**; async via **outbox-relay retry**. Behaviour is essentially
  the same; the difference is only *which* retry does the bridging.

**Summary of the chaos contrast:** async is *better* at 01 (latency), 02 (pod kill), 03 (node) — buffering
+ redelivery beat call-site protection; **roughly equal** at 05 (PG); and **clearly worse** at 04 (Kafka),
because async makes the broker load-bearing for the whole money path.

## 5. Decision guide

- **Bursty traffic / must keep accepting through downstream outages → async.** Load-leveling and temporal
  decoupling pay off exactly at peak, which is when failures cluster.
- **High-but-smooth traffic / reliable downstreams → sync.** Less to operate, lower latency, and it keeps
  the graded resilience artifacts. With **virtual threads** (Java 21 + Boot 4) a blocking call no longer
  pins an OS thread, and checkout is already async behind the `202`, so the historical "blocking doesn't
  scale" pressure to go async is largely gone.
- **Watch the Kafka dependency.** Going async trades "robust to a Kafka outage" (today) for "robust to a
  downstream outage" (async). Pick the one whose failure you fear more.
- **Capstone note.** The async rewrite **retires** the synchronous Orders→Payments circuit breaker — a
  checked Pillar C DoD item with passing tests. Adopt it only deliberately.

**One line:** *async buys resilience under burst and downstream failure; sync buys simplicity, lower
latency, and Kafka-outage tolerance — so the choice is set by your traffic shape and which outage you most
need to survive.* Full scaling reasoning: Pillar A §6 (`design.md §6`).
