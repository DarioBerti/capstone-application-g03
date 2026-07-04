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
  path.
- **Ownership boundary.** Catalog reads/writes only its own schema. It must not read or write Inventory's
  (or any other service's) tables — coordinate via the shared trip `code` vocabulary only.
- **Price is non-negative.** Enforced at the database level; treat it as a hard invariant, not a code
  convenience.
- **Staleness is acceptable, incorrectness is not.** It's fine for Catalog to serve slightly stale data;
  it's not fine to serve wrong prices/ids.

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
- Don't add Kafka/eventing or cross-service sync calls unless a real requirement forces it — and if it
  ever does, it must not compromise the "off the money path" invariant above.
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
