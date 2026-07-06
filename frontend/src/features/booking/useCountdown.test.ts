import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { useCountdown } from './useCountdown'

describe('useCountdown', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  test('derives the remaining time from the absolute deadline, ignoring a stale snapshot', () => {
    // Arrange
    vi.setSystemTime(new Date('2026-07-06T12:00:00Z'))

    // Act
    const { result } = renderHook(() => useCountdown('2026-07-06T12:01:30Z', 865))

    // Assert
    expect(result.current).toBe(90)
    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(result.current).toBe(89)
  })

  test('a remount does not restart the countdown', () => {
    // Arrange
    vi.setSystemTime(new Date('2026-07-06T12:00:00Z'))
    const first = renderHook(() => useCountdown('2026-07-06T12:01:30Z', 90))
    expect(first.result.current).toBe(90)
    first.unmount()

    // Act
    act(() => {
      vi.advanceTimersByTime(30_000)
    })
    const second = renderHook(() => useCountdown('2026-07-06T12:01:30Z', 90))

    // Assert
    expect(second.result.current).toBe(60)
  })

  test('falls back to remainingSeconds when no deadline is present', () => {
    const { result } = renderHook(() => useCountdown(null, 300))
    expect(result.current).toBe(300)
  })

  test('returns null when the order has no hold window', () => {
    const { result } = renderHook(() => useCountdown(null, null))
    expect(result.current).toBeNull()
  })

  test('never goes below zero once the deadline has passed', () => {
    // Arrange
    vi.setSystemTime(new Date('2026-07-06T12:00:00Z'))

    // Act
    const { result } = renderHook(() => useCountdown('2026-07-06T11:59:00Z', null))

    // Assert
    expect(result.current).toBe(0)
  })
})
