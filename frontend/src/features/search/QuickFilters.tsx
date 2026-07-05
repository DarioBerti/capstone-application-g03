import { useState } from 'react'

// Genuinely-functional max-price filter plus honest presentational chrome (all EuroTransit routes are
// direct; there is no refundable concept in the domain, so those controls are informational only).
export default function QuickFilters({
  maxPriceCents,
  onMaxPrice,
}: {
  maxPriceCents?: number
  onMaxPrice: (cents: number | undefined) => void
}) {
  const [euros, setEuros] = useState(maxPriceCents != null ? String(maxPriceCents / 100) : '')

  const apply = (v: string) => {
    setEuros(v)
    const n = Number(v)
    onMaxPrice(v && Number.isFinite(n) && n > 0 ? Math.round(n * 100) : undefined)
  }

  return (
    <div className="bg-surface-container-low border border-outline-variant/30 rounded-xl p-lg">
      <div className="flex justify-between items-center mb-md">
        <h3 className="font-label-tabular text-label-tabular">Quick Filters</h3>
        <button className="text-primary font-label-caps text-label-caps" onClick={() => apply('')}>
          Clear all
        </button>
      </div>
      <div className="space-y-md">
        <div>
          <label className="font-label-caps text-label-caps text-on-surface-variant block mb-xs" htmlFor="max-price">
            Max price (€)
          </label>
          <input
            id="max-price"
            type="number"
            min={0}
            inputMode="numeric"
            placeholder="Any"
            value={euros}
            onChange={(e) => apply(e.target.value)}
            className="w-full p-sm bg-surface-container-lowest border border-outline-variant rounded-lg focus:ring-2 focus:ring-primary focus:outline-none tabular-nums"
          />
        </div>
        <label className="flex items-center justify-between opacity-60 cursor-not-allowed">
          <span className="font-body-sm text-body-sm">Direct trains only</span>
          <input type="checkbox" checked readOnly disabled className="rounded border-outline-variant text-primary h-5 w-5" />
        </label>
        <p className="font-body-sm text-body-sm text-on-surface-variant">All EuroTransit routes are direct.</p>
      </div>
    </div>
  )
}
