import { useCallback, useEffect, useState } from 'react'
import { api } from '../api'
import type { InventoryItem, Station, Trip } from '../types'

const euro = (cents: number) => `${(cents / 100).toFixed(2)} €`
// departureTime/arrivalTime are ISO-8601 with offset; show just the wall-clock HH:mm for the demo.
const hhmm = (iso: string) =>
  new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })

export default function CatalogPanel({ onSelect }: { onSelect: (tripCode: string) => void }) {
  const [trips, setTrips] = useState<Trip[]>([])
  const [seats, setSeats] = useState<Record<string, InventoryItem>>({})
  const [stations, setStations] = useState<Station[]>([])
  const [origin, setOrigin] = useState('')
  const [destination, setDestination] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  // Stations are stable reference data: load them once to populate the origin/destination pickers.
  useEffect(() => {
    api.listStations().then(setStations).catch(() => setStations([]))
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      // Inventory is best-effort here: catalog is the source of trips, seats are an overlay keyed on trip code.
      const [page, items] = await Promise.all([
        api.listTrips({ origin: origin || undefined, destination: destination || undefined, size: 50 }),
        api.listInventory().catch(() => [] as InventoryItem[]),
      ])
      setTrips(page.content)
      setSeats(Object.fromEntries(items.map((i) => [i.tripCode, i])))
    } catch (e) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }, [origin, destination])

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

      <div className="form" style={{ flexDirection: 'row', gap: '0.5rem' }}>
        <label>
          From
          <select value={origin} onChange={(e) => setOrigin(e.target.value)}>
            <option value="">Any</option>
            {stations.map((s) => (
              <option key={s.code} value={s.code}>{s.city} ({s.code})</option>
            ))}
          </select>
        </label>
        <label>
          To
          <select value={destination} onChange={(e) => setDestination(e.target.value)}>
            <option value="">Any</option>
            {stations.map((s) => (
              <option key={s.code} value={s.code}>{s.city} ({s.code})</option>
            ))}
          </select>
        </label>
      </div>

      {error && <p className="error">{error}</p>}
      <table>
        <thead>
          <tr>
            <th>Trip</th>
            <th>Departs</th>
            <th>Price</th>
            <th>Seats</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {trips.map((t) => {
            const inv = seats[t.code]
            const soldOut = inv ? inv.available <= 0 : false
            return (
              <tr key={t.code}>
                <td>
                  <div className="name">{t.origin.city} → {t.destination.city}</div>
                  <div className="muted mono">{t.carrierCode}{t.serviceNumber} · {t.code}</div>
                </td>
                <td>
                  {hhmm(t.departureTime)} → {hhmm(t.arrivalTime)}
                </td>
                <td>{euro(t.priceCents)}</td>
                <td className={soldOut ? 'error' : ''}>
                  {inv ? `${inv.available}/${inv.totalSeats}` : '—'}
                </td>
                <td>
                  <button onClick={() => onSelect(t.code)}>Use</button>
                </td>
              </tr>
            )
          })}
          {trips.length === 0 && !loading && (
            <tr>
              <td colSpan={5} className="muted">
                No trips. Is catalog running and seeded (V2__seed_demo_data)?
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </section>
  )
}
