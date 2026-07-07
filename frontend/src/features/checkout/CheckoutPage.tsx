import { useEffect } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useAppDispatch, useAppSelector } from '../../app/hooks'
import { orderUpdated } from '../booking/bookingSlice'
import { ACTIVE_LEG_STATUSES } from '../booking/legStatus'
import { useOrderStream } from '../booking/useOrderStream'
import { useBookingActions } from '../booking/useBookingActions'
import DiscardBooking from '../booking/DiscardBooking'
import { useGetTripQuery } from '../catalog/catalogApi'
import { useGetOrderQuery } from '../orders/ordersApi'
import { ErrorState, LoadingBlock } from '../../components/ui/QueryStates'
import SagaTracker from './SagaTracker'
import PaymentForm from './PaymentForm'
import ProcessingPanel from './ProcessingPanel'
import HoldWindowCard from './HoldWindowCard'
import TripSummary from './TripSummary'
import ConfirmedView from './ConfirmedView'
import RejectedView from './RejectedView'
import type { OrderLeg } from '../../types'

// Orchestrates the checkout: hydrate the order (from the booking slice, or fetch it on a deep-link/refresh),
// observe the saga live over SSE (degrading to polling), and branch on status. Payment is PayPal Card Fields —
// the card is entered in PayPal's iframe; the browser only ever forwards the approved order id to `pay`. While
// the hold is open the user can Change a leg (drop it → back to /search to re-pick) or discard the whole
// booking; once paid it's read-only.
export default function CheckoutPage() {
  const { orderId = '' } = useParams()
  const dispatch = useAppDispatch()
  const navigate = useNavigate()
  const sliceOrder = useAppSelector((s) => s.booking.order)
  const { payNow, paying, changeLeg, discardBooking, removing } = useBookingActions()

  const hasSliceOrder = sliceOrder?.orderId === orderId
  const { data: fetched, isLoading, isError, error, refetch } = useGetOrderQuery(orderId, { skip: hasSliceOrder })

  useEffect(() => {
    if (fetched && sliceOrder?.orderId !== fetched.orderId) dispatch(orderUpdated(fetched))
  }, [fetched, sliceOrder, dispatch])

  const order = hasSliceOrder ? sliceOrder : fetched
  useOrderStream(order?.orderId ?? null, order?.status)

  // Resolve the outbound trip so "Change outbound" can re-scope the search to the same route/date. Public +
  // cached; skipped until we actually have an outbound leg.
  const outboundLeg = order?.legs.find((l) => l.direction === 'OUTBOUND' && ACTIVE_LEG_STATUSES.has(l.status))
  const { data: outboundTrip } = useGetTripQuery(outboundLeg?.tripCode ?? '', { skip: !outboundLeg?.tripCode })

  if (isLoading) {
    return (
      <main className="flex-grow max-w-max-width mx-auto px-margin-mobile md:px-margin-desktop w-full py-xl">
        <LoadingBlock label="Loading your booking…" />
      </main>
    )
  }
  if (isError || !order) {
    return (
      <main className="flex-grow max-w-max-width mx-auto px-margin-mobile md:px-margin-desktop w-full py-xl">
        <ErrorState error={error} onRetry={refetch} />
      </main>
    )
  }

  if (order.status === 'CONFIRMED') {
    return (
      <main className="flex-grow max-w-max-width mx-auto px-margin-mobile md:px-margin-desktop w-full py-xl">
        <ConfirmedView order={order} />
      </main>
    )
  }
  if (order.status === 'REJECTED') {
    return (
      <main className="flex-grow max-w-max-width mx-auto px-margin-mobile md:px-margin-desktop w-full py-xl">
        <RejectedView order={order} />
      </main>
    )
  }

  const payable = order.status === 'HELD'

  // Changing the RETURN keeps the still-held outbound, so /search resumes the "pick your return" flow. Changing
  // the OUTBOUND clears the booking, so scope the fresh search to the same route/day/pax when the trip is known.
  const searchTargetFor = (leg: OrderLeg): string => {
    if (leg.direction === 'RETURN' || !outboundTrip) return '/search'
    const params = new URLSearchParams({
      origin: outboundTrip.origin.code,
      destination: outboundTrip.destination.code,
      serviceDate: outboundTrip.serviceDate,
      pax: String(leg.qty),
    })
    return `/search?${params.toString()}`
  }

  const onChangeLeg = async (leg: OrderLeg) => {
    const target = searchTargetFor(leg)
    await changeLeg(order, leg)
    navigate(target)
  }

  return (
    <main className="flex-grow w-full max-w-max-width mx-auto px-margin-mobile md:px-margin-desktop py-xl">
      <div className="grid grid-cols-12 gap-lg">
        <aside className="col-span-12 lg:col-span-3 order-last lg:order-none">
          <SagaTracker status={order.status} />
        </aside>
        <section className="col-span-12 lg:col-span-6">
          {payable ? (
            <div className="flex flex-col gap-md">
              <PaymentForm order={order} onPay={(payPalOrderId) => payNow(order.orderId, payPalOrderId)} processing={paying} />
              <DiscardBooking
                onConfirm={async () => {
                  await discardBooking(order)
                  navigate('/')
                }}
                removing={removing}
                resetKey={order.orderId}
              />
            </div>
          ) : (
            <ProcessingPanel status={order.status} />
          )}
        </section>
        <aside className="col-span-12 lg:col-span-3">
          <div className="sticky top-24 flex flex-col gap-lg">
            <HoldWindowCard expiresAt={order.expiresAt} remainingSeconds={order.remainingSeconds} />
            <TripSummary order={order} onChangeLeg={payable ? onChangeLeg : undefined} changing={removing} />
          </div>
        </aside>
      </div>
    </main>
  )
}
