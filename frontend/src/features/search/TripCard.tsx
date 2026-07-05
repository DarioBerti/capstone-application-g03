import Icon from '../../components/Icon'
import Badge from '../../components/ui/Badge'
import Spinner from '../../components/ui/Spinner'
import StationLine from './StationLine'
import { formatCents, formatDuration } from '../../lib/format'
import type { Availability, Trip } from '../../types'

const LOW_SEAT_THRESHOLD = 10

export default function TripCard({
  trip,
  availability,
  onSelect,
  selecting,
  badge,
}: {
  trip: Trip
  availability?: Availability
  onSelect: (trip: Trip) => void
  selecting?: boolean
  badge?: { label: string; tone: 'accent' | 'tertiary' }
}) {
  const soldOut = availability != null && availability.available <= 0
  const low = availability != null && availability.available > 0 && availability.available <= LOW_SEAT_THRESHOLD

  return (
    <div className="group bg-surface-container-lowest border border-outline-variant rounded-xl p-lg shadow-sm hover:-translate-y-1 transition-all duration-300 relative overflow-hidden">
      <div className="absolute left-0 top-0 bottom-0 w-1 bg-primary transform origin-left scale-y-0 group-hover:scale-y-100 transition-transform" />
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-xl">
        <div className="flex-grow grid grid-cols-1 md:grid-cols-3 items-center gap-lg">
          <div className="flex items-center gap-md">
            <div className="w-12 h-12 bg-surface-container flex items-center justify-center rounded-lg shrink-0">
              <Icon name="train" filled className="text-primary" />
            </div>
            <div>
              <p className="font-label-caps text-label-caps text-primary uppercase">{trip.carrierCode}</p>
              <p className="font-body-sm text-body-sm text-on-surface-variant">{trip.serviceNumber}</p>
            </div>
          </div>
          <div className="md:col-span-2">
            <StationLine
              departTime={trip.departureTime}
              departPlace={trip.origin.city}
              arriveTime={trip.arrivalTime}
              arrivePlace={trip.destination.city}
              duration={formatDuration(trip.departureTime, trip.arrivalTime)}
            />
          </div>
        </div>
        <div className="flex md:flex-col items-end justify-between md:justify-center border-t md:border-t-0 md:border-l border-outline-variant pt-md md:pt-0 md:pl-xl gap-sm min-w-[140px]">
          <div className="text-right">
            <p className="font-label-caps text-label-caps text-on-surface-variant">From</p>
            <p className="font-display-lg text-title-lg tabular-nums text-on-background font-extrabold">
              {formatCents(trip.priceCents, trip.currency)}
            </p>
          </div>
          {badge && <Badge tone={badge.tone}>{badge.label}</Badge>}
          {low && <Badge tone="warning">Only {availability!.available} left</Badge>}
          <button
            onClick={() => onSelect(trip)}
            disabled={soldOut || selecting}
            className="mt-xs w-full md:w-auto px-lg py-sm bg-primary text-on-primary rounded-lg font-bold hover:brightness-110 active:scale-95 transition-all disabled:opacity-50 disabled:pointer-events-none flex items-center justify-center gap-xs"
          >
            {selecting ? <Spinner /> : soldOut ? 'Sold out' : 'Select'}
          </button>
        </div>
      </div>
    </div>
  )
}
