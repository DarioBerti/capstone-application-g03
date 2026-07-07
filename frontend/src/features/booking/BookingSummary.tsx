import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import Icon from '../../components/Icon'
import Spinner from '../../components/ui/Spinner'
import HoldWindowCountdown from './HoldWindowCountdown'
import { useBookingActions } from './useBookingActions'
import { ACTIVE_LEG_STATUSES } from './legStatus'
import { useAppSelector } from '../../app/hooks'
import { useGetTripQuery } from '../catalog/catalogApi'
import { formatCents, formatDate, formatTime } from '../../lib/format'
import type { OrderLeg, OrderStatus } from '../../types'

// The cart only represents a booking the user can still act on. Once payment starts, the slice keeps the
// order snapshot for the checkout view, but it must never resurface here as a re-checkoutable cart.
const CART_STATUS = new Set<OrderStatus>(['DRAFT', 'HELD'])

// Sidebar cart for the active booking: one card per reserved leg (plus a pending-return placeholder while the
// booking is still one-way), the total, and the Proceed-to-Checkout action. Renders the empty state until a
// booking is started (or after the active one leaves the cart-able statuses).
export default function BookingSummary() {
  const navigate = useNavigate()
  const sliceOrder = useAppSelector((s) => s.booking.order)
  const { dropLeg, discardBooking, removing } = useBookingActions()
  const [confirmingDiscard, setConfirmingDiscard] = useState(false)
  const order = sliceOrder && CART_STATUS.has(sliceOrder.status) ? sliceOrder : null

  // Never carry an armed discard confirm across bookings: reset it whenever the active order changes
  // (a fresh booking, or the cart emptying) so a new cart never opens mid-confirm.
  useEffect(() => setConfirmingDiscard(false), [order?.orderId])

  if (!order) {
    return (
      <div className="bg-surface-container-highest rounded-xl p-lg shadow-lg border border-primary/10 text-center">
        <Icon name="shopping_basket" className="text-3xl text-primary mb-sm" />
        <h2 className="font-title-lg text-title-lg text-on-background mb-xs">Your Cart</h2>
        <p className="font-body-sm text-body-sm text-on-surface-variant">
          Select a journey to hold your seats and start a booking.
        </p>
      </div>
    )
  }

  const activeLegs = order.legs.filter((l) => ACTIVE_LEG_STATUSES.has(l.status))
  const hasReturn = activeLegs.some((l) => l.direction === 'RETURN')
  const outboundCode = activeLegs.find((l) => l.direction === 'OUTBOUND')?.tripCode

  return (
    <div className="bg-surface-container-highest rounded-xl p-lg shadow-lg border border-primary/10">
      <div className="flex justify-between items-center mb-lg">
        <h2 className="font-title-lg text-title-lg text-on-background">Your Cart</h2>
        <Icon name="shopping_basket" className="text-primary" />
      </div>

      <div className="space-y-md pb-lg border-b border-outline-variant/30">
        {activeLegs.map((leg) => (
          <LegCard
            key={leg.legId}
            leg={leg}
            currency={order.currency}
            onRemove={() => dropLeg(order.orderId, leg.legId)}
            removing={removing}
          />
        ))}
        {activeLegs.length === 0 && <p className="font-body-sm text-body-sm text-on-surface-variant">No active legs.</p>}
        {outboundCode && !hasReturn && <PendingReturnCard outboundCode={outboundCode} />}
      </div>

      <div className="py-lg">
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

      <DiscardBooking
        confirming={confirmingDiscard}
        removing={removing}
        onStart={() => setConfirmingDiscard(true)}
        onCancel={() => setConfirmingDiscard(false)}
        onConfirm={async () => {
          await discardBooking(order)
          setConfirmingDiscard(false)
        }}
      />

      <HoldWindowCountdown expiresAt={order.expiresAt} remainingSeconds={order.remainingSeconds} />
    </div>
  )
}

