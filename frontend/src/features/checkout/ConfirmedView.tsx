import { useNavigate } from 'react-router-dom'
import Icon from '../../components/Icon'
import ConfirmedLeg from './ConfirmedLeg'
import { formatCents } from '../../lib/format'
import type { Order } from '../../types'

// Confirmation ticket, degraded to real data: the backend has no e-ticket artifact (no booking reference,
// seat/carriage, PSP ref or PDF in scope), so the reference is the order id and legs are enriched from the
// catalog.
export default function ConfirmedView({ order }: { order: Order }) {
  const navigate = useNavigate()
  const reference = order.orderId.slice(0, 8).toUpperCase()

  return (
    <div className="flex flex-col items-center justify-center py-xl max-w-2xl mx-auto text-center">
      <div className="w-24 h-24 bg-primary-container text-on-primary-container rounded-full flex items-center justify-center mb-xl shadow-xl">
        <Icon name="check_circle" filled className="text-[48px]" />
      </div>
      <h1 className="font-display-lg text-display-lg-mobile text-on-surface mb-md">Ticket Confirmed!</h1>
      <p className="font-body-md text-body-md text-on-surface-variant mb-xl">
        Your journey is secured. You can review this booking anytime under My Trips.
      </p>

      <div className="w-full bg-surface-container-lowest p-xl rounded-2xl shadow-xl border border-outline-variant/20 relative overflow-hidden mb-xl">
        <div className="absolute top-0 left-0 w-2 h-full bg-primary" />
        <div className="text-left mb-lg">
          <p className="font-label-caps text-label-caps text-on-surface-variant">BOOKING REFERENCE</p>
          <p className="font-headline-md text-headline-md tracking-widest text-primary">ET-{reference}</p>
        </div>
        <div className="flex flex-col gap-lg">
          {order.legs
            .filter((l) => l.status === 'CONFIRMED')
            .map((leg) => (
              <ConfirmedLeg key={leg.legId} leg={leg} currency={order.currency} />
            ))}
          <div className="flex justify-between items-center border-t border-outline-variant/20 pt-lg">
            <span className="font-title-lg text-title-lg">Total paid</span>
            <span className="font-display-lg text-headline-md text-primary tabular-nums">
              {formatCents(order.amountCents, order.currency)}
            </span>
          </div>
        </div>
      </div>

      <div className="flex flex-col md:flex-row gap-md w-full">
        <button
          onClick={() => window.print()}
          className="flex-1 bg-primary text-on-primary py-lg rounded-xl font-bold flex items-center justify-center gap-md shadow-lg active:scale-95 transition-all"
        >
          <Icon name="print" />
          Print confirmation
        </button>
        <button
          onClick={() => navigate('/orders')}
          className="flex-1 bg-surface-container text-on-surface-variant py-lg rounded-xl font-bold flex items-center justify-center gap-md active:scale-95 transition-all"
        >
          <Icon name="confirmation_number" />
          My bookings
        </button>
      </div>
    </div>
  )
}
