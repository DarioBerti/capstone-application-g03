import { beforeEach, describe, expect, test, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import MobileMenu from './MobileMenu'
import { login, logout } from '../../auth'

vi.mock('../../auth', () => ({
  isAuthenticated: () => true,
  getToken: async () => undefined,
  currentUser: () => undefined,
  login: vi.fn(),
  logout: vi.fn(),
  initAuth: async () => true,
}))

const renderMenu = (authed = true, initialPath = '/') =>
  render(
    <MemoryRouter initialEntries={[initialPath]}>
      <MobileMenu authed={authed} displayName="Ada" />
    </MemoryRouter>,
  )

describe('MobileMenu', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  test('opens the drawer with nav links and sign out when authenticated', () => {
    renderMenu()
    const trigger = screen.getByRole('button', { name: 'Open menu' })
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(trigger)
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    const dialog = screen.getByRole('dialog', { name: 'Menu' })
    expect(within(dialog).getByRole('link', { name: 'My Trips' })).toHaveAttribute('href', '/orders')
    expect(within(dialog).getByRole('link', { name: /Profile — Ada/ })).toHaveAttribute('href', '/profile')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Sign out' }))
    expect(logout).toHaveBeenCalled()
  })

  test('shows sign in instead of nav links when unauthenticated', () => {
    renderMenu(false)
    fireEvent.click(screen.getByRole('button', { name: 'Open menu' }))
    const dialog = screen.getByRole('dialog', { name: 'Menu' })
    expect(within(dialog).queryByRole('link')).not.toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Sign In' }))
    expect(login).toHaveBeenCalled()
  })

  test('closes on Escape, unlocks scroll, and returns focus to the trigger', () => {
    renderMenu()
    const trigger = screen.getByRole('button', { name: 'Open menu' })
    fireEvent.click(trigger)
    expect(document.body.style.overflow).toBe('hidden')
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    expect(document.body.style.overflow).toBe('')
    expect(trigger).toHaveFocus()
  })

  test('closes when the backdrop is clicked', () => {
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'Open menu' }))
    fireEvent.click(screen.getByTestId('mobile-menu-backdrop'))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  test('closes when a nav link is followed', () => {
    renderMenu()
    fireEvent.click(screen.getByRole('button', { name: 'Open menu' }))
    fireEvent.click(screen.getByRole('link', { name: 'My Trips' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  test('closes when the link for the current route is tapped', () => {
    renderMenu(true, '/orders')
    fireEvent.click(screen.getByRole('button', { name: 'Open menu' }))
    fireEvent.click(screen.getByRole('link', { name: 'My Trips' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
