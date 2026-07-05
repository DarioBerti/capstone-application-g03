import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import Icon from '../../components/Icon'
import { ErrorState, LoadingBlock, EmptyState } from '../../components/ui/QueryStates'
import SortTabs, { type SortKey } from './SortTabs'
import TripCard from './TripCard'
import InfoBento from './InfoBento'
import QuickFilters from './QuickFilters'
import BookingSummary from '../booking/BookingSummary'
import { useBookingActions } from '../booking/useBookingActions'
import { useAppSelector } from '../../app/hooks'
import { useListTripsQuery, type TripQuery } from '../catalog/catalogApi'
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
  const [maxPriceCents, setMaxPriceCents] = useState<number | undefined>(undefined)

  const query: TripQuery = { origin, destination, serviceDate, maxPriceCents }
  const { data: page, isLoading, isError, error, refetch } = useListTripsQuery(query)
  const { data: availability = [] } = useListAvailabilityQuery()
  const { startBooking, addReturn, starting } = useBookingActions()

  // Active in-progress booking (DRAFT/HELD) drives the round-trip flow: once an outbound leg is held, the
  // result cards offer "Add return" for the reverse trip instead of starting a new booking.
  const order = useAppSelector((s) => s.booking.order)
  const activeOrder = order && (order.status === 'DRAFT' || order.status === 'HELD') ? order : null
  const activeLegs = activeOrder?.legs.filter((l) => ACTIVE_LEG.has(l.status)) ?? []
  const outboundCode = activeLegs.find((l) => l.direction === 'OUTBOUND')?.tripCode
  const hasReturn = activeLegs.some((l) => l.direction === 'RETURN')
  const awaitingReturn = !!outboundCode && !hasReturn

  // Per-card action: normally "Select" (start outbound); once an outbound is held, the other trips become
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
        await addReturn(activeOrder.orderId, trip.code, pax)
      } else {
        await startBooking(trip.code, pax)
      }
    } finally {
      setPendingTrip(null)
    }
  }

  const trips = useMemo(() => sortTrips(page?.content ?? [], sort), [page, sort])
  const cheapest = trips.length ? Math.min(...trips.map((t) => t.priceCents)) : undefined
  const fastest = trips.length ? Math.min(...trips.map(durationMs)) : undefined

  const routeLabel = origin && destination ? `${origin} → ${destination}` : 'All journeys'

  return (
    <main className="flex-grow max-w-max-width mx-auto px-margin-mobile md:px-margin-desktop w-full py-xl">
      <div className="mb-xl flex flex-col md:flex-row md:items-end justify-between gap-md">
        <div>
          <h1 className="font-display-lg text-display-lg-mobile text-on-background mb-xs">Select your journey</h1>
          <div className="flex items-center gap-sm text-on-surface-variant">
            <span className="font-title-lg text-title-lg">{origin ?? 'Anywhere'}</span>
            <Icon name="trending_flat" className="text-primary" />
            <span className="font-title-lg text-title-lg">{destination ?? 'Anywhere'}</span>
          </div>
        </div>
        <SortTabs value={sort} onChange={setSort} />
      </div>

      <div className="grid grid-cols-12 gap-gutter">
        <div className="col-span-12 lg:col-span-8 space-y-md">
          {isLoading && <LoadingBlock label="Finding journeys…" />}
          {isError && <ErrorState error={error} onRetry={refetch} />}
          {!isLoading && !isError && trips.length === 0 && (
            <EmptyState icon="search_off" title="No journeys found" hint={`Try a different date or route for ${routeLabel}.`} />
          )}
          {awaitingReturn && (
            <div className="flex items-center gap-sm p-md bg-secondary-container/40 border border-outline-variant/30 rounded-xl text-on-secondary-container">
              <Icon name="swap_horiz" className="text-primary" />
              <p className="font-body-sm text-body-sm">
                Outbound held. Pick a return trip to make it round-trip, or proceed to checkout for one-way.
              </p>
            </div>
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
          {trips.length > 0 && <InfoBento />}
        </div>

        <aside className="col-span-12 lg:col-span-4">
          <div className="sticky top-24 space-y-md">
            <BookingSummary />
            <QuickFilters maxPriceCents={maxPriceCents} onMaxPrice={setMaxPriceCents} />
          </div>
        </aside>
      </div>
    </main>
  )
}
