import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import Icon from '../../components/Icon'
import { ErrorState, LoadingBlock, EmptyState } from '../../components/ui/QueryStates'
import SortTabs, { type SortKey } from './SortTabs'
import TripCard from './TripCard'
import ReturnSearchBar from './ReturnSearchBar'
import { filterByTimeOfDay, returnQueryFor, type TimeOfDay } from './returnSearch'
import BookingSummary from '../booking/BookingSummary'
import { useBookingActions } from '../booking/useBookingActions'
import { useAppSelector } from '../../app/hooks'
import { useGetTripQuery, useListAllStationsQuery, useListTripsQuery, type TripQuery } from '../catalog/catalogApi'
import { useListAvailabilityQuery } from '../inventory/inventoryApi'
import type { Availability, Trip } from '../../types'

const ACTIVE_LEG = new Set(['PENDING', 'RESERVED', 'CONFIRMED'])

function durationMs(t: Trip) {
  return new Date(t.arrivalTime).getTime() - new Date(t.departureTime).getTime()
}

// Client-side sort over the current page (the "Fastest"/"Best" keys aren't sortable DB columns).
function sortTrips(trips: Trip[], key: SortKey): Trip[] {
  const copy = [...trips]
  if (key === 'cheapest') return copy.sort((a, b) => a.priceCents - b.priceCents)
  if (key === 'fastest') return copy.sort((a, b) => durationMs(a) - durationMs(b))
  // "Best" = a light blend of price and speed (both normalized), a reasonable default.
  const maxP = Math.max(...copy.map((t) => t.priceCents), 1)
  const maxD = Math.max(...copy.map(durationMs), 1)
  return copy.sort((a, b) => a.priceCents / maxP + durationMs(a) / maxD - (b.priceCents / maxP + durationMs(b) / maxD))
}

export default function SearchPage() {
  const [params] = useSearchParams()
  const origin = params.get('origin') ?? undefined
  const destination = params.get('destination') ?? undefined
  const serviceDate = params.get('serviceDate') ?? undefined
  const pax = Math.max(1, Number(params.get('pax') ?? '1'))

  const [sort, setSort] = useState<SortKey>('cheapest')

  const { startBooking, addReturn, starting } = useBookingActions()

  // Active in-progress booking (DRAFT/HELD) drives the round-trip flow: once an outbound leg is held, the
  // result cards offer "Add return" for the reverse trip instead of starting a new booking.
  const order = useAppSelector((s) => s.booking.order)
  const activeOrder = order && (order.status === 'DRAFT' || order.status === 'HELD') ? order : null
  const activeLegs = activeOrder?.legs.filter((l) => ACTIVE_LEG.has(l.status)) ?? []
  const outboundCode = activeLegs.find((l) => l.direction === 'OUTBOUND')?.tripCode
  const hasReturn = activeLegs.some((l) => l.direction === 'RETURN')
  const awaitingReturn = !!outboundCode && !hasReturn

  // Resolve the held outbound trip so we can scope the return search to the reverse route (the leg only carries
  // its tripCode). Public + cached, so this survives reloads.
  const { data: outboundTrip } = useGetTripQuery(outboundCode ?? '', { skip: !outboundCode })

  // The return leg gets its own date + time-of-day, independent of the outbound. Reset both whenever the
  // outbound changes so a new selection starts a fresh return search.
  const [returnDate, setReturnDate] = useState<string | undefined>(undefined)
  const [timeOfDay, setTimeOfDay] = useState<TimeOfDay>('any')
  useEffect(() => {
    setReturnDate(undefined)
    setTimeOfDay('any')
  }, [outboundCode])

  // While awaiting the return we search the reverse route (its own date); otherwise the URL-driven outbound
  // search. Skip the fetch during the brief window where the outbound trip is still resolving, so we never
  // flash the outbound-direction list.
  const resolvingReturn = awaitingReturn && !outboundTrip
  const query: TripQuery =
    awaitingReturn && outboundTrip
      ? returnQueryFor(outboundTrip, { date: returnDate })
      : { origin, destination, serviceDate }
  const { data: page, isLoading, isError, error, refetch } = useListTripsQuery(query, { skip: resolvingReturn })
  const { data: availability = [] } = useListAvailabilityQuery()

  // Per-card action: normally "Select" (start outbound); once an outbound is held, the trips become
  // "Add return"; the outbound trip itself and any card while the booking is already full are disabled.
  const cardAction = (trip: Trip): { label: string; disabled: boolean } => {
    if (outboundCode === trip.code) return { label: 'Selected', disabled: true }
    if (hasReturn) return { label: 'In booking', disabled: true }
    if (awaitingReturn) return { label: 'Add return', disabled: false }
    return { label: 'Select', disabled: false }
  }

  const availByTrip = useMemo(() => {
    const m = new Map<string, Availability>()
    for (const a of availability) m.set(a.tripCode, a)
    return m
  }, [availability])

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

  const sorted = useMemo(() => sortTrips(page?.content ?? [], sort), [page, sort])
  const trips = useMemo(
    () => (awaitingReturn ? filterByTimeOfDay(sorted, timeOfDay) : sorted),
    [sorted, awaitingReturn, timeOfDay],
  )
  const cheapest = trips.length ? Math.min(...trips.map((t) => t.priceCents)) : undefined
  const fastest = trips.length ? Math.min(...trips.map(durationMs)) : undefined

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
          <h1 className="font-display-lg text-display-lg-mobile md:text-display-lg text-on-background mb-xs">
            {awaitingReturn ? 'Pick your return' : 'Select your journey'}
          </h1>
          <div className="flex items-center gap-sm text-on-surface-variant">
            <span className="font-title-lg text-title-lg">{from ?? 'Anywhere'}</span>
            <Icon name="trending_flat" className="text-primary" />
            <span className="font-title-lg text-title-lg">{to ?? 'Anywhere'}</span>
          </div>
        </div>
        <SortTabs value={sort} onChange={setSort} />
      </div>

      <div className="grid grid-cols-12 gap-lg">
        <div className="col-span-12 lg:col-span-8 space-y-md">
          {awaitingReturn && outboundTrip && (
            <ReturnSearchBar
              date={returnDate ?? outboundTrip.serviceDate}
              onDate={setReturnDate}
              minDate={outboundTrip.serviceDate}
              timeOfDay={timeOfDay}
              onTimeOfDay={setTimeOfDay}
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
                availability={availByTrip.get(trip.code)}
                onAction={onAction}
                actionLabel={action.label}
                actionDisabled={action.disabled}
                selecting={starting && pendingTrip === trip.code}
                badge={
                  trip.priceCents === cheapest
                    ? { label: 'Cheapest', tone: 'tertiary' }
                    : durationMs(trip) === fastest
                      ? { label: 'Fastest', tone: 'accent' }
                      : undefined
                }
              />
            )
          })}
        </div>

        <aside className="col-span-12 lg:col-span-4">
          <div className="sticky top-24 space-y-md">
            <BookingSummary />
          </div>
        </aside>
      </div>
    </main>
  )
}
