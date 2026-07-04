import { useRef, useState } from 'react'
import { api } from '../api'
import type { LegDirection, Order } from '../types'

interface Props {
  tripCode: string // pre-filled from the catalog selection
  onTripCode: (tripCode: string) => void
  order: Order | null // the active booking, owned and polled by App (single source of truth)
  onOrder: (order: Order) => void // report a mutation result up so App's poller picks it up
}

const euro = (cents: number) => `${(cents / 100).toFixed(2)} €`
const canAddLegs = (o: Order | null) => o != null && (o.status === 'DRAFT' || o.status === 'HELD')
const activeLeg = (s: string) => s === 'PENDING' || s === 'RESERVED' || s === 'CONFIRMED'

export default function BookingPanel({ tripCode, onTripCode, order, onOrder }: Props) {
  const [direction, setDirection] = useState<LegDirection>('OUTBOUND')
  const [qty, setQty] = useState(1)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // One idempotency key per "start a booking" intent: reused across retries of the create until it succeeds,
  // so a double-click / network retry replays the same draft instead of creating a second one.
  const createKey = useRef<string | null>(null)

  // Wraps a mutation with busy/error handling; keeps the panel thin and every error path surfaced.
  const run = async (fn: () => Promise<Order>) => {
    setBusy(true)
    setError(null)
    try {
      onOrder(await fn())
    } catch (e) {
      setError(String(e))
    } finally {
      setBusy(false)
    }
  }

  const startBooking = () =>
    run(async () => {
      const key = (createKey.current ??= crypto.randomUUID())
      const o = await api.createDraft(key)
      createKey.current = null // success: the next booking is a fresh intent
      return o
    })

  const addLeg = () => {
    if (!order) return
    run(() => api.addLeg(order.orderId, { direction, tripCode, qty }))
  }

  const removeLeg = (legId: string) => {
    if (!order) return
    run(() => api.removeLeg(order.orderId, legId))
  }

  const pay = () => {
    if (!order) return
    run(() => api.pay(order.orderId))
  }

  const hasReservedOutbound = order?.legs.some((l) => l.direction === 'OUTBOUND' && l.status === 'RESERVED') ?? false
  const settling = order?.legs.some((l) => l.status === 'PENDING') ?? false
  const payable = order?.status === 'HELD' && hasReservedOutbound && !settling

  return (
    <section className="panel">
      <div className="panel-head">
        <h2>Booking</h2>
        {order && <span className="muted mono">{order.orderId.slice(0, 8)}…</span>}
      </div>

      {!order || order.status === 'CONFIRMED' || order.status === 'REJECTED' ? (
        <button onClick={startBooking} disabled={busy}>
          {busy ? 'Starting…' : 'Start a new booking (POST /api/orders)'}
        </button>
      ) : (
        <>
          {canAddLegs(order) && (
            <form
              className="form"
              onSubmit={(e) => {
                e.preventDefault()
                addLeg()
              }}
            >
              <label>
                Direction
                <select value={direction} onChange={(e) => setDirection(e.target.value as LegDirection)}>
                  <option value="OUTBOUND">Outbound</option>
                  <option value="RETURN">Return</option>
                </select>
              </label>
              <label>
                Trip code
                <input
                  value={tripCode}
                  onChange={(e) => onTripCode(e.target.value)}
                  placeholder="pick from catalog or type a code"
                  required
                />
              </label>
              <label>
                Quantity
                <input
                  type="number"
                  min={1}
                  value={qty}
                  onChange={(e) => setQty(Math.max(1, Number(e.target.value)))}
                />
              </label>
              <button type="submit" disabled={busy || !tripCode}>
                {busy ? 'Adding…' : 'Add leg'}
              </button>
            </form>
          )}

          <ul className="legs">
            {order.legs.map((l) => (
              <li key={l.legId} className={`leg ${l.status.toLowerCase()}`}>
                <span className="mono">{l.direction}</span> {l.tripCode} ×{l.qty}
                <span className={`badge ${l.status.toLowerCase()}`}>{l.status}</span>
                <span className="muted">{euro(l.lineAmountCents)}</span>
                {canAddLegs(order) && activeLeg(l.status) && (
                  <button className="link" onClick={() => removeLeg(l.legId)} disabled={busy}>
                    remove
                  </button>
                )}
              </li>
            ))}
            {order.legs.length === 0 && <li className="muted">no legs yet — add an outbound leg to start the hold</li>}
          </ul>

          {order.status === 'HELD' && (
            <div className="pay-row">
              <button onClick={pay} disabled={busy || !payable}>
                {busy ? 'Paying…' : `Pay ${euro(order.amountCents)} (POST /pay)`}
              </button>
              {settling && <span className="muted">a reservation is still settling…</span>}
              {!hasReservedOutbound && !settling && <span className="muted">need a reserved outbound leg to pay</span>}
            </div>
          )}
        </>
      )}

      {error && <p className="error">{error}</p>}
    </section>
  )
}
