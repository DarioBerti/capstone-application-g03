import type { TripQuery } from '../catalog/catalogApi'
import type { Trip } from '../../types'

// The return leg of a round trip runs the reverse route: it departs from where the outbound arrived and heads
// back to where it started. `serviceDate` defaults to the outbound's date but the caller can pick another.
export function returnQueryFor(outbound: Trip, opts: { date?: string } = {}): TripQuery {
  return {
    origin: outbound.destination.code,
    destination: outbound.origin.code,
    serviceDate: opts.date ?? outbound.serviceDate,
  }
}
