import { useState, type FormEvent } from 'react'
import { api } from '../api'

interface Props {
  productId: string
  onProductId: (productId: string) => void
  onPlaced: (orderId: string) => void
}

export default function OrderForm({ productId, onProductId, onPlaced }: Props) {
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
      const order = await api.placeOrder({ productId, qty })
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
          Product ID
          <input
            value={productId}
            onChange={(e) => onProductId(e.target.value)}
            placeholder="pick from catalog or type an id"
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
        <button type="submit" disabled={busy || !productId}>
          {busy ? 'Placing…' : 'POST /api/orders'}
        </button>
      </form>
      {accepted && <p className="ok">{accepted}</p>}
      {error && <p className="error">{error}</p>}
    </section>
  )
}
