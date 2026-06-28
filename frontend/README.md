# EuroTransit · Debug Console

A tiny React + Vite UI for **manually driving and observing the place-order saga** while debugging
the backend locally. It is a dev tool — not part of the product, not deployed.

## What it does

- **Catalog** — lists products from `catalog` with live seat counts overlaid from `inventory`.
- **Place order** — `POST /api/orders`, returns `202 Accepted` with an order id.
- **Saga** — polls `GET /api/orders/{id}` to a terminal state (`CONFIRMED` / `REJECTED + reason`)
  and shows the payment outcome from `GET /api/payments/authorizations/{id}`.

## How it connects

The browser talks only to the Vite dev server. Vite proxies each `/api/<svc>` prefix to the
matching local JVM, so **no CORS changes** are needed in the services:

| prefix            | service   | default port |
| ----------------- | --------- | ------------ |
| `/api/catalog`    | catalog   | 8083         |
| `/api/orders`     | orders    | 8080         |
| `/api/inventory`  | inventory | 8081         |
| `/api/payments`   | payments  | 8082         |

Ports match the configuration repo's `just dev-env` / `dev-env-files`. Override per run, e.g.
`ORDERS_PORT=9000 just dev`.

## Run

1. Start the services locally (IDE run configs) + `just dev-forward-all` from the configuration repo
   so the DBs/Kafka are reachable.
2. From this folder:

   ```bash
   just dev      # http://localhost:5173 (installs deps on first run)
   # or
   just build    # type-check + production bundle into dist/
   ```
