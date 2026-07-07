import { useState } from 'react'
import { Link } from 'react-router-dom'
import Icon from '../../components/Icon'
import SignInRequired from '../../components/ui/SignInRequired'
import DatePicker from '../../components/ui/DatePicker'
import { EmptyState, ErrorState, LoadingBlock } from '../../components/ui/QueryStates'
import Pager from '../../components/ui/Pager'
import { useListMyOrdersQuery } from './ordersApi'
import { useGetTripQuery } from '../catalog/catalogApi'
import { isAuthenticated } from '../../auth'
import { formatCents, formatDate, formatDuration, formatTime, formatWeekday } from '../../lib/format'
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

      <div className="grid grid-cols-1 gap-lg">
        {confirmed.map((order) => (
          <BookingCard key={order.orderId} order={order} />
        ))}
      </div>

      <Pager page={page} totalPages={totalPages} onPage={setPage} />
    </main>
  )
}

const LEG_ORDER: Record<OrderLeg['direction'], number> = { OUTBOUND: 0, RETURN: 1 }

function BookingCard({ order }: { order: Order }) {
  // Outbound always before return. The backend now orders legs by createdAt, but sorting here keeps the display
  // deterministic regardless of the array order the API happens to return.
  const legs = order.legs
    .filter((l) => l.status !== 'RELEASED')
    .slice()
    .sort((a, b) => LEG_ORDER[a.direction] - LEG_ORDER[b.direction])
  const outbound = legs.find((l) => l.direction === 'OUTBOUND') ?? legs[0]
  const hasReturn = legs.some((l) => l.direction === 'RETURN')
  const bookingCode = `ET-${order.orderId.slice(0, 8).toUpperCase()}`

  return (
    <div className="bg-surface-container-lowest border border-outline-variant rounded-xl overflow-hidden shadow-sm hover:shadow-lg transition-shadow">
      <div className="p-md md:p-lg border-b border-outline-variant/60">
        <p className="font-body-sm text-body-sm text-on-surface-variant mb-xs">
          {hasReturn ? 'Round trip' : 'One way'}
          {order.createdAt ? ` · Booked ${formatDate(order.createdAt)}` : ''}
        </p>
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-sm md:gap-md">
          {outbound ? (
            <RouteHeader leg={outbound} hasReturn={hasReturn} />
          ) : (
            <p className="font-headline-md text-headline-md font-bold text-on-surface">Empty booking</p>
          )}
          <CopyCodeButton code={bookingCode} />
        </div>
      </div>

      <div className="flex flex-col">
        {legs.map((leg) => (
          <LegRow key={leg.legId} leg={leg} currency={order.currency} />
        ))}
      </div>

      <div className="p-md md:px-lg md:py-md flex justify-between items-center gap-md">
        <div className="font-headline-md text-headline-md text-on-surface">
          <span className="font-normal text-on-surface-variant mr-xs">Total</span>
          <span className="font-bold tabular-nums">{formatCents(order.amountCents, order.currency)}</span>
        </div>
        <Link
          to={`/orders/${order.orderId}`}
          className="bg-primary text-on-primary font-title-lg text-body-md font-bold px-lg py-sm rounded-lg hover:brightness-110 active:scale-95 transition-all shadow-sm"
        >
          View trip
        </Link>
      </div>
    </div>
  )
}

// The card's origin ⇄ destination headline, resolved from the outbound leg's trip (RTK Query dedupes the
// code with the leg rows below); falls back to the raw code until the catalog read lands.
function RouteHeader({ leg, hasReturn }: { leg: OrderLeg; hasReturn: boolean }) {
  const { data: trip } = useGetTripQuery(leg.tripCode)
  return (
    <div className="flex items-center flex-wrap gap-sm font-headline-md text-headline-md font-bold text-on-surface min-w-0">
      {trip ? (
        <>
          <span className="truncate">{trip.origin.name}</span>
          <Icon name={hasReturn ? 'sync_alt' : 'arrow_right_alt'} className="text-primary shrink-0" />
          <span className="truncate">{trip.destination.name}</span>
        </>
      ) : (
        <span className="truncate">{leg.tripCode}</span>
      )}
    </div>
  )
}

