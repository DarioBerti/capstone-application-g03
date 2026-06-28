# Inventory Service — EuroTransit

> Guidance for working in `inventory`. Cross-cutting conventions live in the root [CLAUDE.md](../../CLAUDE.md).
> Principle-based on purpose: the code is the source of truth for endpoints, columns, and config keys —
> don't mirror those here.

## What this service is

Tracks finite seats — **the contended resource** and the heart of the consistency story (Pillar B).
It reserves seats synchronously on the money path and emits events for the rest of the system.
Package `it.g03.inventory`. The headline question this service must answer under load: *two customers,
one last seat — can both buy it?* The answer must always be no.

## Invariants to protect

- **Never oversell.** Available seats must never go negative. Reservation is an **atomic conditional
  decrement** (decrement only if enough remain), backstopped by a database `CHECK`. Two concurrent
  buyers of the last seat → exactly one wins. Don't replace this with read-then-write logic that opens
  a race.
- **Reservation is idempotent.** A duplicated reserve for the same order/product must not reserve twice
  — enforced by a uniqueness constraint plus fast-path replay of the stored outcome.
- **Reservations don't leak.** A reservation that is never confirmed must be released and the seats
  restocked (a supervised background reconciler sweeps overdue holds). Releasing seats must itself be
  safe to repeat.
- **The synchronous answer is load-bearing; events are not.** The reserve/confirm/release HTTP outcome
  is what the saga depends on. Emitted events are best-effort signals published **after commit** —
  losing one must not corrupt seat counts.
- **Document the consistency choice.** The CAP/PACELC stance for this service is a team judgment;
  keep it stated and keep the code consistent with it. (Author the justification in `docs/`, not here.)

## Do

- Route every change through **Controller → Service → Repository**; keep the controller HTTP-only.
- Return **DTOs**, never JPA entities; paginate any list/read endpoint (`Pageable` + `Specification`).
- Keep the contended path a single atomic statement; let the database enforce the invariant.
- Run background sweeps in a **supervised `CoroutineScope`** that cancels cleanly on shutdown.
- Publish events **after** the transaction commits.
- Test never-oversell **under real concurrency** (many threads, one seat) on **Testcontainers
  PostgreSQL** — the race semantics that matter only hold on a real database.

## Don't

- Don't gate the reservation on an application-level read/check then write — that's the oversell bug.
- Don't make seat correctness depend on an event being delivered.
- Don't return raw entities or unbounded lists; don't let Hibernate own the schema (`validate` only).
- Don't reach into other services' data — Inventory owns seat counts for the shared `productId`s only.
- Don't treat a reserve/confirm/release as one-shot; assume retries and make each safe to repeat.

## Source layout

Organize by layer, **one repository per file**, keeping small cohesive types grouped (mirrors the Catalog reference layout):

- `controller/` — REST controllers + their request/response DTOs
- `service/` — business logic (reservation logic, the overdue-hold reconciler)
- `repository/` — Spring Data repositories, **one repository per file**
- `entities/` — JPA entities (`InventoryItem`, `Reservation`), one per file
- `events/` — Kafka publishers and event payloads

Name each file after the type it holds; keep `InventoryApplication.kt` at the package root.

## Configuration & testing

PostgreSQL everywhere, env-driven with local-dev defaults; Inventory has a **dedicated** datasource
(contended money-path resource → blast-radius isolation). Flyway owns the schema; Hibernate validates.
Kafka publish/consume is disabled in tests via properties. Tests need Docker (Testcontainers);
`just test inventory` is the local Definition-of-Done gate.
