import { useEffect } from 'react'
import { useAppDispatch } from '../../app/hooks'
import { streamOrder } from '../../orderStream'
import { ordersApi } from '../orders/ordersApi'
import { orderUpdated } from './bookingSlice'
import type { Order, OrderStatus } from '../../types'

export const isTerminal = (s: OrderStatus) => s === 'CONFIRMED' || s === 'REJECTED'
// "Live" = a state the backend can still transition on its own, so it is worth observing. A fresh DRAFT only
// changes on the user's next action (whose HTTP reply already carries the new state) and a terminal order
// never changes again. HELD/PAYING/AUTHORIZED are.
export const isLive = (s: OrderStatus) => !isTerminal(s) && s !== 'DRAFT'

// A stream that stays open at least STREAM_HEALTHY_MS is a normal reconnect (blip/pod restart); one that ends
// almost immediately is a bad/absent endpoint, so it counts against the retry budget and, once spent,
// degrades to polling — never a hot loop. (Ported from the original debug SPA's App.tsx.)
const MAX_SSE_RETRIES = 3
const SSE_RETRY_MS = 1500
const STREAM_HEALTHY_MS = 2000
const MIN_POLL_MS = 1000
const MAX_POLL_MS = 10000

// Observe a booking to a terminal state over ONE SSE stream (Notifications pushes each saga transition),
// dispatching each snapshot into the booking slice. Nothing runs for a DRAFT/terminal order. If the stream
// can't be held open — Notifications is off the critical path and allowed to be down — it DEGRADES to an
// adaptive backoff poll so the UI still reaches a terminal state.
export function useOrderStream(orderId: string | null, status: OrderStatus | undefined): void {
  const dispatch = useAppDispatch()
  const observe = !!status && isLive(status)

  useEffect(() => {
    if (!orderId || !observe) return
    const controller = new AbortController()
    let cancelled = false
    let last: Order | null = null
    let pollTimer: number | null = null

    const apply = (o: Order) => {
      if (cancelled) return
      last = o
      dispatch(orderUpdated(o))
    }

    const fetchSnapshot = () =>
      dispatch(ordersApi.endpoints.getOrder.initiate(orderId, { forceRefetch: true })).unwrap()

    let delay = MIN_POLL_MS
    const poll = async () => {
      try {
        const o = await fetchSnapshot()
        if (cancelled) return
        apply(o)
        if (!isLive(o.status)) return
      } catch {
        if (cancelled) return
      }
      pollTimer = window.setTimeout(() => void poll(), delay)
      delay = Math.min(delay * 2, MAX_POLL_MS)
    }

    const stream = async () => {
      let attempt = 0
      while (!cancelled) {
        const startedAt = Date.now()
        try {
          await fetchSnapshot().then(apply).catch(() => {})
          await streamOrder(orderId, apply, controller.signal)
        } catch {
          // a dropped/unreachable stream — handled by the duration check below
        }
        if (cancelled) return
        if (last && isTerminal(last.status)) return
        if (Date.now() - startedAt >= STREAM_HEALTHY_MS) {
          attempt = 0
        } else if (++attempt >= MAX_SSE_RETRIES) {
          void poll()
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
  }, [orderId, observe, dispatch])
}
