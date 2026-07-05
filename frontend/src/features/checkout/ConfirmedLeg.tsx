import StationLine from '../search/StationLine'
import { formatCents, formatDate, formatDuration } from '../../lib/format'
import { useGetTripQuery } from '../catalog/catalogApi'
import type { OrderLeg } from '../../types'

// Enriches a confirmed leg with catalog data (times/cities) for the ticket. Degrades to the leg's own fields
// if the trip lookup is unavailable — the order model itself carries no times/seat/carriage.
export default function ConfirmedLeg({ leg, currency }: { leg: OrderLeg; currency: string }) {
  const { data: trip } = useGetTripQuery(leg.tripCode)
  return (
    <div className="text-left border-t border-outline-variant/20 pt-lg first:border-t-0 first:pt-0">
      <div className="flex items-center justify-between mb-sm">
        <p className="font-label-caps text-label-caps text-on-surface-variant">
          {leg.direction === 'OUTBOUND' ? 'OUTBOUND' : 'RETURN'} · {leg.tripCode}
        </p>
        <p className="font-label-tabular text-label-tabular tabular-nums">{formatCents(leg.lineAmountCents, currency)}</p>
      </div>
      {trip ? (
        <>
          <StationLine
            departTime={trip.departureTime}
            departPlace={trip.origin.city}
            arriveTime={trip.arrivalTime}
            arrivePlace={trip.destination.city}
            duration={formatDuration(trip.departureTime, trip.arrivalTime)}
          />
          <p className="font-body-sm text-body-sm text-on-surface-variant mt-sm">
            {formatDate(trip.serviceDate)} · {trip.carrierCode} {trip.serviceNumber} · {leg.qty}{' '}
            {leg.qty > 1 ? 'passengers' : 'passenger'}
          </p>
        </>
      ) : (
        <p className="font-body-sm text-body-sm text-on-surface-variant">
          {leg.qty} {leg.qty > 1 ? 'passengers' : 'passenger'}
        </p>
      )}
    </div>
  )
}
