import type { TripQuery } from '../catalog/catalogApi'
import type { Trip } from '../../types'

// The return leg of a round trip runs the reverse route: it departs from where the outbound arrived and heads
// back to where it started. `serviceDate` defaults to the outbound's date but the caller can pick another.
export function returnQueryFor(outbound: Trip, opts: { date?: string; maxPriceCents?: number } = {}): TripQuery {
  return {
    origin: outbound.destination.code,
    destination: outbound.origin.code,
    serviceDate: opts.date ?? outbound.serviceDate,
    maxPriceCents: opts.maxPriceCents,
  }
}

export type TimeOfDay = 'any' | 'morning' | 'afternoon' | 'evening'

const AFTERNOON_START = 12
const EVENING_START = 18

// Buckets on the departure's local hour (as the card renders it via `new Date(iso)`): morning < 12,
// afternoon 12–17, evening >= 18.
export function inTimeOfDay(isoDateTime: string, tod: TimeOfDay): boolean {
  if (tod === 'any') return true
  const hour = new Date(isoDateTime).getHours()
  if (tod === 'morning') return hour < AFTERNOON_START
  if (tod === 'afternoon') return hour >= AFTERNOON_START && hour < EVENING_START
  return hour >= EVENING_START
}

export function filterByTimeOfDay(trips: Trip[], tod: TimeOfDay): Trip[] {
  if (tod === 'any') return trips
  return trips.filter((t) => inTimeOfDay(t.departureTime, tod))
}
