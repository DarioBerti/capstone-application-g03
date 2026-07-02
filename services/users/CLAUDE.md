# Users Service — EuroTransit

> Guidance for working in `users`. Cross-cutting conventions live in the root [CLAUDE.md](../../CLAUDE.md).
> Principle-based on purpose: the code is the source of truth for endpoints, columns, and config keys —
> don't mirror those here.

## What this service is

The app-side **user profile** store, exposed through the ingress under `/api/users`. Keycloak owns
identity, login, and credentials; this service owns only the marketplace's view of a user (contact +
display fields + preferences), **keyed by the Keycloak subject** (the JWT `sub`). Package `it.g03.users`.
It is an **OAuth2 resource server** — it validates Keycloak JWTs, it never issues them.

## Invariants to protect

- **Keycloak is the source of truth for identity.** Never treat this DB as the authority on who a user
  is or whether they may log in. Token-owned fields (email, names) are mirrored from the JWT, not edited
  here as identity.
- **A caller can only ever touch their own profile.** Every endpoint is scoped to the token's `sub`;
  there is no addressing of another user's profile. Don't add an endpoint that takes a subject/id from
  the request body or path.
- **Provisioning is just-in-time and idempotent.** First authenticated contact creates the row from the
  token; repeated calls converge to the same row (no duplicates). Don't add a Keycloak admin-API sync or
  webhook unless a real requirement forces it.
- **Auth must not break operations.** Actuator health/readiness/liveness, Prometheus, and info stay
  public so probes and scraping never need a token. Don't lock them behind the resource server.

## Do

- Route every change through **Controller → Service → Repository**; keep the controller HTTP-only.
- Read identity from the validated `Jwt` principal; map it to the service's own value types (`UserClaims`)
  so the service never depends on Spring Security types.
- Return **DTOs** from `dto/`, never the JPA entity; map domain exceptions in the `GlobalExceptionHandler`.
- Validate tokens with **`jwk-set-uri` (lazy) + an issuer check**, not `issuer-uri` (eager) — startup must
  not depend on Keycloak being reachable (Pillar C).
- Test the **service** layer for provisioning/idempotency on Testcontainers PostgreSQL, and the security
  wiring with a mock JWT (`spring-security-test`).

## Don't

- Don't expose the entity or return unbounded lists.
- Don't let Hibernate own the schema (`ddl-auto: validate`); Flyway owns the `users` schema.
- Don't put this service on the money path — checkout must not depend on it being up.
- Don't store credentials, password hashes, or roles here; that's Keycloak's job.

## Source layout

Mirrors the Catalog reference layout (folders = packages):

- `controller/` — REST controller + the `GlobalExceptionHandler` (`@RestControllerAdvice`)
- `dto/` — request/response DTOs, `ErrorResponse`, `UserClaims`, and the `UserProfile.toResponse()` mapper
- `service/` — provisioning + update logic
- `repository/` — Spring Data repository, **one per file**
- `entities/` — the `UserProfile` JPA entity
- `config/` — `SecurityConfig` (resource server + JWT decoder)

Keep `UsersApplication.kt` at the package root.

## Configuration & testing

PostgreSQL everywhere, env-driven with local-dev defaults; this service owns the `users` schema (its own
DB `usersdb` in-cluster). OIDC is configured by `KEYCLOAK_JWK_SET_URI` + `KEYCLOAK_ISSUER` env (local
defaults point at a dev Keycloak). Tests need Docker (Testcontainers); `just test users` is the local
Definition-of-Done gate.
