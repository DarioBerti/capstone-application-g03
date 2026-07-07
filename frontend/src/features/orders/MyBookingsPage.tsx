import { useState } from 'react'
import { Link } from 'react-router-dom'
import Icon from '../../components/Icon'
import SignInRequired from '../../components/ui/SignInRequired'
import DatePicker from '../../components/ui/DatePicker'
import { EmptyState, ErrorState, LoadingBlock } from '../../components/ui/QueryStates'
import Pager from '../../components/ui/Pager'
import OrderStatusBadge from './OrderStatusBadge'
import { useListMyOrdersQuery } from './ordersApi'
import { useGetTripQuery } from '../catalog/catalogApi'
import { isAuthenticated } from '../../auth'
import { formatCents, formatDate, formatTime } from '../../lib/format'
import type { Order, OrderLeg } from '../../types'

const PAGE_SIZE = 20

const filterField =
  'w-full h-12 pl-[52px] pr-sm bg-surface-container-low border-2 border-transparent focus:border-primary ' +
  'focus:outline-none focus:ring-0 rounded-lg font-body-md text-body-md transition-all'

export default function MyBookingsPage() {
  const authed = isAuthenticated()
  const [page, setPage] = useState(0)
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')

  const { data, isLoading, isError, error, refetch } = useListMyOrdersQuery(
    {
      status: 'CONFIRMED',
      page,
      size: PAGE_SIZE,
      createdFrom: fromDate || undefined,
      createdTo: toDate || undefined,
    },
    { skip: !authed },
  )

  if (!authed) return <SignInRequired message="Sign in to view your bookings and trip history." />

  // Pre-rollout backends ignore the status param; keep the page honest client-side too.
  const confirmed = (data?.content ?? []).filter((o) => o.status === 'CONFIRMED')
  const totalPages = data?.totalPages ?? 0
  const filtered = Boolean(fromDate || toDate)

  const changeFilter = (which: 'from' | 'to', value: string) => {
    if (which === 'from') setFromDate(value)
    else setToDate(value)
    setPage(0)
  }
  const clearFilter = () => {
    setFromDate('')
    setToDate('')
    setPage(0)
  }

  return (
    <main className="flex-grow w-full max-w-max-width mx-auto px-margin-mobile md:px-margin-desktop py-xl">
      <div className="flex flex-col md:flex-row md:items-end justify-between gap-md mb-lg">
        <div className="space-y-xs">
          <h1 className="font-display-lg text-display-lg-mobile md:text-display-lg text-on-background">My trips</h1>
          <p className="font-body-md text-body-md text-on-surface-variant">
            Your confirmed journeys, most recent first.
          </p>
        </div>
        <div className="flex flex-col sm:flex-row sm:items-end gap-sm">
          <div className="w-full sm:w-44">
            <label className="font-label-caps text-label-caps text-on-surface-variant block ml-xs mb-xs" htmlFor="booked-from">
              BOOKED FROM
            </label>
            <DatePicker id="booked-from" value={fromDate} onChange={(v) => changeFilter('from', v)} placeholder="Any date" className={filterField} />
          </div>
          <div className="w-full sm:w-44">
            <label className="font-label-caps text-label-caps text-on-surface-variant block ml-xs mb-xs" htmlFor="booked-to">
              BOOKED TO
            </label>
            <DatePicker id="booked-to" value={toDate} onChange={(v) => changeFilter('to', v)} placeholder="Any date" min={fromDate || undefined} icon="event" className={filterField} />
          </div>
          {filtered && (
            <button
              onClick={clearFilter}
              className="h-12 px-md rounded-lg text-on-surface-variant hover:text-primary hover:bg-surface-container transition-colors font-body-sm text-body-sm self-start sm:self-auto"
            >
              Clear
            </button>
          )}
        </div>
      </div>

      {isLoading && <LoadingBlock label="Loading your trips…" />}
      {isError && <ErrorState error={error} onRetry={refetch} />}
      {!isLoading && !isError && confirmed.length === 0 && (
        <EmptyState
          icon="luggage"
          title={filtered ? 'No trips in this period' : 'No confirmed trips yet'}
          hint={
            filtered ? (
              'Try widening the date range.'
            ) : (
              <>
                When a booking is confirmed it appears here.{' '}
                <Link to="/search" className="text-primary font-bold hover:underline">
                  Find a train
                </Link>
                .
              </>
            )
          }
        />
      )}

      <div className="grid grid-cols-1 gap-md">
        {confirmed.map((order) => (
          <BookingCard key={order.orderId} order={order} />
        ))}
      </div>

      <Pager page={page} totalPages={totalPages} onPage={setPage} />
    </main>
  )
}

