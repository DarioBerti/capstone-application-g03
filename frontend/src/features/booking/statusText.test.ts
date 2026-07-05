import { describe, expect, test } from 'vitest'
import { rejectionMessage } from './statusText'

describe('rejectionMessage', () => {
  test('maps PAYMENT_DECLINED to a retry-oriented message', () => {
    expect(rejectionMessage('PAYMENT_DECLINED')).toMatch(/declined/i)
  })

  test('maps EXPIRED_NO_PAYMENT to a released-seats message', () => {
    expect(rejectionMessage('EXPIRED_NO_PAYMENT')).toMatch(/released/i)
  })

  test('maps SERVICE_UNAVAILABLE to a not-charged reassurance', () => {
    expect(rejectionMessage('SERVICE_UNAVAILABLE')).toMatch(/not charged/i)
  })

  test('falls back to a generic message when reason is null', () => {
    expect(rejectionMessage(null)).toMatch(/rejected/i)
  })
})
