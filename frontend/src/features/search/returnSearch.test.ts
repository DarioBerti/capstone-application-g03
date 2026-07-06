import { describe, expect, test } from 'vitest'
import { filterByTimeOfDay, inTimeOfDay, returnQueryFor } from './returnSearch'
import type { Station, Trip } from '../../types'

const MIL: Station = { code: 'MIL', name: 'Milano Centrale', city: 'Milano', country: 'IT' }
const ROM: Station = { code: 'ROM', name: 'Roma Termini', city: 'Roma', country: 'IT' }

function tripAt(departureTime: string, over: Partial<Trip> = {}): Trip {
  return {
    id: 'id-1',
    code: 'IT8001-20260706',
    carrierCode: 'IT',
    serviceNumber: '8001',
    serviceDate: '2026-07-06',
    origin: MIL,
    destination: ROM,
    departureTime,
    arrivalTime: '2026-07-06T10:00:00+02:00',
    priceCents: 5900,
    currency: 'EUR',
    priceToken: 'test.price.token',
    ...over,
  }
}

describe('returnQueryFor', () => {
  test('swaps origin and destination for the reverse route', () => {
    // Arrange
    const outbound = tripAt('2026-07-06T07:00:00+02:00')

    // Act
    const query = returnQueryFor(outbound)

    // Assert
    expect(query.origin).toBe('ROM')
    expect(query.destination).toBe('MIL')
  })

  test('defaults the service date to the outbound date', () => {
    const outbound = tripAt('2026-07-06T07:00:00+02:00', { serviceDate: '2026-07-06' })
    expect(returnQueryFor(outbound).serviceDate).toBe('2026-07-06')
  })

  test('honours an overridden return date and max price', () => {
    const outbound = tripAt('2026-07-06T07:00:00+02:00')
    const query = returnQueryFor(outbound, { date: '2026-07-08', maxPriceCents: 9900 })
    expect(query.serviceDate).toBe('2026-07-08')
    expect(query.maxPriceCents).toBe(9900)
  })
})

describe('inTimeOfDay', () => {
  test('any always matches', () => {
    expect(inTimeOfDay('2026-07-06T03:00:00', 'any')).toBe(true)
  })

  test('11:59 is morning, not afternoon', () => {
    expect(inTimeOfDay('2026-07-06T11:59:00', 'morning')).toBe(true)
    expect(inTimeOfDay('2026-07-06T11:59:00', 'afternoon')).toBe(false)
  })

  test('12:00 is afternoon, not morning', () => {
    expect(inTimeOfDay('2026-07-06T12:00:00', 'afternoon')).toBe(true)
    expect(inTimeOfDay('2026-07-06T12:00:00', 'morning')).toBe(false)
  })

  test('17:59 is afternoon, 18:00 is evening', () => {
    expect(inTimeOfDay('2026-07-06T17:59:00', 'afternoon')).toBe(true)
    expect(inTimeOfDay('2026-07-06T18:00:00', 'evening')).toBe(true)
    expect(inTimeOfDay('2026-07-06T18:00:00', 'afternoon')).toBe(false)
  })
})

describe('filterByTimeOfDay', () => {
  test('keeps only departures in the chosen bucket', () => {
    // Arrange — a local-time morning and an evening departure
    const morning = tripAt('2026-07-06T09:00:00', { id: 'm' })
    const evening = tripAt('2026-07-06T20:00:00', { id: 'e' })

    // Act
    const result = filterByTimeOfDay([morning, evening], 'evening')

    // Assert
    expect(result.map((t) => t.id)).toEqual(['e'])
  })

  test('any returns every trip', () => {
    const trips = [tripAt('2026-07-06T09:00:00', { id: 'm' }), tripAt('2026-07-06T20:00:00', { id: 'e' })]
    expect(filterByTimeOfDay(trips, 'any')).toHaveLength(2)
  })
})
