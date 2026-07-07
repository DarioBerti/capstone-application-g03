# Frontend (SPA) — EuroTransit

> Guidance for working in `frontend`. Cross-cutting conventions live in the root [CLAUDE.md](../CLAUDE.md).
> Principle-based on purpose: the code is the source of truth for components, routes, and API shapes —
> don't mirror those here.

## What this is

The **production storefront** for the EuroTransit money path — browse the catalog, place a booking, pay within
the hold window, and watch the saga reach a terminal state. **React + TypeScript + Vite**, styled with
**Tailwind** (the "EuroTransit Signature" design system), state via **Redux Toolkit + RTK Query**, HTTP via
**Axios**. Served in-cluster behind Traefik at `https://app.g03.cpo2026.it` (kind: `https://app.localhost`),
runnable locally via Vite on `:5173`. In production it contacts **only public services** — catalog, orders,
users, the secured notifications SSE stream, and the narrow public seat-availability read; the internal services
(inventory writes, payments) are never called directly.

Auth is **Keycloak via OAuth2 PKCE** (`keycloak-js`); every `/api/*` call carries the user's bearer token. The
backend services are OAuth2 resource servers — the SPA holds no secrets.

## Architecture

- **Data layer = RTK Query over an Axios base query.** All REST goes through one Axios instance
  (`api/httpClient.ts`) whose request interceptor attaches the Keycloak token and whose 401 handler bounces to
  login. RTK Query (`api/apiSlice.ts` + per-feature `injectEndpoints`) owns caching, loading, and invalidation.
  **Add endpoints there, one typed function per backend route; keep components thin.** No token is ever stored
  in Redux — it always comes from `auth.ts`.
- **SSE is deliberately NOT Axios.** The live booking stream (`orderStream.ts`) uses `fetch` +
  ReadableStream because it must send the `Authorization` header on a `text/event-stream` (Axios/XHR can't
  stream incrementally; `EventSource` can't set headers). Do not "unify" it into the Axios/RTK layer.
- **Feature-first layout** under `src/features/<domain>/` (catalog, inventory, orders, booking, checkout,
  search, home, profile); shared UI in `src/components/`, helpers in `src/lib/`.

## Invariants to protect

- **The client owns idempotency for order placement.** A single "place order" intent must never create more
  than one order — across a double-click, a transparent retry, or a re-submit after a timeout. The intake
  (`POST /api/orders`) dedups on a client-supplied `Idempotency-Key` **header**. The `booking` slice mints and
  holds that key: **one key per intent (outbound trip + qty), reused across retries, re-minted only when the
  intent changes or after a terminal order.** Generate it with `crypto.randomUUID()`. Never put it in the body.
- **Identity comes from the token, never the form.** The buyer is taken from the validated JWT server-side.
- **The SPA never sees or transmits card data (PCI SAQ A).** Payment is the **PayPal Checkout button** — the
  buyer approves in PayPal's own hosted flow (redirect/popup); any card/credentials are entered on PayPal, so
  they never enter our bundle, state, or any request (`lib/paypal.ts` loads the `buttons` component +
  `PaymentForm.tsx` renders it). Checkout first opens a session (`POST /api/orders/{id}/paypal-session` →
  `{clientId, clientToken, payPalOrderId}`; `clientId` is public, no secret in the bundle — `clientToken` is
  currently unused by the button flow), the button approves that order in PayPal, and `POST /pay` carries only
  the approved `payPalOrderId`. Never add our own card input, and don't put PSP secrets in the bundle. `pay`
  carries no card data — just the approved order id.
- **The hold window is backend-authoritative.** Countdowns are seeded from the order's `remainingSeconds` and
  tick locally for display only (`useCountdown`). The backend re-checks at pay and sweeps at expiry — the UI is
  never the authority on expiry.
- **Degrade, don't crash.** A declined/expired/sold-out order is a normal outcome to render (see
  `RejectedView` + `rejectionMessage`). SSE down ⇒ the observer falls back to adaptive-backoff polling
  (`useOrderStream`); checkout is unaffected. Surface the HTTP status/body on error (`describeError`) — never
  swallow it.
- **Call only public services.** In prod the SPA speaks catalog, orders, users, the notifications SSE, and the
  public `GET /api/inventory/availability`. Never `/api/inventory/items` or `/api/payments` directly — the
  payment outcome is read from the order status.

## Design system

Tokens live in `tailwind.config.js` (the Stitch "EuroTransit Signature" export, transcribed verbatim):
Material-style colour roles (`surface*`, `on-*`, `primary*`, `*-fixed`, `accent` = the CTA orange),
role-named type scale (`text-display-lg`, `text-headline-md`, `text-label-tabular`, …) and spacing
(`px-margin-desktop`, `gap-xl`, `max-w-max-width`, …). **Use the tokens, not raw hex/px.** Prices, times and
countdowns use `tabular-nums` + the `label-tabular` role. Dark mode is a `.dark` class toggle
(`lib/theme.ts` + `ThemeToggle`) defaulting to the OS setting — style both themes.

## Do

- Keep API calls in RTK Query endpoints (`features/*/*Api.ts`); keep components presentational.
- Handle every request's error/empty/loading path explicitly (`components/ui/QueryStates.tsx`).
- Type responses against `src/types.ts`; keep those shapes aligned with the services' DTOs.
- Reuse the shared components (`TripCard`/`StationLine`, `SagaTracker`, `HoldWindowCard`, `Badge`, `Button`).

## Don't

- Don't place an order without a stable `Idempotency-Key` for the attempt.
- Don't put buyer identity, secrets, or card/PSP data in a request body or the bundle.
- Don't call internal services directly, and don't route SSE through Axios/RTK Query.
- Don't hardcode colours/spacing outside the token scale; don't swallow errors.

## Backend contract notes

A few storefront reads may not be wired on the backend yet; the SPA is built to the target contract and
degrades until they land: **my-bookings** (`GET /api/orders`, buyer-scoped), **per-trip availability**
(`GET /api/inventory/availability/{tripCode}` — currently derived from the bulk list), **stations `?q=`
typeahead** (currently client-side filtered), and **public browse** (catalog/availability without a token).

## Build & run

- `just dev` — Vite dev server + `/api` proxy at `http://localhost:5173` (hot reload).
- `just build` — `tsc --noEmit` type-check + production build into `dist/` (the type-check gates the build).
- `just test` — Vitest + Testing Library + MSW unit/integration tests.
- Prod image built + pushed by the app-repo CI (`build-frontend` job, bakes `VITE_KEYCLOAK_URL`); the config
  repo chart deploys it (`frontend.enabled: true`). Kind image via the config repo's `just images-kind`.
