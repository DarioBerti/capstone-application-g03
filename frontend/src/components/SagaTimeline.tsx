import { useEffect, useState } from 'react'
import type { Authorization, Order, OrderStatus, RejectionReason } from '../types'

// The booking's progression, in order. DRAFT (no legs yet) precedes the window; the timeline lights up from HELD.
const STEPS: OrderStatus[] = ['HELD', 'PAYING', 'AUTHORIZED', 'CONFIRMED']
const isTerminal = (s: OrderStatus) => s === 'CONFIRMED' || s === 'REJECTED'
const euro = (cents: number) => `${(cents / 100).toFixed(2)} €`

// User-facing explanation per rejection reason — the whole point of the dual-cause design is that a system
// fault reads differently from a user who simply let the hold lapse.
const REJECTION_COPY: Record<RejectionReason, string> = {
  SOLD_OUT: 'Those seats sold out before the booking could be held.',
  PAYMENT_DECLINED: 'Your payment was declined — nothing was charged.',
  RESERVATION_EXPIRED: 'A held seat lapsed before the booking could be confirmed — nothing was charged.',
  EXPIRED_NO_PAYMENT: 'Your reservation window elapsed before payment. The seats were released — please rebook.',
  SERVICE_UNAVAILABLE: 'We couldn’t complete payment due to a system issue on our side. Nothing was charged — please try again.',
}

const mmss = (total: number) => {
  const s = Math.max(0, Math.floor(total))
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}

export default function SagaTimeline({ order, auth }: { order: Order | null; auth: Authorization | null }) {
  // Display-only countdown: seed from the server's remainingSeconds on every fresh order, then decrement
  // locally between polls so it ticks smoothly without a 1s poll. The backend stays the sole expiry authority.
  const [remaining, setRemaining] = useState<number | null>(null)
  const seed = order?.remainingSeconds ?? null
  useEffect(() => setRemaining(seed), [seed, order?.orderId])

  const live = order?.status === 'HELD' || order?.status === 'PAYING'
  useEffect(() => {
    if (!live || seed == null) return
    const id = window.setInterval(() => setRemaining((r) => (r == null ? r : Math.max(0, r - 1))), 1000)
    return () => window.clearInterval(id)
  }, [live, seed, order?.orderId])

  if (!order) {
    return (
      <section className="panel">
        <div className="panel-head">
          <h2>Saga</h2>
        </div>
        <p className="muted">Start a booking, add legs, and pay to watch it flow to a terminal state.</p>
      </section>
    )
  }

  const rejected = order.status === 'REJECTED'
  const reachedIdx = rejected ? -1 : STEPS.indexOf(order.status)
  const showCountdown = live && remaining != null

  return (
    <section className="panel">
      <div className="panel-head">
        <h2>Saga</h2>
        <span className="muted mono">{order.orderId.slice(0, 8)}…</span>
      </div>

      {showCountdown && (
        <p className={`countdown ${remaining! <= 60 ? 'urgent' : ''}`}>
          Hold expires in <b>{mmss(remaining!)}</b>
          <span className="muted"> (enforced by the backend)</span>
        </p>
      )}

      <ol className="timeline">
        {STEPS.map((s, i) => {
          const done = reachedIdx >= i
          const active = order.status === s && !isTerminal(s)
          return (
            <li key={s} className={`${done ? 'done' : ''} ${active ? 'active' : ''}`}>
              {s}
              {active ? ' …' : ''}
            </li>
          )
        })}
        {rejected && <li className="rejected">REJECTED · {order.rejectionReason}</li>}
      </ol>

      {rejected && order.rejectionReason && (
        <p className={`reject-note ${order.rejectionReason === 'SERVICE_UNAVAILABLE' ? 'system-fault' : ''}`}>
          {REJECTION_COPY[order.rejectionReason]}
        </p>
      )}

      <dl className="kv">
        <dt>Order</dt>
        <dd>
          <span className={`badge ${order.status.toLowerCase()}`}>{order.status}</span>
        </dd>
        <dt>Amount</dt>
        <dd>{euro(order.amountCents)}</dd>
        <dt>Payment</dt>
        <dd>
          {auth ? (
            <span className={`badge ${auth.status.toLowerCase()}`}>
              {auth.status}
              {auth.replayed ? ' · replayed' : ''}
            </span>
          ) : (
            <span className="muted">—</span>
          )}
        </dd>
      </dl>
    </section>
  )
}
