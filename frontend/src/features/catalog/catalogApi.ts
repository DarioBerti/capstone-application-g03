import { apiSlice } from '../../api/apiSlice'
import type { Page, Station, Trip } from '../../types'

// Filters for the trip list (mirror the catalog TripFilter). All optional: an empty query lists everything.
export interface TripQuery {
  origin?: string
  destination?: string
  carrierCode?: string
  serviceDate?: string
  maxPriceCents?: number
  sort?: string
  page?: number
  size?: number
}

export const catalogApi = apiSlice.injectEndpoints({
  endpoints: (build) => ({
    listTrips: build.query<Page<Trip>, TripQuery | void>({
      query: (q) => {
        const f = q ?? {}
        const params: Record<string, string | number> = { page: f.page ?? 0, size: f.size ?? 20 }
        if (f.origin) params.origin = f.origin
        if (f.destination) params.destination = f.destination
        if (f.carrierCode) params.carrierCode = f.carrierCode
        if (f.serviceDate) params.serviceDate = f.serviceDate
        if (f.maxPriceCents != null) params.maxPriceCents = f.maxPriceCents
        if (f.sort) params.sort = f.sort
        return { url: '/catalog/trips', params }
      },
      providesTags: ['Trip'],
    }),
    getTrip: build.query<Trip, string>({
      query: (code) => ({ url: `/catalog/trips/${encodeURIComponent(code)}` }),
      providesTags: ['Trip'],
    }),
    // Stations feed the origin/destination pickers. `q` typeahead needs backend #6; until then the caller
    // fetches the (small) list and filters client-side — sending `q` is harmless (Spring ignores it).
    listStations: build.query<Station[], string | void>({
      query: (q) => ({ url: '/catalog/stations', params: { size: 100, ...(q ? { q } : {}) } }),
      transformResponse: (page: Page<Station>) => page.content,
    }),
  }),
})

export const { useListTripsQuery, useGetTripQuery, useListStationsQuery } = catalogApi
