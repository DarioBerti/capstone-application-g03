# EuroTransit Marketplace — Application Repository

A small, realistic **multi-service marketplace** (train-ticket sales) built to prove
**operational behaviour under partial failure**, not feature richness. The capstone question:
*stability is an outcome — can we produce it on purpose, under failure, and prove it?*

This is the **application repository** (source for the five services, tests, CI, `justfile`).
A separate **configuration repository** holds Helm charts, Argo CD `Application`s, SLO/alert
definitions, and platform bootstrap. CI here pushes immutable images and bumps image tags in the
config repo; **Argo CD reconciles**. CI never holds cluster credentials.

Graded on four pillars: **A** distributed design & async execution, **B** consistency under
contention (inventory), **C** resilience engineering, **D** delivery + observability + proof.
The team owns the judgments; agents only produce artifacts (see Agentic policy).

## The five services

| Service | Responsibility | Interaction style | Database |
|---|---|---|---|
| `catalog` | Lists products/offers; read-mostly | Sync API, tolerant of staleness | shared (lower-tier) |
| `orders` | Orchestrates the purchase workflow | Sync entry + async pipeline | dedicated CNPG |
| `inventory` | Tracks finite seats — **the contended resource** | Async Kafka commands/replies (reserve→confirm hold) | dedicated CNPG |
| `payments` | Authorizes then captures payment; **must not double-charge** | Async Kafka commands/replies, strict idempotency | dedicated CNPG |
| `notifications` | Sends confirmations | Fully async; **must fail without failing checkout** | none (stateless) |

**The money path** is a two-phase, human-gated **booking**: create a DRAFT → add an outbound leg (required)
plus an optional return leg — each add **reserves seats on-add** and the first starts a backend-owned hold
window → **pay** within the window → the saga confirms every held leg and captures once → CONFIRMED. A booking
is one journey (a one-way or round-trip), at most two legs. SLOs, dashboards, tracing, and chaos experiments
all center on it.

- North-south entry: a single API gateway/ingress (Traefik).
- Sync APIs: client → gateway → Catalog/Orders (browsing + the booking intake: `POST /api/orders` create,
  `POST /{id}/legs` add-leg, `DELETE /{id}/legs/{legId}`, `POST /{id}/pay`). The pay/confirm/capture flow
  itself is **fully async**: Orders drives Inventory/Payments over Kafka commands + replies, not HTTP.
- The hold window is **backend-authoritative** (`expires_at` on the order; re-checked at pay and swept by a
  give-up sweeper — the frontend countdown is display-only). At window expiry the reason is dual-cause on a
  stored `payment_initiated_at`: `EXPIRED_NO_PAYMENT` (the user never paid) vs `SERVICE_UNAVAILABLE` (pay was
  accepted but the pipeline stalled).
- Async: **Kafka (Strimzi operator)** for the pipeline and all eventing. The saga is an orchestrated
  command/reply pipeline (Orders = orchestrator), triggered by the synchronous add-leg (reserve) and pay
  (authorize) — there is no `order-placed` trigger. Commands `reserve-seats`, `authorize-payment`,
  `confirm-seats`, `capture-payment`, `release-seats`, `cancel-authorization`; replies `reservation-result`,
  `payment-result`, `seats-confirmed`, `payment-captured` (reservation/confirm replies carry the `tripCode` so
  a multi-leg booking maps each reply to its leg); terminal/notify `order-confirmed`, `order-rejected`,
  `notification-requested`; and a dead-letter topic per consuming service (`orders-dlt`, `inventory-dlt`,
  `payments-dlt`).

## General rules
- When editing or creating code files, do NOT add useless comments in any circumstance. The only comments which can be considered useful are the following:
	- Complex logic (e.g. bit-twiddling math, complex one-liners, etc.)
	- Citations or algorithms taken from documents such as papers or other implementations
	- Implementation quirks
	- TODOs or FIXMEs
	Useless type of comments include examples such as:
	- Explaining a short function which has a self-explanatory name
	- Outlining an obvious algorithm (e.g. a for loop)
	- A short logic that already has self explanatory class names / variables
	DO NOT REMOVE EXISTING COMMENTS UNLESS SPECIFICALLY ASKED TO.
