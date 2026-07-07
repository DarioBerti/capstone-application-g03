import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import Icon from '../../components/Icon'
import { ErrorState, LoadingBlock, EmptyState } from '../../components/ui/QueryStates'
import Pager from '../../components/ui/Pager'
import SortTabs, { type SortKey } from './SortTabs'
import TripCard from './TripCard'
import DateStrip from './DateStrip'
import { returnQueryFor } from './returnSearch'
import BookingSummary from '../booking/BookingSummary'
import { useBookingActions } from '../booking/useBookingActions'
import { useAppSelector } from '../../app/hooks'
import { useGetTripQuery, useListAllStationsQuery, useListTripsQuery, type TripQuery } from '../catalog/catalogApi'
import { todayIso } from '../../lib/format'
import { ACTIVE_LEG_STATUSES } from '../booking/legStatus'
import type { Availability, Trip } from '../../types'

function durationMs(t: Trip) {
  return new Date(t.arrivalTime).getTime() - new Date(t.departureTime).getTime()
}

// Client-side sort over the current page (the "Fastest" key isn't a sortable DB column).
function sortTrips(trips: Trip[], key: SortKey): Trip[] {
  const copy = [...trips]
  if (key === 'cheapest') return copy.sort((a, b) => a.priceCents - b.priceCents)
  return copy.sort((a, b) => durationMs(a) - durationMs(b))
}

// Seat badge input, sourced from Catalog's availability read-model on the trip itself (sold-out trips are
// already filtered out server-side). Unknown (null) availability => no badge.
export function availabilityOf(trip: Trip): Availability | undefined {
  return trip.availableSeats != null
    ? { tripCode: trip.code, available: trip.availableSeats, totalSeats: trip.availableSeats }
    : undefined
}

