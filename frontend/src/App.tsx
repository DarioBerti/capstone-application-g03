import { useEffect, useState } from 'react'
import CatalogPanel from './components/CatalogPanel'
import BookingPanel from './components/BookingPanel'
import SagaTimeline from './components/SagaTimeline'
import { api } from './api'
import { streamOrder } from './orderStream'
import { currentUser, isAuthenticated, login, logout } from './auth'
import type { Order, OrderStatus, Profile } from './types'

const isTerminal = (s: OrderStatus) => s === 'CONFIRMED' || s === 'REJECTED'
// "Live" = a state the backend can still transition on its own, so it is worth observing. A fresh DRAFT only
// changes on the user's next action (whose HTTP reply already carries the new state) and a terminal order never
// changes again — neither is streamed or polled. HELD/PAYING/AUTHORIZED are.
const isLive = (s: OrderStatus) => !isTerminal(s) && s !== 'DRAFT'

// The booking is observed over a single SSE stream (Notifications relays each transition). If the stream can't
// be held open, we DEGRADE to an adaptive backoff poll so the UI still reaches a terminal state — Notifications
// is off the critical path, so this never affects checkout. A stream that stays open at least STREAM_HEALTHY_MS
// before dropping is a normal reconnect (blip / pod restart); one that ends almost immediately is a bad/absent
// endpoint, so we back off and, after MAX_SSE_RETRIES such attempts, fall back to polling — never a hot loop.
const MAX_SSE_RETRIES = 3
const SSE_RETRY_MS = 1500
const STREAM_HEALTHY_MS = 2000
const MIN_POLL_MS = 1000
const MAX_POLL_MS = 10000

export default function App() {
  const [tripCode, setTripCode] = useState('')
  // Single source of truth for the active booking: BookingPanel's mutations report here, and one poller keeps
  // it fresh so a leg flips PENDING->RESERVED (unlocking Pay) and the saga is observed to a terminal state.
  // The frontend is NEVER the authority on expiry — it only reflects the server's status/remainingSeconds.
  const [order, setOrder] = useState<Order | null>(null)
  const authed = isAuthenticated()
  const [profile, setProfile] = useState<Profile | null>(null)

  const orderId = order?.orderId ?? null
  const status = order?.status
  // Only observe while the backend can still advance the booking on its own. Keyed into the effect below so a
  // DRAFT->HELD start opens the stream and a terminal state tears it down; the intermediate HELD/PAYING/
  // AUTHORIZED transitions all keep this true, so the ONE stream is never remounted mid-saga.
  const observe = !!status && isLive(status)

  // Once signed in, fetch (and just-in-time provision) the caller's profile from the users service.
  useEffect(() => {
    if (!authed) return
    api.getMyProfile().then(setProfile).catch(() => setProfile(null))
  }, [authed])

  // Observe the booking to a terminal state over ONE SSE stream (Notifications pushes each saga transition),
  // rather than polling. Nothing runs for a DRAFT/terminal order (observe=false). If the stream can't be held
  // open — Notifications is off the critical path and allowed to be down — we DEGRADE to the adaptive poll.
  useEffect(() => {
    if (!orderId || !observe) return
    const controller = new AbortController()
    let cancelled = false
    let last: Order | null = order
    let pollTimer: number | null = null

    // Reflect a fresh snapshot. The payment outcome is conveyed by the order itself (status +
    // rejectionReason=PAYMENT_DECLINED), so there's no separate internal payments call — in prod the
    // SPA talks only to public services.
    const apply = (o: Order) => {
      if (cancelled) return
      last = o
      setOrder(o)
    }

    // Fallback observer (only if the stream can't be held open): the adaptive backoff poll, terminal-aware.
    let delay = MIN_POLL_MS
    const poll = async () => {
      try {
        const o = await api.getOrder(orderId)
        if (cancelled) return
        apply(o)
        if (!isLive(o.status)) return
      } catch {
        if (cancelled) return
      }
      pollTimer = window.setTimeout(() => void poll(), delay)
      delay = Math.min(delay * 2, MAX_POLL_MS)
    }

    // Primary observer: sync current state once per (re)connect, then stream live transitions. A stream that
    // stayed open a while then dropped is a normal reconnect (reset the budget); one that ends almost at once is
    // a bad/absent endpoint, so it counts against the budget and, once spent, degrades to polling.
    const stream = async () => {
      let attempt = 0
      while (!cancelled) {
        const startedAt = Date.now()
        try {
          await api.getOrder(orderId).then(apply).catch(() => {})
          await streamOrder(orderId, apply, controller.signal)
        } catch {
          // a dropped/unreachable stream — handled by the duration check below
        }
        if (cancelled) return
        if (last && isTerminal(last.status)) return // reached the end of the saga
        if (Date.now() - startedAt >= STREAM_HEALTHY_MS) {
          attempt = 0
        } else if (++attempt >= MAX_SSE_RETRIES) {
          void poll() // stream won't hold open -> graceful fallback to polling
          return
        }
        await new Promise((r) => window.setTimeout(r, Math.min(SSE_RETRY_MS * Math.max(1, attempt), MAX_POLL_MS)))
      }
    }

    void stream()
    return () => {
      cancelled = true
      controller.abort()
      if (pollTimer) window.clearTimeout(pollTimer)
    }
  }, [orderId, observe])

  return (
    <div className="app">
      <header className="app-head">
        <div className="app-head-row">
          <h1>EuroTransit</h1>
          <div className="auth-box">
            {authed ? (
              <>
                <span className="muted">{profile?.email ?? currentUser() ?? 'signed in'}</span>
                <button onClick={logout}>Sign out</button>
              </>
            ) : (
              <button onClick={login}>Sign in</button>
            )}
          </div>
        </div>
        <p className="muted">
          Book a journey with the two-phase, human-gated saga: hold your seats, then pay within the
          backend-enforced window. Status updates stream live as the booking reaches a terminal state.
        </p>
      </header>

      {authed ? (
        <main className="grid">
          <CatalogPanel onSelect={setTripCode} />
          <BookingPanel tripCode={tripCode} onTripCode={setTripCode} order={order} onOrder={setOrder} />
          <SagaTimeline order={order} />
        </main>
      ) : (
        <main className="grid">
          <p className="muted">
            Sign in with Keycloak to browse the catalog and drive the saga — every API call now carries
            your bearer token.
          </p>
        </main>
      )}
    </div>
  )
}
