# Catalog Service — EuroTransit

> Guidance for working in `catalog`. Cross-cutting conventions live in the root [CLAUDE.md](../../CLAUDE.md).
> This file is principle-based on purpose: the code is the source of truth for specifics (endpoints,
> columns, config keys) — keep it that way, don't mirror them here.

## What this service is

The read-mostly product/offer catalog (train departures), exposed via the ingress under `/api/catalog`.
It **owns** trip identity (a surrogate `id` UUID + a human-readable business `code`), the journey
(origin/destination stations, departure/arrival times), and pricing. It is **tolerant of staleness by
contract** and sits **outside the money path** — browsing, not checkout. Package `it.g03.catalog`.

A trip `code` follows the transit-industry format `[CARRIER][SERVICE_NUMBER]-[YYYYMMDD]` (e.g.
`FR9592-20260629`) and is the **cross-service vocabulary**. Catalog does **not** own seat counts;
Inventory owns those for the same trip `code`s. The two stores share only the `code` vocabulary
(database-per-service), never each other's tables.

## Invariants to protect

- **Off the money path.** Checkout must never depend on Catalog being up or fresh. Don't introduce a
  synchronous or eventing dependency that puts Catalog on the place-order → reserve → authorize → confirm
  path. (The availability read-model below is compatible: it is a **consume-only, after-the-fact** projection
  — checkout never blocks on Catalog or on this consumer's lag.)
- **Ownership boundary.** Catalog reads/writes only its own schema. It must not read or write Inventory's
  (or any other service's) tables — coordinate via the shared trip `code` vocabulary only. The
  `trips.available_seats` hint is a **derived local projection** kept in Catalog's own schema, never a read of
  Inventory's tables.
- **Price is non-negative.** Enforced at the database level; treat it as a hard invariant, not a code
  convenience.
- **Staleness is acceptable, incorrectness is not.** It's fine for Catalog to serve slightly stale data;
  it's not fine to serve wrong prices/ids. The seat-availability projection is deliberately **advisory and
  eventually consistent** — the authoritative never-oversell guard stays Inventory's atomic decrement at
  reserve time; a stale count here only ever hides a trip a beat late or shows one a beat early.

## Availability read-model (the one eventing dependency)

Catalog keeps a local `trips.available_seats` hint so search can hide sold-out trips **server-side with
correct pagination** (a client-side join can't paginate correctly — sold-out rows would eat page slots).

- **Consume-only.** Catalog subscribes to Inventory's `seat-availability` topic (`events/AvailabilityListener`)
  and upserts the count via `CatalogService.applyAvailability` → `TripRepository.applyAvailability`. It
  **never produces** a domain event and **never calls** another service. The only thing it puts on Kafka is
  the dead-letter recoverer (`events/KafkaErrorHandler` → `catalog-dlt`) — operational, not domain.
- **Advisory + eventually consistent.** `available_seats IS NULL` = unknown → the trip is shown; `= 0` =
  sold out → hidden from **search only**. Single-trip `byCode`/`GET /trips/{code}` is intentionally
  unfiltered, so a held-then-sold-out return leg still resolves.
- **Idempotent + out-of-order-safe.** The projection update is guarded by `seats_updated_at` (last-writer-by
  event-time), so a redelivery or a reordered event can't overwrite a fresher count.
- Adds a `spring-kafka` dependency (consumer only). The CAP/staleness justification is a **team** judgment —
  author it in `docs/design.md`, not here.

## Do

- Route every change through **Controller → Service → Repository**. Business/query logic belongs in the
  service; the controller is HTTP-only (DTOs, status codes, exception mapping).
- Return **DTOs** at the boundary, never JPA entities.
- Keep list endpoints **paginated and filtered** (`Pageable` + `Specification`), with a sane default and
  a hard max page size.
- Let **Flyway own the schema** (migrations into the `catalog` schema); Hibernate only validates.
- Test against **real PostgreSQL via Testcontainers**; cover behaviour at the service layer.
- Treat Catalog as a future caching/read-replica candidate — keep reads cheap and side-effect-free.

## Don't

- Don't let the controller call the repository directly, or push logic into the controller.
- Don't return raw entities or unbounded `List`s from read endpoints.
- Don't rely on Hibernate to create/alter schema (`ddl-auto` stays at `validate`); don't use H2.
- Don't add cross-service **sync** calls, and don't **produce** domain events or add eventing beyond the one
  consume-only availability read-model documented above — and never in a way that compromises the "off the
  money path" invariant. (Catalog consuming Inventory's `seat-availability` is the sanctioned exception: a
  read-side projection, not a producer, and never on the checkout path.)
- Don't make checkout, Orders, or Inventory block on a Catalog response.

## Source layout

Organize by layer, **one repository per file**, keeping small cohesive types grouped:

- `controller/` — REST controllers + the `GlobalExceptionHandler` (`@RestControllerAdvice`)
- `dto/` — request/response DTOs, `ErrorResponse`, and the `Trip`/`Station` `toResponse()` mappers
- `service/` — business/query logic
- `repository/` — Spring Data repositories, **one repository per file** (never bundle several into a `Repositories.kt`)
- `entities/` — JPA entities, one entity per file

Name each file after the type it holds; keep `CatalogApplication.kt` at the package root. **This service
is the reference layout** — the other services should match it.

## Configuration & testing

PostgreSQL everywhere, env-driven with local-dev defaults (see root CLAUDE.md for the injection
contract). Schema-per-service: this service owns the `catalog` schema. Tests need Docker running
(Testcontainers); `just test catalog` is the local Definition-of-Done gate.
