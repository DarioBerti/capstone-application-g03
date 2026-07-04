<!-- #DA ELIMINARE -->
> **⚠️ TO BE DELETED (#DA ELIMINARE)** — Schema/ER consolidated into the application repo README.md (§7). Kept for now; remove in one pass when the repo is final (grep `#DA ELIMINARE`).

## Summary
Advances the feature line from the `dev` merge-base (`06da3aa`) onto `dev`. Linear stack of 5 commits:

- `d3373dc` refactor: extract DTOs to `dto/` + centralized error handling
- `651aab4` feat: small fixes  — **backend-fixes** tip
- `fafdbbc` feat: add Keycloak auth (users service, resource servers, frontend login)
- `21c2c99` feat(frontend): containerize debug SPA, target kind Keycloak  — **feat/keycloak-auth** tip
- `e8ff637` refactor: Postgres/Testcontainers DB refactor + service interfaces

## Branch inclusion
`backend-fixes` (`651aab4`) and `feat/keycloak-auth` (`21c2c99`) are both ancestors of this branch, so this PR includes all of their commits. Neither is currently in `dev`.

## Notes
- `dev` has diverged: it carries **12 commits not in this branch** (common ancestor `06da3aa`), so this is a 3-way merge — review for conflicts.
- Backend verified: all five service test suites pass on Testcontainers PostgreSQL. Frontend changes were not built/tested here.

## Database schema (ER diagram)
Per-service schemas (database-per-service). **Solid** lines are real foreign keys within a service's schema; **dashed** lines are logical cross-service links over the shared `code` / `order_id` vocabulary (no physical FK).

```mermaid
erDiagram

    %% ============ catalog schema ============
    stations {
        varchar code PK "8"
        varchar name "128"
        varchar city "128"
        varchar country "2 (ISO 3166-1)"
    }
    trips {
        uuid id PK
        varchar code UK "32 - business key FR9592-20260629"
        varchar carrier_code "8"
        varchar service_number "16"
        date service_date
        varchar origin_code FK "8"
        varchar destination_code FK "8"
        timestamptz departure_time
        timestamptz arrival_time
        bigint price_cents ">= 0"
        varchar currency "3, default EUR"
        timestamptz created_at
        timestamptz updated_at
    }

    %% ============ inventory schema ============
    trip_seats {
        varchar trip_code PK "64 - shared trip-code vocab"
        integer total_seats ">= 0"
        integer available ">= 0 (never oversell)"
        timestamptz updated_at
    }
    reservations {
        uuid id PK
        varchar order_id UK "64 (uq with trip_code)"
        varchar trip_code FK "64"
        integer qty "> 0"
        varchar status "16 RESERVED/CONFIRMED/RELEASED"
        timestamptz created_at
        timestamptz expires_at
        timestamptz confirmed_at "nullable"
        timestamptz released_at "nullable"
    }

    %% ============ orders schema ============
    orders {
        uuid id PK
        varchar trip_code "64 - shared trip-code vocab"
        integer qty "> 0"
        bigint amount_cents "> 0"
        varchar currency "3, default EUR"
        varchar buyer_sub "255 - Keycloak sub"
        varchar buyer_email "320, nullable"
        varchar idempotency_key UK "64, nullable"
        varchar status "16"
        varchar rejection_reason "32, nullable"
        timestamptz created_at
        timestamptz updated_at
    }
    outbox_events {
        uuid id PK
        varchar topic "64"
        varchar aggregate_id "64"
        varchar event_type "64, nullable"
        text payload "pre-serialised JSON"
        timestamptz created_at
        timestamptz published_at "nullable - null = unpublished"
    }

    %% ============ payments schema ============
    payment_authorizations {
        uuid id PK
        varchar order_id UK "64 - one charge per order"
        bigint amount_cents "> 0"
        varchar currency "3, default EUR"
        varchar status "16 AUTHORIZED/DECLINED/VOIDED"
        varchar psp_reference "64, nullable"
        varchar decline_reason "64, nullable"
        timestamptz created_at
        timestamptz captured_at "nullable"
        timestamptz voided_at "nullable"
    }

    %% ============ users schema ============
    user_profiles {
        uuid id PK
        varchar keycloak_sub UK "255 - JWT sub"
        varchar email UK "320"
        varchar given_name "255, nullable"
        varchar family_name "255, nullable"
        varchar preferred_username "255, nullable"
        varchar locale "16, nullable"
        varchar preferred_currency "3, nullable"
        timestamptz created_at
        timestamptz updated_at
    }

    %% ---- real FK constraints (within a schema) ----
    stations ||--o{ trips : "origin_code"
    stations ||--o{ trips : "destination_code"
    trip_seats ||--o{ reservations : "trip_code"

    %% ---- logical links across services (shared vocab, NO DB FK) ----
    trips ||..o| trip_seats : "code = trip_code"
    orders ||..o{ reservations : "id = order_id"
    orders ||..o| payment_authorizations : "id = order_id"
    trip_seats ||..o{ orders : "trip_code"
    user_profiles ||..o{ orders : "keycloak_sub = buyer_sub"
```

## Test plan
- [ ] CI green on the PR
- [ ] Resolve any merge conflicts with `dev`
- [ ] Smoke-test the money path after merge
