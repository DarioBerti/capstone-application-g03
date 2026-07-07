import { Link } from 'react-router-dom'
import Icon from '../../components/Icon'
import Spinner from '../../components/ui/Spinner'
import { formatCents } from '../../lib/format'
import { ACTIVE_LEG_STATUSES } from '../booking/legStatus'
import type { Order, OrderLeg } from '../../types'

// Right-column order summary on checkout: the reserved legs and the amount to be authorized. While the hold is
// still open (order HELD) each leg can be Changed — dropped so the user re-picks it on /search — and a one-way
// booking can add a return. Presentational: the checkout page owns the drop + navigation via onChangeLeg.
export default function TripSummary({
  order,
  onChangeLeg,
  changing = false,
}: {
  order: Order
  onChangeLeg?: (leg: OrderLeg) => void
  changing?: boolean
}) {
  const legs = order.legs.filter((l) => ACTIVE_LEG_STATUSES.has(l.status))
  const hasReturn = legs.some((l) => l.direction === 'RETURN')
  const editable = order.status === 'HELD'
  const canAddReturn = editable && legs.length > 0 && !hasReturn
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
                {leg.tripCode} · {leg.qty} pax
              </p>
              {editable && onChangeLeg && (
                <button
                  onClick={() => onChangeLeg(leg)}
                  disabled={changing}
                  className="mt-xs flex items-center gap-xs font-label-caps text-xs text-primary hover:underline disabled:opacity-50"
                  aria-label={`Change ${leg.direction.toLowerCase()} trip`}
                >
                  {changing ? <Spinner className="text-sm" /> : <Icon name="swap_horiz" className="text-sm" />}
                  Change
                </button>
              )}
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
