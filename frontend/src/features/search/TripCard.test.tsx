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
    render(<TripCard trip={trip} onSelect={() => {}} />)
    expect(screen.getByText(/142\.00/)).toBeInTheDocument()
    expect(screen.getByText('Eurostar')).toBeInTheDocument()
    expect(screen.getByText('Paris')).toBeInTheDocument()
    expect(screen.getByText('Amsterdam')).toBeInTheDocument()
  })

  test('calls onSelect with the trip when Select is clicked', async () => {
    const onSelect = vi.fn()
    render(<TripCard trip={trip} onSelect={onSelect} availability={{ tripCode: trip.code, available: 20, totalSeats: 100 }} />)
    await userEvent.click(screen.getByRole('button', { name: /select/i }))
    expect(onSelect).toHaveBeenCalledWith(trip)
  })

  test('disables selection and shows a low-seat badge appropriately', () => {
    const { rerender } = render(
      <TripCard trip={trip} onSelect={() => {}} availability={{ tripCode: trip.code, available: 0, totalSeats: 100 }} />,
    )
    expect(screen.getByRole('button', { name: /sold out/i })).toBeDisabled()

    rerender(<TripCard trip={trip} onSelect={() => {}} availability={{ tripCode: trip.code, available: 3, totalSeats: 100 }} />)
    expect(screen.getByText(/only 3 left/i)).toBeInTheDocument()
  })
})
