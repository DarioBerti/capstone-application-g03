import { describe, expect, test, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import TripCard from './TripCard'
import type { Trip } from '../../types'

const trip: Trip = {
  id: 't1',
  code: 'EST9325-20260629',
  carrierCode: 'Eurostar',
  serviceNumber: '9325 · Standard',
  serviceDate: '2026-06-29',
  origin: { code: 'PAR', name: 'Paris Gare du Nord', city: 'Paris', country: 'FR' },
  destination: { code: 'AMS', name: 'Amsterdam Centraal', city: 'Amsterdam', country: 'NL' },
  departureTime: '2026-06-29T08:22:00Z',
  arrivalTime: '2026-06-29T11:41:00Z',
  priceCents: 14200,
  currency: 'EUR',
}

describe('TripCard', () => {
  test('shows the price, carrier and cities', () => {
    render(<TripCard trip={trip} onAction={() => {}} actionLabel="Select" />)
    expect(screen.getByText(/142\.00/)).toBeInTheDocument()
    expect(screen.getByText('Eurostar')).toBeInTheDocument()
    expect(screen.getByText('Paris')).toBeInTheDocument()
    expect(screen.getByText('Amsterdam')).toBeInTheDocument()
  })

  test('calls onAction with the trip when the action is clicked', async () => {
    const onAction = vi.fn()
    render(
      <TripCard
        trip={trip}
        onAction={onAction}
        actionLabel="Select"
        availability={{ tripCode: trip.code, available: 20, totalSeats: 100 }}
      />,
    )
    await userEvent.click(screen.getByRole('button', { name: /select/i }))
    expect(onAction).toHaveBeenCalledWith(trip)
  })

  test('renders the contextual action label (e.g. Add return)', () => {
    render(
      <TripCard trip={trip} onAction={() => {}} actionLabel="Add return" availability={{ tripCode: trip.code, available: 20, totalSeats: 100 }} />,
    )
    expect(screen.getByRole('button', { name: /add return/i })).toBeInTheDocument()
  })

  test('disables the action and shows a low-seat badge appropriately', () => {
    const { rerender } = render(
      <TripCard trip={trip} onAction={() => {}} actionLabel="Select" availability={{ tripCode: trip.code, available: 0, totalSeats: 100 }} />,
    )
    expect(screen.getByRole('button', { name: /sold out/i })).toBeDisabled()

    rerender(
      <TripCard trip={trip} onAction={() => {}} actionLabel="Select" availability={{ tripCode: trip.code, available: 3, totalSeats: 100 }} />,
    )
    expect(screen.getByText(/only 3 left/i)).toBeInTheDocument()
  })

  test('respects actionDisabled (e.g. the outbound card while booking)', () => {
    render(
      <TripCard trip={trip} onAction={() => {}} actionLabel="Selected" actionDisabled availability={{ tripCode: trip.code, available: 20, totalSeats: 100 }} />,
    )
    expect(screen.getByRole('button', { name: /selected/i })).toBeDisabled()
  })
})
