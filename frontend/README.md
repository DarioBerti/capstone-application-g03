# EuroTransit

A small React + Vite storefront for **driving and observing the booking saga** end-to-end from a browser.
It is the **production frontend**, served in-cluster behind Traefik at `https://app.g03.cpo2026.it`
(kind: `https://app.localhost`), and still runnable locally via Vite on `:5173` for development.

It contacts **only public services** in production — the internal services (inventory writes, payments)
are never called directly.

## What it does

- **Catalog** — lists trips from `catalog`, with live seat counts overlaid from the public
  `GET /api/inventory/availability` read (advisory — the authoritative never-oversell guard is at reserve time).
- **Book** — `POST /api/orders` (DRAFT) → add legs (reserve-on-add) → `pay` within the hold window.
- **Saga** — observes `GET /api/orders/{id}` to a terminal state (`CONFIRMED` / `REJECTED + reason`). The
  payment outcome is read from the **order status** (`AUTHORIZED`/`CONFIRMED`/`REJECTED` +
  `rejectionReason=PAYMENT_DECLINED`), not from the internal payments service.
- **Live updates** — a secured SSE stream (`GET /api/notifications/orders/{id}/events`) pushes each saga
  transition; if it can't hold open, the SPA degrades to polling the public order.

## How it connects

- **Prod**: same-origin `/api/*` routed by the Traefik ingress to the public services — `catalog`, `orders`,
  `users`, the secured `notifications` SSE, and the narrow `inventory/availability` read.
- **Dev**: the browser talks only to the Vite dev server, which proxies each `/api/<svc>` prefix to the
  matching local JVM (no CORS changes needed):

  | prefix            | service       | default port |
  | ----------------- | ------------- | ------------ |
  | `/api/catalog`    | catalog       | 8083         |
  | `/api/orders`     | orders        | 8080         |
  | `/api/inventory`  | inventory     | 8081         |
  | `/api/payments`   | payments      | 8082         |
  | `/api/users`      | users         | 8085         |
  | `/api/notifications` | notifications | 8084      |

  Ports match the configuration repo's `just dev-env` / `dev-env-files`. Override per run, e.g.
  `ORDERS_PORT=9000 just dev`.

## Run (local dev)

1. Start the services locally (IDE run configs) + `just dev-forward-all` from the configuration repo
   so the DBs/Kafka are reachable.
2. From this folder:

   ```bash
   just dev      # http://localhost:5173 (installs deps on first run)
   # or
   just build    # type-check + production bundle into dist/
   ```

## Prod image

Built + pushed by the app-repo CI (`.github/workflows/ci.yml`, the `build-frontend` job) with
`--build-arg VITE_KEYCLOAK_URL=https://auth.g03.cpo2026.it` baked in; the config repo's chart deploys it
(`frontend.enabled: true`). The kind image is built by the config repo's `just images-kind`.
