# Frontend (debug SPA) — EuroTransit

> Guidance for working in `frontend`. Cross-cutting conventions live in the root [CLAUDE.md](../CLAUDE.md).
> Principle-based on purpose: the code is the source of truth for components, routes, and API shapes —
> don't mirror those here.

## What this is

A small **React + TypeScript + Vite** single-page app whose only job is to **exercise and observe the
money path** end-to-end from a browser: browse the catalog, place an order, and watch the saga reach a
terminal state. It is a **dev/demo tool, not a product surface** — hosted in-cluster on kind at
`https://app.localhost` (and run locally via Vite on `:5173`), and **never deployed to AKS/prod**.

Auth is **Keycloak via OAuth2 PKCE** (`keycloak-js`); every `/api/*` call carries the user's bearer
token. Requests are same-origin `/api/<svc>` and reach the services through the Vite proxy (local) or the
Traefik ingress (in-cluster). The backend services are OAuth2 resource servers — the SPA holds no secrets.

## Invariants to protect

- **The client owns idempotency for order placement.** A single "place order" intent must never create
  more than one order, even across a double-click, a transparent network retry, or a re-submit after a
  timeout. The intake (`POST /api/orders`) dedups on a client-supplied `Idempotency-Key` **header**, so
  the SPA MUST mint that key and keep it stable across retries of the same intent. See the do/don't below.
- **Identity comes from the token, never the form.** The buyer is taken from the validated JWT server-side;
  never send buyer identity in the request body.
- **No secrets in the SPA.** It's a public client (PKCE, no client secret); never embed credentials,
  tokens-at-rest, or PSP data. Build-time config (e.g. the Keycloak URL) is non-secret only.
- **Degrade, don't crash.** A failed/declined order or a down service is a normal outcome to render, not
  an unhandled rejection. Surface the HTTP status/body to the user.

## Idempotency key — the rule

The key exists to make a **retry of the same intent** replay the existing order instead of creating a
second one. Only the client knows whether a request is a retry or a new order, so the client mints it.

- **Mint one key per checkout intent**, tied to the order parameters (trip + quantity). Reuse the **same**
  key while those are unchanged — a re-submit after a failure/timeout is a *retry* and must carry the same key.
- **Mint a new key when the intent changes** (different trip or quantity) or **after a success** — the next
  order is a genuinely new intent and must not replay the previous one.
- Send it in the **`Idempotency-Key` request header**, not the JSON body (the backend reads the header;
  `UNIQUE(idempotency_key)` is its hard backstop for a concurrent same-key race → 409, then replay).
- Generate it with `crypto.randomUUID()` (the app is served over a secure context, so it's available).

Do NOT rely on the backend to generate the key: on a lost response the server-minted key never reaches the
client, so a retry would create a duplicate. This is a distinct dedup layer from the server-side outbox /
consumer idempotency in the Kafka pipeline — don't conflate them.

## Do

- Keep API calls in `src/api.ts` (one typed function per endpoint); keep components thin.
- Handle every request's error path explicitly and show the status + message.
- Prefer `crypto.randomUUID()` and the platform `fetch`; avoid adding heavy dependencies for a debug tool.
- Type responses against `src/types.ts`; keep those shapes aligned with the services' DTOs.

## Don't

- Don't place an order without a stable `Idempotency-Key` for the attempt.
- Don't put buyer identity, secrets, or PSP data in a request body or in the bundle.
- Don't treat this SPA as production UI or ship it to prod (`frontend.enabled` is a dev-only chart flag).
- Don't swallow errors — an uncaught rejection hides a real backend problem the demo is meant to reveal.

## Build & run

- `just dev` — Vite dev server + `/api` proxy at `http://localhost:5173` (hot reload).
- `just build` — `tsc --noEmit` type-check + production build into `dist/` (the type-check gates the build).
- In-cluster image is built + pushed by the config repo's `just images-kind` (bakes `VITE_KEYCLOAK_URL`).
