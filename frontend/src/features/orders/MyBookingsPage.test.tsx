import { afterAll, afterEach, beforeAll, describe, expect, test, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { Provider } from 'react-redux'
import { configureStore } from '@reduxjs/toolkit'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { apiSlice } from '../../api/apiSlice'
import MyBookingsPage from './MyBookingsPage'
import type { Order, Page } from '../../types'

vi.mock('../../auth', () => ({
  isAuthenticated: () => true,
  getToken: async () => undefined,
  currentUser: () => undefined,
  login: vi.fn(),
  logout: vi.fn(),
  initAuth: async () => true,
}))

const emptyPage: Page<Order> = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }

let lastRequestUrl: URL | null = null
const server = setupServer(
  http.get('*/api/orders', ({ request }) => {
    lastRequestUrl = new URL(request.url)
    return HttpResponse.json(emptyPage)
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  lastRequestUrl = null
})
afterAll(() => server.close())

const renderPage = () => {
  const store = configureStore({
    reducer: { [apiSlice.reducerPath]: apiSlice.reducer },
    middleware: (gdm) => gdm().concat(apiSlice.middleware),
  })
  return render(
    <Provider store={store}>
      <MemoryRouter>
        <MyBookingsPage />
      </MemoryRouter>
    </Provider>,
  )
}

describe('MyBookingsPage', () => {
  test('requests only CONFIRMED orders, newest first from the backend default sort', async () => {
    renderPage()
    expect(await screen.findByText(/no confirmed trips yet/i)).toBeInTheDocument()
    expect(lastRequestUrl?.searchParams.get('status')).toBe('CONFIRMED')
    expect(lastRequestUrl?.searchParams.get('size')).toBe('20')
    expect(lastRequestUrl?.searchParams.get('createdFrom')).toBeNull()
  })
})
