# EuroTransit — Storefront

The **production frontend** for the EuroTransit money path: a React storefront for browsing trains, placing a
booking, paying within the hold window, and watching the booking saga reach a terminal state — all from the
browser. Served in-cluster behind Traefik at `https://app.g03.cpo2026.it` (kind: `https://app.localhost`), and
runnable locally via Vite on `:5173`.

It contacts **only public services** in production — the internal services (inventory writes, payments) are
never called directly.

## Stack

- **React 18 + TypeScript + Vite**
- **Tailwind CSS** — the "EuroTransit Signature" design system (tokens in `tailwind.config.js`), light + dark
- **Redux Toolkit + RTK Query** — state and data fetching
- **Axios** — HTTP transport (RTK Query base query + the Keycloak token interceptor)
- **keycloak-js** — OAuth2 Authorization Code + PKCE
- **react-router-dom** — routing
- **Vitest + Testing Library + MSW** — tests

## What it does

- **Browse** (`/`, `/search`) — trips from `catalog` with live seat counts overlaid from the public
  `GET /api/inventory/availability` read (advisory — the authoritative never-oversell guard is at reserve time).
- **Book** — select a trip (`POST /api/orders` DRAFT → add leg, reserve-on-add, starts the hold window) → pay
  within the window (`POST /{id}/pay`). Payment is a one-click trigger; the card form is cosmetic (the PSP,
  PayPal sandbox, authorizes server-to-server — no card data leaves the browser).
- **Saga** (`/orders/:id`) — a live tracker of `DRAFT → HELD → PAYING → AUTHORIZED → CONFIRMED`, or
  `REJECTED` with a human-readable reason. The payment outcome is read from the **order status**, not the
  internal payments service.
- **Live updates** — a secured SSE stream (`GET /api/notifications/orders/{id}/events`) pushes each transition;
  if it can't hold open, the SPA degrades to adaptive-backoff polling of the public order.
- **My bookings** (`/orders`) and **Profile** (`/profile`) — behind Keycloak sign-in.

## How it connects

- **Prod**: same-origin `/api/*` routed by the Traefik ingress to the public services — `catalog`, `orders`,
  `users`, the secured `notifications` SSE, and the narrow `inventory/availability` read.
- **Dev**: the browser talks only to the Vite dev server, which proxies each `/api/<svc>` prefix to the
  matching local JVM (no CORS changes needed):

  | prefix               | service       | default port |
  | -------------------- | ------------- | ------------ |
  | `/api/catalog`       | catalog       | 8083         |
  | `/api/orders`        | orders        | 8080         |
  | `/api/inventory`     | inventory     | 8081         |
  | `/api/payments`      | payments      | 8082         |
  | `/api/users`         | users         | 8085         |
  | `/api/notifications` | notifications | 8084         |

  Ports match the configuration repo's `just dev-env`. Override per run, e.g. `ORDERS_PORT=9000 just dev`.

## Run (local dev)

1. Start the services locally + `just dev-forward-all` from the configuration repo so the DBs/Kafka/Keycloak
   are reachable.
2. From this folder:

   ```bash
   just dev      # http://localhost:5173 (installs deps on first run)
   just build    # type-check + production bundle into dist/
   just test     # Vitest unit/integration tests
   ```

## Prod image

Built + pushed by the app-repo CI (`.github/workflows/ci.yml`, the `build-frontend` job) with
`--build-arg VITE_KEYCLOAK_URL=https://auth.g03.cpo2026.it` baked in; the config repo's chart deploys it
(`frontend.enabled: true`). The kind image is built by the config repo's `just images-kind`.
