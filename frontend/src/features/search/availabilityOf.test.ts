import { describe, expect, test } from 'vitest'
import { availabilityOf } from './SearchPage'
import type { Trip } from '../../types'

const baseTrip: Trip = {
  id: 't1',
  code: 'FR9592-20260629',
  carrierCode: 'Frecciarossa',
  serviceNumber: '9592',
  serviceDate: '2026-06-29',
  origin: { code: 'MIL', name: 'Milano Centrale', city: 'Milano', country: 'IT' },
  destination: { code: 'TOR', name: 'Torino Porta Nuova', city: 'Torino', country: 'IT' },
  departureTime: '2026-06-29T08:00:00Z',
  arrivalTime: '2026-06-29T09:00:00Z',
  priceCents: 1600,
  currency: 'EUR',
  priceToken: 'test.price.token',
}

// SearchPage maps Catalog's availableSeats projection into the TripCard badge input. Sold-out (0) trips are
// filtered out server-side, so the storefront only ever renders known-positive or unknown counts here.
describe('availabilityOf', () => {
  test('returns undefined when the seat count is unknown (null)', () => {
    expect(availabilityOf({ ...baseTrip, availableSeats: null })).toBeUndefined()
  })

  test('returns undefined when availableSeats is absent', () => {
    expect(availabilityOf(baseTrip)).toBeUndefined()
  })

  test('builds a badge input from a known seat count', () => {
    expect(availabilityOf({ ...baseTrip, availableSeats: 5 })).toEqual({
      tripCode: 'FR9592-20260629',
      available: 5,
      totalSeats: 5,
    })
  })
})
