import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { Authorization, Order, OrderStatus } from '../types'

const STEPS: OrderStatus[] = ['PENDING', 'RESERVED', 'CONFIRMED']
const isTerminal = (s: OrderStatus) => s === 'CONFIRMED' || s === 'REJECTED'
const euro = (cents: number) => `${(cents / 100).toFixed(2)} €`

export default function SagaTimeline({ orderId }: { orderId: string | null }) {
  const [order, setOrder] = useState<Order | null>(null)
  const [auth, setAuth] = useState<Authorization | null>(null)
  const [error, setError] = useState<string | null>(null)
  const timer = useRef<number | null>(null)

  useEffect(() => {
    setOrder(null)
    setAuth(null)
    setError(null)
    if (timer.current) window.clearInterval(timer.current)
    if (!orderId) return

    const stop = () => {
      if (timer.current) {
        window.clearInterval(timer.current)
        timer.current = null
      }
    }

    const tick = async () => {
      try {
        const o = await api.getOrder(orderId)
        setOrder(o)
        // Payment row is informational; it may 404 until/unless an authorization exists.
        api.getAuthorization(orderId).then(setAuth).catch(() => {})
        if (isTerminal(o.status)) stop()
      } catch (e) {
        setError(String(e))
        stop()
      }
    }

    void tick()
    timer.current = window.setInterval(() => void tick(), 1000)
    return stop
  }, [orderId])

  if (!orderId) {
    return (
      <section className="panel">
        <div className="panel-head">
          <h2>Saga</h2>
        </div>
        <p className="muted">Place an order to watch it flow to a terminal state.</p>
      </section>
    )
  }

  const rejected = order?.status === 'REJECTED'
  // PENDING is always reached once accepted; on rejection we only light PENDING.
  const reachedIdx = order ? (rejected ? 0 : STEPS.indexOf(order.status)) : -1

  return (
    <section className="panel">
      <div className="panel-head">
        <h2>Saga</h2>
        <span className="muted mono">{orderId.slice(0, 8)}…</span>
      </div>
      {error && <p className="error">{error}</p>}

      <ol className="timeline">
        {STEPS.map((s, i) => {
          const done = reachedIdx >= i
          const active = order?.status === s && !isTerminal(s)
          return (
            <li key={s} className={`${done ? 'done' : ''} ${active ? 'active' : ''}`}>
              {s}
              {active ? ' …' : ''}
            </li>
          )
        })}
        {rejected && <li className="rejected">REJECTED · {order?.rejectionReason}</li>}
      </ol>

      <dl className="kv">
        <dt>Order</dt>
        <dd>
          <span className={`badge ${order?.status?.toLowerCase()}`}>{order?.status ?? '…'}</span>
        </dd>
        <dt>Amount</dt>
        <dd>{order ? euro(order.amountCents) : '…'}</dd>
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
