import { Link } from 'react-router-dom'
import Icon from '../../components/Icon'
import SignInRequired from '../../components/ui/SignInRequired'
import { EmptyState, ErrorState, LoadingBlock } from '../../components/ui/QueryStates'
import OrderStatusBadge from './OrderStatusBadge'
import { useListMyOrdersQuery } from './ordersApi'
import { isAuthenticated } from '../../auth'
import { formatCents } from '../../lib/format'
import type { Order } from '../../types'

export default function MyBookingsPage() {
  const authed = isAuthenticated()
  const { data: page, isLoading, isError, error, refetch } = useListMyOrdersQuery(undefined, { skip: !authed })

  if (!authed) return <SignInRequired message="Sign in to view your bookings and trip history." />

  return (
    <main className="flex-grow w-full max-w-max-width mx-auto px-margin-mobile md:px-margin-desktop py-xl">
      <h1 className="font-display-lg text-display-lg-mobile mb-lg">My bookings</h1>

      {isLoading && <LoadingBlock label="Loading your bookings…" />}
      {isError && <ErrorState error={error} onRetry={refetch} />}
      {!isLoading && !isError && (page?.content.length ?? 0) === 0 && (
        <EmptyState
          icon="luggage"
          title="No bookings yet"
          hint={
            <>
              When you book a journey it appears here.{' '}
              <Link to="/search" className="text-primary font-bold hover:underline">
                Find a train
              </Link>
              .
            </>
          }
        />
      )}

      <div className="grid grid-cols-1 gap-md">
        {page?.content.map((order) => <BookingRow key={order.orderId} order={order} />)}
      </div>
    </main>
  )
}

function BookingRow({ order }: { order: Order }) {
  const legs = order.legs.filter((l) => l.status !== 'RELEASED')
  return (
    <Link
      to={`/orders/${order.orderId}`}
      className="group bg-surface-container-lowest border border-outline-variant rounded-xl p-lg shadow-sm hover:-translate-y-1 transition-all flex items-center justify-between gap-lg"
    >
      <div className="flex items-center gap-md min-w-0">
        <div className="w-12 h-12 bg-surface-container flex items-center justify-center rounded-lg shrink-0">
          <Icon name="train" filled className="text-primary" />
        </div>
        <div className="min-w-0">
          <p className="font-title-lg text-title-lg truncate">
            {legs.map((l) => l.tripCode).join(' · ') || 'Empty booking'}
          </p>
          <p className="font-body-sm text-body-sm text-on-surface-variant">
            ET-{order.orderId.slice(0, 8).toUpperCase()} · {legs.length} {legs.length === 1 ? 'leg' : 'legs'}
          </p>
        </div>
      </div>
      <div className="flex items-center gap-lg shrink-0">
        <OrderStatusBadge status={order.status} />
        <span className="font-label-tabular text-label-tabular tabular-nums hidden sm:inline">
          {formatCents(order.amountCents, order.currency)}
        </span>
        <Icon name="chevron_right" className="text-on-surface-variant group-hover:text-primary" />
      </div>
    </Link>
  )
}