function CopyCodeButton({ code }: { code: string }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard?.writeText(code)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // Clipboard unavailable (insecure context / denied) — the code stays visible to copy manually.
    }
  }
  return (
    <div className="flex items-center gap-xs font-body-md text-body-md shrink-0">
      <span className="text-on-surface-variant">Booking code</span>
      <span className="font-label-tabular text-label-tabular tabular-nums font-bold text-primary ml-xs">{code}</span>
      <button
        type="button"
        onClick={copy}
        aria-label={copied ? 'Booking code copied' : 'Copy booking code'}
        className="text-primary hover:bg-surface-container-high p-xs rounded transition-colors flex items-center justify-center"
      >
        <Icon name={copied ? 'check' : 'content_copy'} className="text-title-lg" />
      </button>
    </div>
  )
}

// One outbound/return leg as a ticket row. Resolves the leg's tripCode to its schedule via the cached catalog
// read (RTK Query dedupes identical codes across cards); degrades to the raw code when the trip has left the
// catalog. Carriage/seat/class are intentionally omitted — the orders domain doesn't own them.
function LegRow({ leg, currency }: { leg: OrderLeg; currency: string }) {
  const { data: trip } = useGetTripQuery(leg.tripCode)
  return (
    <div className="p-md md:p-lg border-b border-outline-variant/40 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-md md:gap-lg items-start">
      <div className="flex items-start gap-sm min-w-0">
        <Icon name="train" filled className="text-primary mt-xs shrink-0" />
        <div className="font-title-lg text-title-lg font-bold text-on-surface leading-tight min-w-0">
          {trip ? (
            <>
              <div>{formatWeekday(trip.departureTime)},</div>
              <div className="tabular-nums">{formatDate(trip.departureTime)}</div>
            </>
          ) : (
            <div className="truncate">{leg.tripCode}</div>
          )}
          <div className="font-label-caps text-label-caps text-on-surface-variant mt-xs">
            {leg.direction === 'RETURN' ? 'RETURN' : 'OUTBOUND'}
          </div>
        </div>
      </div>

      <div className="space-y-xs font-body-md text-body-md text-on-surface min-w-0">
        {trip ? (
          <>
            <div className="truncate">
              <span className="text-on-surface-variant">Departure</span>{' '}
              <span className="font-bold tabular-nums">{formatTime(trip.departureTime)}</span>{' '}
              <span className="text-on-surface-variant">from</span> <span className="font-bold">{trip.origin.name}</span>
            </div>
            <div className="truncate">
              <span className="text-on-surface-variant">Arrival</span>{' '}
              <span className="font-bold tabular-nums">{formatTime(trip.arrivalTime)}</span>{' '}
              <span className="text-on-surface-variant">to</span> <span className="font-bold">{trip.destination.name}</span>
            </div>
          </>
        ) : (
          <div className="text-on-surface-variant">Schedule unavailable</div>
        )}
      </div>

      <div className="space-y-xs font-body-md text-body-md text-on-surface">
        <div>
          <span className="text-on-surface-variant">Train</span>{' '}
          <span className="font-bold">{trip ? `${trip.carrierCode} ${trip.serviceNumber}` : leg.tripCode}</span>
        </div>
        {trip && (
          <div>
            <span className="text-on-surface-variant">Duration</span>{' '}
            <span className="font-bold tabular-nums">{formatDuration(trip.departureTime, trip.arrivalTime)}</span>
          </div>
        )}
      </div>

      <div className="space-y-xs font-body-md text-body-md text-on-surface">
        <div>
          <span className="text-on-surface-variant">Passengers</span>{' '}
          <span className="font-bold tabular-nums">{leg.qty}</span>
        </div>
        <div>
          <span className="text-on-surface-variant">Fare</span>{' '}
          <span className="font-bold tabular-nums">{formatCents(leg.lineAmountCents, currency)}</span>
        </div>
      </div>
    </div>
  )
}
