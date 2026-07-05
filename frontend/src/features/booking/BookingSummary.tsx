import { useNavigate } from 'react-router-dom'
import Icon from '../../components/Icon'
import Spinner from '../../components/ui/Spinner'
import HoldWindowCountdown from './HoldWindowCountdown'
import { useBookingActions } from './useBookingActions'
import { useAppSelector } from '../../app/hooks'
import { formatCents } from '../../lib/format'
import type { OrderLeg } from '../../types'

const ACTIVE_LEG = new Set(['PENDING', 'RESERVED', 'CONFIRMED'])

// Sidebar summary of the active booking (the reserved legs, total, hold-window countdown) with the
// Proceed-to-Checkout action. Renders nothing until a booking is started.
export default function BookingSummary() {
  const navigate = useNavigate()
  const order = useAppSelector((s) => s.booking.order)
  const { dropLeg, removing } = useBookingActions()

  if (!order) {
    return (
      <div className="bg-surface-container-highest rounded-xl p-lg shadow-lg border border-primary/10 text-center">
        <Icon name="shopping_basket" className="text-3xl text-primary mb-sm" />
        <h2 className="font-title-lg text-title-lg text-on-background mb-xs">Your Summary</h2>
        <p className="font-body-sm text-body-sm text-on-surface-variant">
          Select a journey to hold your seats and start a booking.
        </p>
      </div>
    )
  }

  const activeLegs = order.legs.filter((l) => ACTIVE_LEG.has(l.status))

  return (
    <div className="bg-surface-container-highest rounded-xl p-lg shadow-lg border border-primary/10">
      <div className="flex justify-between items-center mb-lg">
        <h2 className="font-title-lg text-title-lg text-on-background">Your Summary</h2>
        <Icon name="shopping_basket" className="text-primary" />
      </div>

      <div className="space-y-md pb-lg border-b border-outline-variant/30">
        {activeLegs.map((leg) => (
          <LegRow key={leg.legId} leg={leg} onRemove={() => dropLeg(order.orderId, leg.legId)} removing={removing} />
        ))}
        {activeLegs.length === 0 && (
          <p className="font-body-sm text-body-sm text-on-surface-variant">No active legs.</p>
        )}
      </div>

      <div className="py-lg space-y-sm">
        <div className="flex justify-between items-center">
          <span className="font-body-md text-body-md text-on-surface-variant">Ticket total</span>
          <span className="font-label-tabular text-label-tabular tabular-nums">
            {formatCents(order.amountCents, order.currency)}
          </span>
        </div>
        <div className="flex justify-between items-center">
          <span className="font-body-md text-body-md text-on-surface-variant">Booking fee</span>
          <span className="font-label-tabular text-label-tabular tabular-nums">{formatCents(0, order.currency)}</span>
        </div>
        <div className="flex justify-between items-center pt-md">
          <span className="font-headline-md text-headline-md text-on-background">Total price</span>
          <span className="font-display-lg text-title-lg tabular-nums text-primary font-extrabold">
            {formatCents(order.amountCents, order.currency)}
          </span>
        </div>
      </div>

      <button
        onClick={() => navigate(`/orders/${order.orderId}`)}
        className="w-full py-md bg-accent text-on-background rounded-xl font-bold text-lg hover:brightness-105 transition-all shadow-md active:scale-[0.98] flex items-center justify-center gap-sm"
      >
        Proceed to Checkout
        <Icon name="arrow_forward" />
      </button>

      <HoldWindowCountdown remainingSeconds={order.remainingSeconds} />
    </div>
  )
}

function LegRow({ leg, onRemove, removing }: { leg: OrderLeg; onRemove: () => void; removing: boolean }) {
  return (
    <div className="flex justify-between items-start">
      <div>
        <p className="font-label-tabular text-label-tabular">
          {leg.direction === 'OUTBOUND' ? 'Outbound journey' : 'Return journey'}
        </p>
        <p className="font-body-sm text-body-sm text-on-surface-variant">
          {leg.tripCode} · {leg.qty} {leg.qty > 1 ? 'passengers' : 'passenger'} · {leg.status}
        </p>
      </div>
      <button
        onClick={onRemove}
        disabled={removing}
        className="text-on-surface-variant hover:text-error font-label-caps text-label-caps disabled:opacity-50"
        aria-label={`Remove ${leg.direction.toLowerCase()} leg`}
      >
        {removing ? <Spinner className="text-sm" /> : 'Remove'}
      </button>
    </div>
  )
}