- When writing in Italian, you MUST ALWAYS use the correct accented vowels in words that require them (e.g. è, perché, più, etc, etc). DO NOT under any circumstance write these words without the accented letter (perch) or without the accent (perche) or by using the aphostrophe (') as an accent (e.g. piu' or perche').
## Tech stack

- **Kotlin 2.2.x**, **Spring Boot 4.0.x**, Gradle (Kotlin DSL), **Java 21** toolchain.
- Spring MVC (`spring-boot-starter-webmvc`), Spring Data JPA, Jackson 3 (`tools.jackson`).
- **PostgreSQL** everywhere (via CloudNativePG); **Flyway** owns the schema, Hibernate only validates.
- Actuator + Micrometer Prometheus registry + Micrometer Tracing (OTel bridge) + OTLP exporter.
- Async order pipeline uses **Kotlin coroutines / Flows** (Pillar A: correct cancellation/shutdown).
- Use the AOP approach whenever possible.
- **Kafka** via `spring-kafka` (idempotent producer, `acks=all`, manual offset commit / at-least-once).

## Repository layout

```
services/<name>/          # one independent Gradle project per service
  build.gradle.kts        # plugins, deps (kept per-service, not a multi-module root build)
  gradlew, gradle/        # each service ships its own wrapper
  Dockerfile              # multi-stage build → runnable jar
  CLAUDE.md               # service-specific domain, events, invariants (see per-service docs)
  src/main/kotlin/it/g03/<name>/   # source, package it.g03.<name>
    <Name>Application.kt           # entrypoint stays at the package root
    controller/ service/ repository/ entities/ events/   # layer folders = package segments
  src/main/resources/
    application.yml               # base config
    application-cluster.yml       # cluster overlay (quiet SQL, hidden health details, JSON logs)
    db/migration/V*__*.sql        # Flyway migrations (own schema per service)
  src/test/kotlin/it/g03/<name>/  # tests (Testcontainers PostgreSQL)
justfile                  # task runner — primary entrypoint for all commands
.github/workflows/ci.yml  # path-filtered per-service build/push + GitOps tag bump
docs/                     # DoD, design/consistency justification, SLOs, chaos reports, postmortem, agent-log
```

This root file covers cross-cutting conventions. Each `services/<name>/CLAUDE.md` documents that
service's domain, events, and invariants.

## Commands

Use `just` (run `just` or `just --list` to see all recipes). `<svc>` ∈
`catalog orders inventory payments notifications`. **A local PostgreSQL must be running** (see
Database & profiles) before `just run`.

```bash
just test <svc>         # run one service's tests (Testcontainers spins up Postgres)
just test-all           # tests for every service
just build <svc>        # build runnable jar (runs tests)
just run <svc>          # run locally on :8080 (needs Postgres + Kafka available)
just verify <svc>       # full local DoD: build → test → image → run container → readiness probe
just verify-all         # verify every service
just image <svc> [tag]  # build container image locally (linux/amd64)
just clean <svc>        # clean Gradle artifacts
```

Direct Gradle (from inside a service dir): `./gradlew test --no-daemon --console=plain`.
Health/readiness probe: `GET /actuator/health/readiness`.

## Developer notes — building & testing

Practical guidance for working day-to-day, including while the system is mid-build and not every
service/object exists yet. Items that describe the *target* (not yet in code) are flagged against the
**Current state / migration debt** section at the bottom.

### Prerequisites
- **JDK 21** (or let the Gradle toolchain auto-provision it).
- **Docker running** — required for tests (Testcontainers), `just verify`, and image builds.
- **`just`** installed.
- Each service is an **independent Gradle project** with its own wrapper — there is no root build.

### Inner loop — build & test one service
```bash
just test <svc>          # or: cd services/<svc> && ./gradlew test --no-daemon --console=plain
just build <svc>         # build + test → runnable jar
```
Services compile and test **independently** — there is no build-time coupling, so one service builds
even if the others are incomplete or absent.

### Tests need Docker
Tests use **Testcontainers PostgreSQL** (ephemeral real Postgres per run); if Docker isn't running the
container can't start and tests fail fast. *(Transition: until the H2→Testcontainers migration lands,
some tests still use H2 — see migration debt.)*

### Running locally (two-tier)
- **Inner loop** (fast iteration): bring up dependencies with **docker-compose** (Postgres + Kafka),
  then `just run <svc>`. *(The compose file doesn't exist yet — until then, start Postgres/Kafka
  manually or point at deps from a running cluster. See migration debt.)*
