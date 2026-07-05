import { describe, expect, test } from 'vitest'
import { isLive, isTerminal } from './useOrderStream'

// The observe-gating logic: only stream states the backend can still advance on its own. A fresh DRAFT and a
// terminal order are NOT observed; HELD/PAYING/AUTHORIZED are.
describe('saga observe-gating', () => {
  test('CONFIRMED and REJECTED are terminal', () => {
    expect(isTerminal('CONFIRMED')).toBe(true)
    expect(isTerminal('REJECTED')).toBe(true)
  })

  test('non-terminal, non-draft states are live', () => {
    expect(isLive('HELD')).toBe(true)
    expect(isLive('PAYING')).toBe(true)
    expect(isLive('AUTHORIZED')).toBe(true)
  })

  test('DRAFT and terminal states are not live', () => {
    expect(isLive('DRAFT')).toBe(false)
    expect(isLive('CONFIRMED')).toBe(false)
    expect(isLive('REJECTED')).toBe(false)
  })
})