function BookingCard({ order }: { order: Order }) {
  const legs = order.legs.filter((l) => l.status !== 'RELEASED')
  return (
    <Link
      to={`/orders/${order.orderId}`}
      className="group bg-surface-container-lowest border border-outline-variant rounded-xl p-lg shadow-sm hover:shadow-lg hover:-translate-y-1 transition-all flex items-center justify-between gap-md sm:gap-lg"
    >
      <div className="flex items-center gap-md min-w-0">
        <div className="w-12 h-12 bg-surface-container hidden sm:flex items-center justify-center rounded-lg shrink-0">
          <Icon name="train" filled className="text-primary" />
        </div>
        <div className="min-w-0 space-y-xs">
          {legs.length === 0 && <p className="font-title-lg text-title-lg text-on-surface">Empty booking</p>}
          {legs.map((leg) => (
            <LegRouteLine key={leg.legId} leg={leg} currency={order.currency} />
          ))}
          <p className="font-body-sm text-body-sm text-on-surface-variant">
            ET-{order.orderId.slice(0, 8).toUpperCase()}
            {order.createdAt ? ` · Booked ${formatDate(order.createdAt)}` : ''} · {legs.length}{' '}
            {legs.length === 1 ? 'leg' : 'legs'}
          </p>
        </div>
      </div>
      <div className="flex items-center gap-sm sm:gap-lg shrink-0">
        <OrderStatusBadge status={order.status} />
        <span className="font-label-tabular text-label-tabular tabular-nums hidden sm:inline">
          {formatCents(order.amountCents, order.currency)}
        </span>
        <Icon name="chevron_right" className="text-on-surface-variant group-hover:text-primary" />
      </div>
    </Link>
  )
}

// Resolves a leg's tripCode to its route via the cached catalog read (RTK Query dedupes identical codes
// across cards); falls back to the raw code while loading or when the trip has left the catalog.
function LegRouteLine({ leg, currency }: { leg: OrderLeg; currency: string }) {
  const { data: trip } = useGetTripQuery(leg.tripCode)
  return (
    <p className="flex flex-wrap items-center gap-xs min-w-0 font-title-lg text-title-lg text-on-surface">
      <Icon
        name={leg.direction === 'RETURN' ? 'u_turn_left' : 'arrow_right_alt'}
        className="text-on-surface-variant shrink-0"
      />
      {trip ? (
        <>
          <span className="truncate">{trip.origin.name}</span>
          <Icon name="trending_flat" className="text-primary shrink-0" />
          <span className="truncate">{trip.destination.name}</span>
          <span className="font-body-sm text-body-sm text-on-surface-variant whitespace-nowrap shrink-0">
            {formatDate(trip.departureTime)} · {formatTime(trip.departureTime)} · {leg.qty} ×{' '}
            {formatCents(leg.unitPriceCents, currency)}
          </span>
        </>
      ) : (
        <span className="truncate">
          {leg.tripCode}
          <span className="font-body-sm text-body-sm text-on-surface-variant"> · {leg.qty} × {formatCents(leg.unitPriceCents, currency)}</span>
        </span>
      )}
    </p>
  )
}
