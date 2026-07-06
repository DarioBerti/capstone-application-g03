import { describe, expect, test } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import TripSummary from './TripSummary'
import type { Order, OrderLeg } from '../../types'

const aLeg = (direction: OrderLeg['direction']): OrderLeg => ({
  legId: `leg-${direction}`,
  direction,
  tripCode: direction === 'OUTBOUND' ? 'FR9592-20260706' : 'FR9593-20260708',
  qty: 1,
  unitPriceCents: 1600,
  lineAmountCents: 1600,
  status: 'RESERVED',
})

const anOrder = (status: Order['status'], legs: OrderLeg[]): Order => ({
  orderId: 'o1',
  status,
  rejectionReason: null,
  currency: 'EUR',
  amountCents: legs.reduce((sum, l) => sum + l.lineAmountCents, 0),
  legs,
  expiresAt: null,
  remainingSeconds: 600,
  paymentInitiatedAt: null,
})

const renderSummary = (order: Order) =>
  render(
    <MemoryRouter>
      <TripSummary order={order} />
    </MemoryRouter>,
  )

describe('TripSummary', () => {
  test('offers adding a return trip for a one-way HELD order', () => {
    // Arrange + Act
    renderSummary(anOrder('HELD', [aLeg('OUTBOUND')]))

    // Assert
    expect(screen.getByRole('link', { name: /add a return trip/i })).toHaveAttribute('href', '/search')
  })

  test('does not offer a return when the booking already has one', () => {
    renderSummary(anOrder('HELD', [aLeg('OUTBOUND'), aLeg('RETURN')]))
    expect(screen.queryByRole('link', { name: /add a return trip/i })).not.toBeInTheDocument()
  })

  test('does not offer a return once the order is past the hold (not HELD)', () => {
    renderSummary(anOrder('PAYING', [aLeg('OUTBOUND')]))
    expect(screen.queryByRole('link', { name: /add a return trip/i })).not.toBeInTheDocument()
  })

  test('renders each leg with its amount and the total', () => {
    renderSummary(anOrder('HELD', [aLeg('OUTBOUND'), aLeg('RETURN')]))
    expect(screen.getByText('Outbound')).toBeInTheDocument()
    expect(screen.getByText('Return')).toBeInTheDocument()
    expect(screen.getByText(/32\.00/)).toBeInTheDocument()
  })
})
