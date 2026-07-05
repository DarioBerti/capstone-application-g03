import { afterAll, afterEach, beforeAll, describe, expect, test } from 'vitest'
import { configureStore } from '@reduxjs/toolkit'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { apiSlice } from '../../api/apiSlice'
import { catalogApi } from './catalogApi'
import type { Page, Station, Trip } from '../../types'

const station = (code: string, city: string): Station => ({ code, name: `${city} Central`, city, country: 'XX' })
const tripPage: Page<Trip> = {
  content: [
    {
      id: 't1',
      code: 'EST9325',
      carrierCode: 'Eurostar',
      serviceNumber: '9325',
      serviceDate: '2026-06-29',
      origin: station('PAR', 'Paris'),
      destination: station('AMS', 'Amsterdam'),
      departureTime: '2026-06-29T08:22:00Z',
      arrivalTime: '2026-06-29T11:41:00Z',
      priceCents: 14200,
      currency: 'EUR',
    },
  ],
  totalElements: 1,
  totalPages: 1,
  number: 0,
  size: 20,
}
const stationPage: Page<Station> = {
  content: [station('PAR', 'Paris'), station('AMS', 'Amsterdam')],
  totalElements: 2,
  totalPages: 1,
  number: 0,
  size: 100,
}

const server = setupServer(
  http.get('*/api/catalog/trips', () => HttpResponse.json(tripPage)),
  http.get('*/api/catalog/stations', () => HttpResponse.json(stationPage)),
)

const makeStore = () =>
  configureStore({
    reducer: { [apiSlice.reducerPath]: apiSlice.reducer },
    middleware: (gdm) => gdm().concat(apiSlice.middleware),
  })

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

describe('catalogApi', () => {
  test('listTrips returns the paginated content through the axios base query', async () => {
    const store = makeStore()
    const result = await store.dispatch(catalogApi.endpoints.listTrips.initiate({})).unwrap()
    expect(result.content).toHaveLength(1)
    expect(result.content[0].carrierCode).toBe('Eurostar')
  })

  test('listStations unwraps the Page into a plain content array', async () => {
    const store = makeStore()
    const stations = await store.dispatch(catalogApi.endpoints.listStations.initiate()).unwrap()
    expect(stations.map((s) => s.code)).toEqual(['PAR', 'AMS'])
  })
})