- **Fidelity loop** (cluster parity): deploy into a **KIND** cluster using the config-repo Helm charts
  + operators (CloudNativePG, Strimzi, Traefik) + Argo CD when you need to validate probes/PDBs,
  GitOps, progressive delivery (canary / blue-green), or run chaos experiments. Inner-loop deps can
  also be port-forwarded out of KIND instead of running compose.
- Note: **KIND runs on Docker** and complements (does not replace) docker-compose — the two serve
  different loops. Testcontainers needs only a Docker daemon, not Kubernetes.

### Working ahead while objects are missing
- **TDD**: write the service/Testcontainers test first, then Controller → Service → Repository + DTO +
  Flyway migration.
- **Fake collaborators** in Service tests. Kafka publish/consume is disabled in tests via properties,
  so event-producing code can be built and tested before the broker is wired.
- **Fake downstream sync calls** (e.g. Orders → Inventory/Payments gateways) so a service can be
  developed before its dependencies are complete.

### Adding a new service or persistent object — checklist
- Package under `it.g03.<svc>`; follow Controller → Service → Repository.
- Own **schema** + Flyway migration `db/migration/V*__*.sql` (no Hibernate auto-DDL in cluster).
- Mirror an existing service's `build.gradle.kts` dependencies.
- Register the service in the `services` list in `justfile` **and** in the `dorny/paths-filter` map in
  `.github/workflows/ci.yml` (otherwise CI won't build it).
- Expose `/actuator/health/readiness`.

## Architecture conventions

### Controller → Service → Repository (mandatory, no exceptions)
Every service uses all three layers. **A controller never calls a repository directly.**
- **Controller**: HTTP only — request/response DTOs, status codes, exception handlers. No business logic.
- **Service**: business logic, transactions, orchestration, idempotency. The unit of behaviour testing.
- **Repository**: Spring Data JPA interfaces; no logic beyond queries.

Even read-mostly services (e.g. `catalog`) route through a Service. (`catalog` currently shortcuts
controller → repository — that is debt to fix, not an accepted pattern.)

The Service layer is an `interface` + `@Service` impl: `<Svc>Service` (no annotation) implemented by
`<Svc>ServiceImpl` (`@Service`), one type per file in `service/`. **Controllers inject the interface,
never the impl.**

### Source layout (folders = packages)

Organize each service's source **by layer into folders whose names are the package segments** — see
`catalog` for the reference layout. `<Svc>Application.kt` stays at the package root `it.g03.<svc>`.

| Folder | Package | Holds |
|---|---|---|
| `controller/` | `it.g03.<svc>.controller` | REST controllers + the service's `GlobalExceptionHandler` (`@RestControllerAdvice`) |
| `dto/` | `it.g03.<svc>.dto` | request/response DTOs, the `ErrorResponse` DTO, and entity↔DTO mappers (`toResponse()`) |
| `service/` | `it.g03.<svc>.service` | business logic — each service as an `interface` (`XxxService`) + `@Service` impl (`XxxServiceImpl`); also saga, state machine, reconciler |
| `repository/` | `it.g03.<svc>.repository` | Spring Data repositories — **one repository per file** |
| `entities/` | `it.g03.<svc>.entities` | JPA entities (+ their tightly-bound enums) |
| `events/` | `it.g03.<svc>.events` | Kafka listeners, publishers, `Topics`, event payloads |
| `gateway/` | `it.g03.<svc>.gateway` | outbound sync HTTP clients to other services (where present, e.g. `orders`) |

**One repository per file** (never bundle several into a `Repositories.kt`); otherwise keep small
cohesive types grouped (a service's request/response DTOs together in `dto/`, event payloads together). Spring
component/entity/repository scanning is unaffected — `@SpringBootApplication` scans `it.g03.<svc>` and
all subpackages.

### DTOs at the boundary
Never expose JPA entities directly. Define request/response `data class` DTOs in the **`dto/` package**
(`it.g03.<svc>.dto`) and map entity ↔ DTO explicitly with a top-level `toResponse()` mapper kept
beside them (see `it.g03.catalog.dto` `Product.toResponse()` for the reference pattern).

Validate request bodies at the edge with **Bean Validation**: annotate DTO fields
(`jakarta.validation.constraints.*`, e.g. `@field:NotBlank`, `@field:Positive`) and add `@Valid` on the
controller parameter — don't hand-roll `require()` checks in the service for request-body fields. The
resulting `MethodArgumentNotValidException` is mapped to a 400 `ErrorResponse` in the service's
`GlobalExceptionHandler`. (Reserve service-layer `require()` for preconditions that aren't request-body
fields, e.g. identity derived from the JWT.)

### Paginated reads (standard for any endpoint returning table rows)
Endpoints returning rows from a table **must be paginated and use Specifications** for filtering —
never return an unbounded `List`. Target pattern:

```kotlin
interface ProductRepository : JpaRepository<Product, String>, JpaSpecificationExecutor<Product>

@GetMapping("/products")
fun list(filter: ProductFilter, pageable: Pageable): Page<ProductResponse> =
    service.search(filter, pageable).map { it.toResponse() }   // controller → service → repository
```

- Accept `Pageable` (page, size, sort) on every list endpoint; enforce a sane **default and max page size**.
- Use `Specification<E>` to build dynamic, composable filters from query params.
- Return `Page<Dto>`, never `Page<Entity>`.

### Money-path correctness (Pillars A & B)
- **Idempotency is mandatory**: a redelivered reservation/payment reply or a re-driven command must not
  double-reserve, double-charge, or double-capture. The codebase enforces this with DB constraints
  (`UNIQUE(order_id)` in Payments, `UNIQUE(order_id, trip_code)` in Inventory and Orders' `order_legs`) +
  fast-path replay + per-order/per-leg status guards; Orders uses a **transactional outbox** so the business
  change and its command/reply commit atomically. Payments also keeps a `cancel-authorization` **VOIDED
  tombstone** so a compensation wins the race against a stale `authorize-payment` on the separate topic.
- **Inventory invariant: never oversell.** Reservation is an atomic conditional decrement; two
  customers cannot buy the last seat. Document the CAP/PACELC choice in `inventory`'s CLAUDE.md.
- **Async lifecycle**: when you *do* run background coroutines, a `CoroutineScope` is a failure domain
  (use `SupervisorJob`) and must cancel cooperatively on `SIGTERM`/shutdown via `DisposableBean` (in-flight
  work finishes or is cleanly cancelled — no orphaned tasks, no double-processing). But use coroutines/Flows
  only where they genuinely earn it — suspending, cancellable async work such as the saga — and prefer
  Spring's `@Scheduled` for simple blocking periodic polls (e.g. the orders timeout sweeper). Don't reach for
  coroutines/Flows just to show them off.
- **Notifications must degrade**: checkout succeeds even if notifications are entirely down.

## Database & profiles (Postgres everywhere, dev / prod for Kubernetes)

PostgreSQL is the only datasource — local, test, and cluster. Config comes from **env vars with
local-dev defaults** (`${VAR:default}`); nothing environment-specific is hardcoded. The image is
immutable; behaviour is configured by environment.

**Connection env vars** (set by the deployment / docker-compose):
`DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASS`, `DB_NAME`, and a per-service **schema**.
Flyway owns the schema (`flyway: enabled`); Hibernate runs `ddl-auto: validate` only.

### Dev — single CloudNativePG, schema per service
One CNPG cluster, one `eurotransit` database. Each service owns its own **schema** and migrates into it.

```
CNPG cluster: eurotransit-dev   →   database: eurotransit
  schema catalog    → stations, trips
  schema orders     → orders, outbox_events
  schema inventory  → trip_seats, reservations
  schema payments   → payment_authorizations
  schema users      → user_profiles
  (notifications: stateless, no schema)
```

`DB_NAME=eurotransit` for all DB-owning services; each sets its default schema (e.g.
`spring.jpa.properties.hibernate.default_schema` / Flyway `schemas`) to its own name. This keeps
clean ownership boundaries while sharing one instance, and splits cleanly to dedicated DBs in prod.

### Local — two-tier (no H2)
The same Postgres model runs locally. Two complementary loops (see **Developer notes** for usage):
- **Inner loop**: docker-compose (Postgres + Kafka) → `just run <svc>`; fast edit/compile/run.
- **Fidelity loop**: a **KIND** cluster running the config-repo Helm charts + operators
  (CloudNativePG, Strimzi, Traefik) + Argo CD, for k8s-level validation (probes, PDBs, GitOps,
  progressive delivery, chaos).

There is no in-memory fallback. KIND runs on Docker; Testcontainers (tests) needs only Docker.

### Prod — dedicated CNPG for the money path
`orders`, `inventory`, `payments` each get a **dedicated CloudNativePG cluster** (money-path /
contended / financial → blast-radius isolation). `catalog` shares a lower-tier instance
(read-mostly, tolerant of staleness). `notifications` has no database. Activate the cluster profile
with `SPRING_PROFILES_ACTIVE=cluster`.

### Secrets & migrations
- Secrets live in Git **only as SealedSecrets** and reach the app as env vars — never in source or
  plain manifests.
- New schema changes go in `db/migration/V*__*.sql` (Flyway), scoped to the service's schema.

## Configuration & variable injection (config repo → backend)

The backend declares a **contract** of variables; the *values* live in the config repo (ConfigMaps and
SealedSecrets), not here. Injection uses plain environment variables — there is **no** Spring Cloud
Config and **no** `@ConfigurationProperties` classes. Three mechanisms:

1. **Explicit placeholders `${VAR:default}`** in `application*.yml`:
   - DB (in `application.yml`): `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASS`
   - Kafka (orders/inventory/payments/notifications): `KAFKA_BOOTSTRAP`
   - Orders sync gateways: `INVENTORY_BASE_URL`, `PAYMENTS_BASE_URL`
2. **Spring relaxed binding** (env var maps to a property, no placeholder needed):
   `SPRING_PROFILES_ACTIVE=cluster`, `MANAGEMENT_OTLP_TRACING_ENDPOINT`.
3. **App-level tunables via `@Value`** (overridable by env): `app.unit-price-cents`,
   `app.outbox.poll-interval`, `app.outbox.enabled`, `inventory.reservation.ttl`,
   `inventory.reservation.sweep-interval`, `payments.decline-threshold-cents`.

Flow:
```
config repo Helm chart → renders Deployment env:
    non-secret values ← ConfigMap
    secret values     ← SealedSecret (decrypted in-cluster), e.g. DB_PASS
  → Spring binds them via ${VAR} placeholders + relaxed binding
Argo CD reconciles. CI only bumps image tags — never holds cluster creds. Secrets in Git only as SealedSecrets.
```

The local-dev defaults baked into the `${VAR:default}` placeholders are convenience fallbacks only;
in-cluster every value is supplied by the rendered Deployment env.

## Testing

- **Testcontainers PostgreSQL** is the test datasource (`@ServiceConnection` `PostgreSQLContainer`):
  ephemeral real Postgres per run, real Flyway migrations, faithful to `UNIQUE`/never-oversell
  behaviour. CI already has Docker. No H2.
- JUnit 5 (`useJUnitPlatform`); backtick-quoted descriptive test names; AAA structure.
- Prefer hand-written fakes over mocks. Test the **Service** layer for behaviour; slice-test controllers.
- Concurrency/idempotency is first-class: see `inventory` `InventoryConcurrencyTest` (never-oversell
  under contention) and `orders` `OrderVersionConcurrencyTest` (optimistic-locking idempotency).
- Kafka listeners/publishing are disabled in tests via properties; consumer logic is tested with fakes (the outbox is drained externally by Debezium CDC).
- `just verify <svc>` is the local Definition-of-Done gate (build + test + container + readiness).

## Observability (Pillar D)

- Expose only `health,info,prometheus` actuator endpoints.
- Kubernetes probes use Actuator health groups: **readiness** includes `readinessState` + `db`;
  **liveness must not fail on transient downstream issues** (and excludes Kafka).
- Prometheus scrape via `/actuator/prometheus`; per-service **RED** dashboards.
- Distributed tracing across the money path via OTLP — endpoint overridden in-cluster with
  `MANAGEMENT_OTLP_TRACING_ENDPOINT` (collector/Tempo). Local default `localhost:4318`.
- Alerts are **symptom-based** (burn rate / user-visible), tied to SLOs — not CPU.

## Resilience (Pillar C)

Contain partial failure; never propagate it. The money path is **async Kafka command/reply**, so its
resilience comes from temporal decoupling rather than a synchronous circuit breaker:
- **Kafka buffering** absorbs a downstream slowdown/outage as consumer lag, not blocked threads — the saga
  resumes when the consumer recovers.
- **Bounded, non-blocking consumer retry + a dead-letter topic** per service (`DefaultErrorHandler` +
  `DeadLetterPublishingRecoverer`) so a poison/exhausted message is parked, never head-of-line-blocking.
- **A timeout sweeper** re-drives a saga stuck in a non-terminal state past a deadline (idempotent commands
  make re-drive safe); every saga reaches a terminal state in bounded time.
- **Backpressure / load shedding** at the synchronous intake (`@RateLimiter` on `POST /api/orders` → HTTP
  429) rather than queuing to collapse. The 429 is edge-only.
- Any *remaining* cross-service sync call still gets timeouts + bounded retries (backoff + jitter) +
  bulkheads + a circuit breaker with a safe fallback — never an unbounded hang.
- Kubernetes-level: deliberate startup/readiness/liveness probes, PodDisruptionBudgets, topology spread.

## CI/CD & GitOps

- `.github/workflows/ci.yml`: detect changed services (path filter) → per-service matrix builds &
  tests → push immutable image tagged with the **commit SHA** to ACR → bump that tag in the
  **config repo** `values.yaml`. Argo CD reconciles the change.
- **CI never touches the cluster.** A PAT scoped to the config repo only is the least-privilege bridge.
- Two progressive-delivery strategies are demonstrated: **canary** and **blue/green**.

## Code style (project-specific deltas)

Global Kotlin/coding rules apply (immutability, `val` over `var`, no `!!`, sealed types, etc.).
Project-specific notes:
- **JPA entities are the pragmatic exception to immutability**: Hibernate requires mutable `var`
  properties and a no-arg constructor (`kotlin("plugin.jpa")` + `allOpen` handle the latter).
  Keep DTOs and domain values as immutable `data class`es with `val`.
- Package every service under `it.g03.<service>`. `group = "it.g03"`.
- Keep files small and cohesive (target 200–400 lines). Many small files over few large ones.
- Handle errors explicitly at the boundary; map domain exceptions to HTTP status in a per-service
  `@RestControllerAdvice` (`GlobalExceptionHandler`) returning a typed `ErrorResponse` (404 for
  `NoSuchElementException`, 400 for `IllegalArgumentException`) — not per-controller `@ExceptionHandler`s.

## Agentic coding policy (graded)

Agents may produce artifacts (scaffolding, manifests, dashboards, tests) but **must not own the
judgments**: service decomposition, consistency model, SLO definitions, failure-mode mapping, chaos
hypotheses, and the postmortem are authored by the team.
- The agent is an actor in the system — reason about its blast radius (least privilege, review before merge).
- `docs/agent-log.md` records concrete cases where an agent artifact was wrong/unsafe and how it was
  caught. **Verify agent output**; finding its mistakes is the graded skill.

## Current state / migration debt

The conventions above are the **target**. These items in the current code still diverge and need work
(tracked separately, not assume-done):

- **H2 still present** (deps in every `build.gradle.kts`, default `application.yml` = in-memory H2)
  → remove H2; make PostgreSQL the only datasource.
- **Per-service databases** (`catalogdb`, `ordersdb`, `inventorydb`, `paymentsdb`) → dev should use one
  `eurotransit` DB with **schema per service**; keep separate databases only for the prod money-path
  CNPG clusters.
- **Tests run on H2** → migrate to Testcontainers PostgreSQL.
- **Raw-`List` read endpoints** (e.g. `CatalogController.all()`) → `Pageable` + `Specification`,
  returning `Page<Dto>`.
- **Catalog has no Service layer** (controller → repository directly) → add a `CatalogService` so all
  services follow Controller → Service → Repository.
- **No docker-compose for local Postgres/Kafka** yet → add one for the inner loop so `just run`/local
  dev have their dependencies.
- **No documented KIND bootstrap** for the fidelity loop yet → document (or reference, in the config
  repo) standing up KIND + operators (CloudNativePG, Strimzi, Traefik) + Argo CD.
- **`DB_NAME` defaults to per-service db names** (`catalogdb`…) in `application-postgres.yml`, and no
  per-service schema is configured yet → switch dev to `DB_NAME=eurotransit` + `default_schema`/Flyway
  `schemas` per service (matches the agreed schema-per-service model).