// Whole-booking discard: a quiet text button that expands to an inline two-step confirm (no modal, KISS)
// so releasing a live hold can't happen on a single stray tap. The confirm action drops every held leg.
function DiscardBooking({
  confirming,
  removing,
  onStart,
  onCancel,
  onConfirm,
}: {
  confirming: boolean
  removing: boolean
  onStart: () => void
  onCancel: () => void
  onConfirm: () => void
}) {
  if (!confirming) {
    return (
      <button
        onClick={onStart}
        className="w-full mt-md py-sm font-body-sm text-body-sm text-on-surface-variant hover:text-error transition-colors flex items-center justify-center gap-xs"
      >
        <Icon name="delete" className="text-base" />
        Discard booking
      </button>
    )
  }
  return (
    <div className="mt-md rounded-lg border border-error/30 bg-error/5 p-md">
      <p className="font-body-sm text-body-sm text-on-surface text-center mb-sm">
        Discard this booking and release the held seats?
      </p>
      <div className="flex gap-sm">
        <button
          onClick={onCancel}
          disabled={removing}
          className="flex-1 py-sm rounded-lg bg-surface-container text-on-surface font-bold hover:bg-surface-container-high transition-colors disabled:opacity-50"
        >
          Keep
        </button>
        <button
          onClick={onConfirm}
          disabled={removing}
          className="flex-1 py-sm rounded-lg bg-error text-on-error font-bold hover:brightness-110 transition-all disabled:opacity-50 flex items-center justify-center gap-xs"
        >
          {removing ? <Spinner className="text-sm" /> : 'Discard'}
        </button>
      </div>
    </div>
  )
}

// A reserved leg: the human route + departure resolved from its trip code (public + cached), the line price,
// and a Remove action. Applies to the outbound and the return alike.
function LegCard({
  leg,
  currency,
  onRemove,
  removing,
}: {
  leg: OrderLeg
  currency: string
  onRemove: () => void
  removing: boolean
}) {
  const { data: trip } = useGetTripQuery(leg.tripCode)
  const route = trip ? `${trip.origin.city} – ${trip.destination.city}` : leg.tripCode
  const when = trip ? `${formatDate(trip.departureTime)} · ${formatTime(trip.departureTime)}` : null

  return (
    <div className="relative bg-surface-container-lowest rounded-lg shadow-sm overflow-hidden border border-outline-variant/30">
      <div className="absolute left-0 top-0 bottom-0 w-1.5 bg-primary" />
      <div className="pl-lg pr-md py-md">
        <div className="flex justify-between items-start mb-xs">
          <span className="font-label-caps text-label-caps uppercase text-on-surface-variant">{leg.direction}</span>
          <span className="text-primary font-bold text-lg tabular-nums">{formatCents(leg.lineAmountCents, currency)}</span>
        </div>
        <h3 className="font-body-md text-body-md font-bold text-on-surface leading-tight mb-xs">{route}</h3>
        {when && <p className="font-body-sm text-body-sm text-on-surface-variant">{when}</p>}
        <div className="flex justify-end mt-sm">
          <button
            onClick={onRemove}
            disabled={removing}
            className="text-primary hover:underline font-label-caps text-xs disabled:opacity-50"
            aria-label={`Remove ${leg.direction.toLowerCase()} leg`}
          >
            {removing ? <Spinner className="text-sm" /> : 'Remove'}
          </button>
        </div>
      </div>
    </div>
  )
}

// Placeholder shown while the booking is still one-way: the reverse route (derived from the outbound trip) as
// a greyed slot nudging the traveller to add a return. Checkout is still available for a one-way booking.
function PendingReturnCard({ outboundCode }: { outboundCode: string }) {
  const { data: outbound } = useGetTripQuery(outboundCode)
  const route = outbound ? `${outbound.destination.city} – ${outbound.origin.city}` : 'Return trip'

  return (
    <div className="relative bg-surface-container-lowest rounded-lg shadow-sm overflow-hidden border border-outline-variant/30">
      <div className="absolute left-0 top-0 bottom-0 w-1.5 bg-outline-variant" />
      <div className="pl-lg pr-md py-md">
        <div className="flex justify-between items-start mb-xs">
          <span className="font-label-caps text-label-caps uppercase text-on-surface-variant">RETURN</span>
          <span className="text-error font-bold text-sm">To complete</span>
        </div>
        <h3 className="font-body-md text-body-md font-bold text-on-surface-variant leading-tight mb-xs">{route}</h3>
        <p className="font-body-sm text-body-sm text-on-surface-variant/60 italic">Select a train</p>
      </div>
    </div>
  )
}
