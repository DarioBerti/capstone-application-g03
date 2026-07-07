import { describe, expect, test, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
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

  test('offers a Change action per leg on a HELD order and reports the picked leg', () => {
    // Arrange
    const onChangeLeg = vi.fn()
    render(
      <MemoryRouter>
        <TripSummary order={anOrder('HELD', [aLeg('OUTBOUND'), aLeg('RETURN')])} onChangeLeg={onChangeLeg} />
      </MemoryRouter>,
    )

    // Act — change the return leg
    fireEvent.click(screen.getByRole('button', { name: /change return trip/i }))

    // Assert
    expect(screen.getByRole('button', { name: /change outbound trip/i })).toBeInTheDocument()
    expect(onChangeLeg).toHaveBeenCalledWith(expect.objectContaining({ direction: 'RETURN' }))
  })

  test('does not offer Change once the order is past the hold (not HELD)', () => {
    render(
      <MemoryRouter>
        <TripSummary order={anOrder('PAYING', [aLeg('OUTBOUND')])} onChangeLeg={vi.fn()} />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('button', { name: /change outbound trip/i })).not.toBeInTheDocument()
  })

  test('does not offer Change when no handler is supplied (read-only summary)', () => {
    renderSummary(anOrder('HELD', [aLeg('OUTBOUND')]))
    expect(screen.queryByRole('button', { name: /change outbound trip/i })).not.toBeInTheDocument()
  })
})
