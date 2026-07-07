import { useEffect } from 'react'
import { useParams } from 'react-router-dom'
import { useAppDispatch, useAppSelector } from '../../app/hooks'
import { orderUpdated } from '../booking/bookingSlice'
import { useOrderStream } from '../booking/useOrderStream'
import { useBookingActions } from '../booking/useBookingActions'
import { useGetOrderQuery } from '../orders/ordersApi'
import { ErrorState, LoadingBlock } from '../../components/ui/QueryStates'
import SagaTracker from './SagaTracker'
import PaymentForm from './PaymentForm'
import ProcessingPanel from './ProcessingPanel'
import HoldWindowCard from './HoldWindowCard'
import TripSummary from './TripSummary'
import ConfirmedView from './ConfirmedView'
import RejectedView from './RejectedView'

// Orchestrates the checkout: hydrate the order (from the booking slice, or fetch it on a deep-link/refresh),
// observe the saga live over SSE (degrading to polling), and branch on status. Payment is PayPal Card Fields —
// the card is entered in PayPal's iframe; the browser only ever forwards the approved order id to `pay`.
export default function CheckoutPage() {
  const { orderId = '' } = useParams()
  const dispatch = useAppDispatch()
  const sliceOrder = useAppSelector((s) => s.booking.order)
  const { payNow, paying } = useBookingActions()

  const hasSliceOrder = sliceOrder?.orderId === orderId
  const { data: fetched, isLoading, isError, error, refetch } = useGetOrderQuery(orderId, { skip: hasSliceOrder })

  useEffect(() => {
    if (fetched && sliceOrder?.orderId !== fetched.orderId) dispatch(orderUpdated(fetched))
  }, [fetched, sliceOrder, dispatch])

  const order = hasSliceOrder ? sliceOrder : fetched
  useOrderStream(order?.orderId ?? null, order?.status)

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

  return (
    <main className="flex-grow w-full max-w-max-width mx-auto px-margin-mobile md:px-margin-desktop py-xl">
      <div className="grid grid-cols-12 gap-lg">
        <aside className="col-span-12 lg:col-span-3 order-last lg:order-none">
          <SagaTracker status={order.status} />
        </aside>
        <section className="col-span-12 lg:col-span-6">
          {payable ? (
            <PaymentForm order={order} onPay={(payPalOrderId) => payNow(order.orderId, payPalOrderId)} processing={paying} />
          ) : (
            <ProcessingPanel status={order.status} />
          )}
        </section>
        <aside className="col-span-12 lg:col-span-3">
          <div className="sticky top-24 flex flex-col gap-lg">
            <HoldWindowCard expiresAt={order.expiresAt} remainingSeconds={order.remainingSeconds} />
            <TripSummary order={order} />
          </div>
        </aside>
      </div>
    </main>
  )
}
