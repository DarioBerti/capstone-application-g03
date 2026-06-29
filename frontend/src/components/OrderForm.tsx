import { useState, type FormEvent } from 'react'
import { api } from '../api'

interface Props {
  tripCode: string
  onTripCode: (tripCode: string) => void
  onPlaced: (orderId: string) => void
}

export default function OrderForm({ tripCode, onTripCode, onPlaced }: Props) {
  const [qty, setQty] = useState(1)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [accepted, setAccepted] = useState<string | null>(null)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    setAccepted(null)
    try {
      const order = await api.placeOrder({ tripCode, qty })
      setAccepted(`202 Accepted · ${order.orderId} (${order.status})`)
      onPlaced(order.orderId)
    } catch (e) {
      setError(String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="panel">
      <div className="panel-head">
        <h2>Place order</h2>
      </div>
      <form onSubmit={submit} className="form">
        <label>
          Trip code
          <input
            value={tripCode}
            onChange={(e) => onTripCode(e.target.value)}
            placeholder="pick from catalog or type a code (e.g. FR9592-20260629)"
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
          {busy ? 'Placing…' : 'POST /api/orders'}
        </button>
      </form>
      {accepted && <p className="ok">{accepted}</p>}
      {error && <p className="error">{error}</p>}
    </section>
  )
}
