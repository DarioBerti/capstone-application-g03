import { Link } from 'react-router-dom'
import Icon from '../../components/Icon'
import { formatCents } from '../../lib/format'
import type { Order } from '../../types'

const ACTIVE_LEG = new Set(['PENDING', 'RESERVED', 'CONFIRMED'])

// Right-column order summary on checkout: the reserved legs and the amount to be authorized. While the hold
// is still open and the booking is one-way, it also offers going back to add the return — /search resumes
// the "pick your return" flow from the active booking.
export default function TripSummary({ order }: { order: Order }) {
  const legs = order.legs.filter((l) => ACTIVE_LEG.has(l.status))
  const hasReturn = legs.some((l) => l.direction === 'RETURN')
  const canAddReturn = order.status === 'HELD' && legs.length > 0 && !hasReturn
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
        {canAddReturn && (
          <Link
            to="/search"
            className="flex items-center justify-center gap-xs py-sm border border-dashed border-outline-variant rounded-lg text-primary font-label-tabular text-body-sm hover:bg-surface-container transition-colors"
          >
            <Icon name="add" className="text-sm" />
            Add a return trip
          </Link>
        )}
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
