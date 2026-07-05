import { afterAll, afterEach, beforeAll, describe, expect, test } from 'vitest'
import { configureStore } from '@reduxjs/toolkit'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { apiSlice } from '../../api/apiSlice'
import { inventoryApi } from './inventoryApi'
import type { Availability, Page } from '../../types'

const page: Page<Availability> = {
  content: [
    { tripCode: 'EST9325', available: 42, totalSeats: 100 },
    { tripCode: 'FR0001', available: 0, totalSeats: 100 },
  ],
  totalElements: 2,
  totalPages: 1,
  number: 0,
  size: 100,
}

const server = setupServer(
  http.get('*/api/inventory/availability', () => HttpResponse.json(page)),
  http.get('*/api/inventory/availability/:tripCode', ({ params }) => {
    const found = page.content.find((a) => a.tripCode === params.tripCode)
    return found ? HttpResponse.json(found) : new HttpResponse(null, { status: 404 })
  }),
)

const makeStore = () =>
  configureStore({
    reducer: { [apiSlice.reducerPath]: apiSlice.reducer },
    middleware: (gdm) => gdm().concat(apiSlice.middleware),
  })

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

describe('inventoryApi', () => {
  test('listAvailability unwraps the page content', async () => {
    const store = makeStore()
    const result = await store.dispatch(inventoryApi.endpoints.listAvailability.initiate()).unwrap()
    expect(result.map((a) => a.tripCode)).toEqual(['EST9325', 'FR0001'])
  })

  test('getAvailability fetches the per-trip projection', async () => {
    const store = makeStore()
    const result = await store.dispatch(inventoryApi.endpoints.getAvailability.initiate('EST9325')).unwrap()
    expect(result.available).toBe(42)
    expect(result.totalSeats).toBe(100)
  })
})
