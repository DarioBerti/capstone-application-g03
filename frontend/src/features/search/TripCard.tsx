import Icon from '../../components/Icon'
import Badge from '../../components/ui/Badge'
import Spinner from '../../components/ui/Spinner'
import { formatCents, formatDayMonth, formatDuration, formatTime } from '../../lib/format'
import type { Availability, Trip } from '../../types'

const LOW_SEAT_THRESHOLD = 10

export default function TripCard({
  trip,
  availability,
  onAction,
  actionLabel,
  actionDisabled,
  selecting,
}: {
  trip: Trip
  availability?: Availability
  onAction: (trip: Trip) => void
  // Contextual action: "Select" to start a booking, or "Add return" once an outbound is held (set by SearchPage).
  actionLabel: string
  actionDisabled?: boolean
  selecting?: boolean
}) {
  const soldOut = availability != null && availability.available <= 0
  const low = availability != null && availability.available > 0 && availability.available <= LOW_SEAT_THRESHOLD
  const plenty = availability != null && availability.available > LOW_SEAT_THRESHOLD
  const disabled = soldOut || actionDisabled || selecting

  return (
    <div className="group bg-surface rounded-xl shadow-sm border border-outline-variant p-md hover:shadow-md hover:-translate-y-0.5 transition-all duration-300">
      <div className="flex flex-col md:flex-row items-start md:items-center justify-between gap-lg">
        <div className="flex items-start gap-xl flex-1">
          <div className="flex flex-col">
            <span className="font-headline-md text-headline-md text-on-surface tabular-nums">
              {formatTime(trip.departureTime)}
            </span>
            <span className="font-headline-md text-headline-md text-on-surface-variant tabular-nums mt-md">
              {formatTime(trip.arrivalTime)}
            </span>
          </div>

          <div className="flex flex-col flex-1 relative min-w-[200px]">
            <div className="flex items-center gap-md mb-md">
              <div className="w-3 h-3 rounded-full bg-primary z-10 relative" />
              <span className="font-label-tabular text-label-tabular text-on-surface">{trip.origin.name}</span>
            </div>
            <div className="absolute left-1.5 top-3 bottom-5 w-px bg-outline-variant" />
            <div className="flex items-center gap-md mt-xs">
              <div className="w-3 h-3 rounded-full bg-on-background z-10 relative" />
              <span className="font-label-tabular text-label-tabular text-on-surface">{trip.destination.name}</span>
            </div>
          </div>

          <div className="flex flex-col items-start gap-sm">
            <span className="font-body-sm text-body-sm text-on-surface-variant whitespace-nowrap">
              {formatDayMonth(trip.departureTime)} · {formatDuration(trip.departureTime, trip.arrivalTime)}
            </span>
            {/* Fixed label: the domain has no connection model, every trip is a single direct service. */}
            <div className="flex items-center gap-xs text-primary bg-primary-container/10 px-sm py-xs rounded">
              <Icon name="train" className="text-[16px]" />
              <span className="font-label-caps text-label-caps">DIRECT</span>
            </div>
            <span className="font-body-sm text-body-sm text-outline whitespace-nowrap">
              {trip.carrierCode} · {trip.serviceNumber}
            </span>
            {plenty && (
              <span className="flex items-center gap-xs font-body-sm text-body-sm text-on-surface-variant whitespace-nowrap">
                <Icon name="event_seat" className="text-sm" />
                <span className="tabular-nums">{availability!.available} seats available</span>
              </span>
            )}
            {low && <Badge tone="warning">Only {availability!.available} left</Badge>}
          </div>
        </div>

        <div className="flex flex-row md:flex-col items-center md:items-end justify-between w-full md:w-auto mt-md md:mt-0 pt-md md:pt-0 border-t md:border-t-0 border-outline-variant gap-md">
          <div className="flex flex-col items-end">
            <span className="font-body-sm text-body-sm text-on-surface-variant">from</span>
            <span className="font-headline-md text-headline-md text-on-surface tabular-nums">
              {formatCents(trip.priceCents, trip.currency)}
            </span>
          </div>
          <button
            onClick={() => onAction(trip)}
            disabled={disabled}
            className="bg-surface-variant text-primary border border-primary font-label-tabular text-label-tabular px-lg py-sm rounded-lg group-hover:enabled:bg-primary group-hover:enabled:text-on-primary transition-colors disabled:opacity-50 disabled:pointer-events-none flex items-center justify-center gap-xs"
          >
            {selecting ? <Spinner /> : soldOut ? 'Sold out' : actionLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
