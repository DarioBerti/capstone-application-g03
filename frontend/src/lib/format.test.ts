import { describe, expect, test } from 'vitest'
import { formatCents, formatCountdown, formatDayMonth, formatDuration } from './format'

describe('formatCents', () => {
  test('renders cents as euro currency', () => {
    expect(formatCents(14200, 'EUR')).toMatch(/142\.00/)
  })

  test('renders zero as a currency amount', () => {
    expect(formatCents(0, 'EUR')).toMatch(/0\.00/)
  })
})

describe('formatCountdown', () => {
  test('formats seconds as zero-padded mm:ss', () => {
    expect(formatCountdown(595)).toBe('09:55')
  })

  test('never goes below zero', () => {
    expect(formatCountdown(-5)).toBe('00:00')
  })
})

describe('formatDayMonth', () => {
  // Noon UTC so no realistic dev/CI timezone shifts the calendar day.
  test('renders a compact day and month without the year', () => {
    expect(formatDayMonth('2026-07-15T12:00:00Z')).toBe('15 Jul')
  })
})

describe('formatDuration', () => {
  test('renders hours and minutes between two instants', () => {
    expect(formatDuration('2026-06-29T08:22:00Z', '2026-06-29T11:41:00Z')).toBe('3h 19m')
  })

  test('renders minutes only for sub-hour trips', () => {
    expect(formatDuration('2026-06-29T08:00:00Z', '2026-06-29T08:45:00Z')).toBe('45m')
  })
})
