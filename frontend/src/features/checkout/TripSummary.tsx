import { formatCents } from '../../lib/format'
import type { Order } from '../../types'

const ACTIVE_LEG = new Set(['PENDING', 'RESERVED', 'CONFIRMED'])

// Right-column order summary on checkout: the reserved legs and the amount to be authorized.
export default function TripSummary({ order }: { order: Order }) {
  const legs = order.legs.filter((l) => ACTIVE_LEG.has(l.status))
  return (
    <div className="bg-surface-container-lowest p-lg rounded-xl shadow-sm border border-outline-variant/10">
      <h3 className="font-title-lg text-title-lg mb-md">Your Trip</h3>
      <div className="flex flex-col gap-md">
        {legs.map((leg) => (
          <div key={leg.legId} className="flex justify-between items-start">
            <div>
              <p className="font-label-tabular text-label-tabular">
                {leg.direction === 'OUTBOUND' ? 'Outbound' : 'Return'}
              </p>
              <p className="font-body-sm text-body-sm text-on-surface-variant">
                {leg.tripCode} · {leg.qty} {leg.qty > 1 ? 'pax' : 'pax'}
              </p>
            </div>
            <p className="font-label-tabular text-label-tabular tabular-nums">
              {formatCents(leg.lineAmountCents, order.currency)}
            </p>
          </div>
        ))}
        <div className="pt-md border-t-2 border-dashed border-outline-variant">
          <div className="flex justify-between items-end">
            <span className="font-title-lg text-title-lg">Total</span>
            <span className="font-display-lg text-headline-md text-primary tabular-nums">
              {formatCents(order.amountCents, order.currency)}
            </span>
          </div>
        </div>
      </div>
    </div>
  )
}