export default function SearchPage() {
  const [params, setSearchParams] = useSearchParams()
  const origin = params.get('origin') ?? undefined
  const destination = params.get('destination') ?? undefined
  const serviceDate = params.get('serviceDate') ?? undefined
  const pax = Math.max(1, Number(params.get('pax') ?? '1'))

  // The outbound is always a single day's search (the strip needs a selected day); default to today when
  // the URL carries no date. Picking a day rewrites just that param (shareable, refresh-safe).
  const today = todayIso()
  const outboundDate = serviceDate ?? today
  const onServiceDate = (day: string) => {
    const next = new URLSearchParams(params)
    next.set('serviceDate', day)
    setSearchParams(next)
  }

  const [sort, setSort] = useState<SortKey>('cheapest')

  const { startBooking, addReturn, dropLeg, starting, removing } = useBookingActions()

  // Active in-progress booking (DRAFT/HELD) drives the round-trip flow: once an outbound leg is held, the
  // result cards offer "Add return" for the reverse trip instead of starting a new booking.
  const order = useAppSelector((s) => s.booking.order)
  const activeOrder = order && (order.status === 'DRAFT' || order.status === 'HELD') ? order : null
  const activeLegs = activeOrder?.legs.filter((l) => ACTIVE_LEG_STATUSES.has(l.status)) ?? []
  const outboundLeg = activeLegs.find((l) => l.direction === 'OUTBOUND')
  const outboundCode = outboundLeg?.tripCode
  const hasReturn = activeLegs.some((l) => l.direction === 'RETURN')
  const awaitingReturn = !!outboundCode && !hasReturn

  // Undo the outbound choice: dropping its leg releases the held seats and flips the page back to outbound
  // selection (the reverse-route search + return date fall away with awaitingReturn).
  const changeOutbound = () => {
    if (activeOrder && outboundLeg) dropLeg(activeOrder.orderId, outboundLeg.legId)
  }

  // Resolve the held outbound trip so we can scope the return search to the reverse route (the leg only carries
  // its tripCode). Public + cached, so this survives reloads.
  const { data: outboundTrip } = useGetTripQuery(outboundCode ?? '', { skip: !outboundCode })

  // The return leg gets its own date, independent of the outbound. Reset it whenever the outbound changes
  // so a new selection starts a fresh return search.
  const [returnDate, setReturnDate] = useState<string | undefined>(undefined)
  useEffect(() => {
    setReturnDate(undefined)
  }, [outboundCode])

  // While awaiting the return we search the reverse route (its own date); otherwise the URL-driven outbound
  // search. Skip the fetch during the brief window where the outbound trip is still resolving, so we never
  // flash the outbound-direction list.
  const resolvingReturn = awaitingReturn && !outboundTrip
  const baseQuery: TripQuery =
    awaitingReturn && outboundTrip
      ? returnQueryFor(outboundTrip, { date: returnDate })
      : { origin, destination, serviceDate: outboundDate }
  // The page index is bound to the search it was set for: a different search (route, day, or
  // outbound↔return flip) reads as page 0 without an effect, so the stale page never even fetches.
  const queryKey = JSON.stringify(baseQuery)
  const [paging, setPaging] = useState({ key: queryKey, index: 0 })
  const pageIndex = paging.key === queryKey ? paging.index : 0
  const setPageIndex = (index: number) => setPaging({ key: queryKey, index })
  const query: TripQuery = { ...baseQuery, page: pageIndex }
  const { data: page, isLoading, isError, error, refetch } = useListTripsQuery(query, { skip: resolvingReturn })

  // Per-card action: normally "Select" (start outbound); once an outbound is held, the trips become
  // "Add return"; the outbound trip itself and any card while the booking is already full are disabled.
  const cardAction = (trip: Trip): { label: string; disabled: boolean } => {
    if (outboundCode === trip.code) return { label: 'Selected', disabled: true }
    if (hasReturn) return { label: 'In booking', disabled: true }
    if (awaitingReturn) return { label: 'Add return', disabled: false }
    return { label: 'Select', disabled: false }
  }

  const [pendingTrip, setPendingTrip] = useState<string | null>(null)
  const onAction = async (trip: Trip) => {
    setPendingTrip(trip.code)
    try {
      if (awaitingReturn && activeOrder) {
        await addReturn(activeOrder.orderId, trip.code, pax, trip.priceToken)
      } else {
        await startBooking(trip.code, pax, trip.priceToken)
      }
    } finally {
      setPendingTrip(null)
    }
  }

  // Sold-out trips are filtered out server-side by Catalog's availability read-model, so here we only sort
  // the current page.
  const trips = useMemo(() => sortTrips(page?.content ?? [], sort), [page, sort])
  // The URL params carry station CODES; resolve them to display names off the cached station list
  // (falling back to the raw code only for the brief first load).
  const { data: allStations = [] } = useListAllStationsQuery()
  const stationName = useMemo(() => {
    const byCode = new Map(allStations.map((s) => [s.code, s.name]))
    return (code?: string) => (code ? (byCode.get(code) ?? code) : undefined)
  }, [allStations])

  const from = awaitingReturn && outboundTrip ? outboundTrip.destination.city : stationName(origin)
  const to = awaitingReturn && outboundTrip ? outboundTrip.origin.city : stationName(destination)
  const routeLabel = from && to ? `${from} → ${to}` : 'All journeys'
  const showLoading = isLoading || resolvingReturn

  return (
    <main className="flex-grow max-w-max-width mx-auto px-margin-mobile md:px-margin-desktop w-full py-lg">
      <div className="mb-lg flex flex-col md:flex-row md:items-end justify-between gap-md">
        <div>
          {awaitingReturn && (
            <button
              onClick={changeOutbound}
              disabled={removing}
              className="flex items-center gap-xs mb-xs font-label-caps text-label-caps text-on-surface-variant hover:text-primary transition-colors disabled:opacity-50"
            >
              <Icon name="arrow_back" className="text-base" />
              Change outbound
            </button>
          )}
          <h1 className="font-display-lg text-display-lg-mobile md:text-display-lg text-on-background mb-xs">
            {awaitingReturn ? 'Pick your return' : 'Pick your outbound'}
          </h1>
          <div className="flex flex-wrap items-center gap-sm">
            <span className="font-title-lg text-title-lg text-primary">{from ?? 'Anywhere'}</span>
            <Icon name="arrow_forward" className="text-outline" />
            <span className="font-title-lg text-title-lg text-primary">{to ?? 'Anywhere'}</span>
          </div>
        </div>
        <SortTabs value={sort} onChange={setSort} />
      </div>

      <div className="grid grid-cols-12 gap-lg">
        <div className="col-span-12 lg:col-span-8 space-y-md">
          {!awaitingReturn && (
            <DateStrip
              value={outboundDate}
              onChange={onServiceDate}
              minDate={today}
              priceQuery={{ origin, destination }}
            />
          )}
          {awaitingReturn && outboundTrip && (
            <DateStrip
              value={returnDate ?? outboundTrip.serviceDate}
              onChange={setReturnDate}
              minDate={outboundTrip.serviceDate}
              priceQuery={{ origin: outboundTrip.destination.code, destination: outboundTrip.origin.code }}
            />
          )}
          {showLoading && <LoadingBlock label="Finding journeys…" />}
          {isError && <ErrorState error={error} onRetry={refetch} />}
          {!showLoading && !isError && trips.length === 0 && (
            <EmptyState icon="search_off" title="No journeys found" hint={`Try a different date or route for ${routeLabel}.`} />
          )}
          {trips.map((trip) => {
            const action = cardAction(trip)
            return (
              <TripCard
                key={trip.id}
                trip={trip}
                availability={availabilityOf(trip)}
                onAction={onAction}
                actionLabel={action.label}
                actionDisabled={action.disabled}
                selecting={starting && pendingTrip === trip.code}
              />
            )
          })}
          {!showLoading && !isError && <Pager page={pageIndex} totalPages={page?.totalPages ?? 0} onPage={setPageIndex} />}
        </div>

        <aside className={`col-span-12 lg:col-span-4 ${activeOrder ? 'order-first lg:order-none' : ''}`}>
          <div className="sticky top-24 space-y-md">
            <BookingSummary />
          </div>
        </aside>
      </div>
    </main>
  )
}
