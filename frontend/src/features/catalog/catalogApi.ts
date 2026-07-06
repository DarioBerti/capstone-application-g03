import { apiSlice } from '../../api/apiSlice'
import type { DestinationSummary, Page, Station, Trip } from '../../types'

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
    // The full station list feeds the origin/destination pickers, which load it once and filter locally.
    // Small, near-static reference data: cached for the session (long keepUnusedDataFor) on top of the
    // endpoint's own HTTP cache window, so the pickers never re-fetch mid-session.
    listAllStations: build.query<Station[], void>({
      query: () => ({ url: '/catalog/stations/all' }),
      keepUnusedDataFor: 86400,
    }),
    // Aggregated "popular destinations" (cheapest future fare + departure count per destination); backs the
    // home Explore grid. Mirrors the endpoint's own 10-minute public HTTP cache window.
    listTopDestinations: build.query<DestinationSummary[], { origin?: string; limit?: number } | void>({
      query: (q) => ({
        url: '/catalog/destinations/top',
        params: { ...(q?.origin && { origin: q.origin }), limit: q?.limit ?? 6 },
      }),
      providesTags: ['Trip'],
      keepUnusedDataFor: 600,
    }),
  }),
})

export const { useListTripsQuery, useGetTripQuery, useListAllStationsQuery, useListTopDestinationsQuery } = catalogApi
