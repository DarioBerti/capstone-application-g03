import { afterEach, describe, expect, test } from 'vitest'
import { clearBookingPointer, loadBookingPointer, saveBookingPointer } from './bookingStorage'

const KEY = 'eurotransit-booking'

afterEach(() => localStorage.clear())

describe('bookingStorage', () => {
  test('save then load round-trips the pointer', () => {
    // Arrange
    const pointer = { orderId: 'o1', idempotencyKey: 'key-1', selectedTripCode: 'FR9592-20260629' }

    // Act
    saveBookingPointer(pointer)

    // Assert
    expect(loadBookingPointer()).toEqual(pointer)
  })

  test('preserves null key and trip', () => {
    saveBookingPointer({ orderId: 'o1', idempotencyKey: null, selectedTripCode: null })
    expect(loadBookingPointer()).toEqual({ orderId: 'o1', idempotencyKey: null, selectedTripCode: null })
  })

  test('clear removes the pointer', () => {
    saveBookingPointer({ orderId: 'o1', idempotencyKey: 'key-1', selectedTripCode: null })
    clearBookingPointer()
    expect(loadBookingPointer()).toBeNull()
  })

  test('returns null when nothing is stored', () => {
    expect(loadBookingPointer()).toBeNull()
  })

  test('returns null on malformed JSON', () => {
    localStorage.setItem(KEY, '{not json')
    expect(loadBookingPointer()).toBeNull()
  })

  test('returns null when the stored value has no orderId', () => {
    localStorage.setItem(KEY, JSON.stringify({ idempotencyKey: 'key-1' }))
    expect(loadBookingPointer()).toBeNull()
  })
})
