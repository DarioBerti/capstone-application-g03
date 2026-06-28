import { useCallback, useEffect, useState } from 'react'
import { api } from '../api'
import type { InventoryItem, Product } from '../types'

const euro = (cents: number) => `${(cents / 100).toFixed(2)} €`

export default function CatalogPanel({ onSelect }: { onSelect: (productId: string) => void }) {
  const [products, setProducts] = useState<Product[]>([])
  const [seats, setSeats] = useState<Record<string, InventoryItem>>({})
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      // Inventory is best-effort here: catalog is the source of products, seats are an overlay.
      const [page, items] = await Promise.all([
        api.listProducts({ size: 50 }),
        api.listInventory().catch(() => [] as InventoryItem[]),
      ])
      setProducts(page.content)
      setSeats(Object.fromEntries(items.map((i) => [i.productId, i])))
    } catch (e) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  return (
    <section className="panel">
      <div className="panel-head">
        <h2>Catalog</h2>
        <button onClick={() => void load()} disabled={loading}>
          {loading ? '…' : 'Refresh'}
        </button>
      </div>
      {error && <p className="error">{error}</p>}
      <table>
        <thead>
          <tr>
            <th>Product</th>
            <th>Price</th>
            <th>Seats</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {products.map((p) => {
            const inv = seats[p.productId]
            const soldOut = inv ? inv.available <= 0 : false
            return (
              <tr key={p.productId}>
                <td>
                  <div className="name">{p.name}</div>
                  <div className="muted mono">{p.productId}</div>
                </td>
                <td>{euro(p.priceCents)}</td>
                <td className={soldOut ? 'error' : ''}>
                  {inv ? `${inv.available}/${inv.totalSeats}` : '—'}
                </td>
                <td>
                  <button onClick={() => onSelect(p.productId)}>Use</button>
                </td>
              </tr>
            )
          })}
          {products.length === 0 && !loading && (
            <tr>
              <td colSpan={4} className="muted">
                No products. Is catalog running on :8083 and seeded (V2__seed_demo_data)?
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </section>
  )
}
