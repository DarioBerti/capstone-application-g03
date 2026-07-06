import { afterAll, afterEach, beforeAll, describe, expect, test } from 'vitest'
import { configureStore } from '@reduxjs/toolkit'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { apiSlice } from '../../api/apiSlice'
import { catalogApi } from './catalogApi'
import type { DestinationSummary, Page, Station, Trip } from '../../types'

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
      priceToken: 'test.price.token',
    },
  ],
  totalElements: 1,
  totalPages: 1,
  number: 0,
  size: 20,
}
const allStations: Station[] = [station('PAR', 'Paris'), station('AMS', 'Amsterdam')]
const topDestinations: DestinationSummary[] = [
  { code: 'AMS', name: 'Amsterdam Central', city: 'Amsterdam', country: 'XX', fromPriceCents: 4550, currency: 'EUR', tripCount: 12 },
]

let lastDestinationsUrl: URL | null = null
const server = setupServer(
  http.get('*/api/catalog/trips', () => HttpResponse.json(tripPage)),
  http.get('*/api/catalog/stations/all', () => HttpResponse.json(allStations)),
  http.get('*/api/catalog/destinations/top', ({ request }) => {
    lastDestinationsUrl = new URL(request.url)
    return HttpResponse.json(topDestinations)
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

describe('catalogApi', () => {
  test('listTrips returns the paginated content through the axios base query', async () => {
    const store = makeStore()
    const result = await store.dispatch(catalogApi.endpoints.listTrips.initiate({})).unwrap()
    expect(result.content).toHaveLength(1)
    expect(result.content[0].carrierCode).toBe('Eurostar')
  })

  test('listAllStations returns the full station array', async () => {
    const store = makeStore()
    const stations = await store.dispatch(catalogApi.endpoints.listAllStations.initiate()).unwrap()
    expect(stations.map((s) => s.code)).toEqual(['PAR', 'AMS'])
  })

  test('listTopDestinations passes the limit and returns the aggregate rows', async () => {
    const store = makeStore()
    const destinations = await store.dispatch(catalogApi.endpoints.listTopDestinations.initiate()).unwrap()
    expect(destinations).toHaveLength(1)
    expect(destinations[0]).toMatchObject({ code: 'AMS', fromPriceCents: 4550, tripCount: 12 })
    expect(lastDestinationsUrl?.searchParams.get('limit')).toBe('6')
    expect(lastDestinationsUrl?.searchParams.get('origin')).toBeNull()
  })
})
