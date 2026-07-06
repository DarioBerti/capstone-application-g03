import { describe, expect, test } from 'vitest'
import { returnQueryFor } from './returnSearch'
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

  test('honours an overridden return date', () => {
    const outbound = tripAt('2026-07-06T07:00:00+02:00')
    expect(returnQueryFor(outbound, { date: '2026-07-08' }).serviceDate).toBe('2026-07-08')
  })
})
