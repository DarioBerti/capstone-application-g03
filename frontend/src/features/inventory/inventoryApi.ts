import { apiSlice } from '../../api/apiSlice'
import type { Availability, Page } from '../../types'

// Advisory, browse-time seat counts (public reads). The bulk list feeds the search-results overlay; the
// per-trip lookup backs a single trip view.
export const inventoryApi = apiSlice.injectEndpoints({
  endpoints: (build) => ({
    listAvailability: build.query<Availability[], void>({
      // size capped at 100 (the services' max-page-size); the overlay only needs the current browse set.
      query: () => ({ url: '/inventory/availability', params: { size: 100 } }),
      transformResponse: (page: Page<Availability>) => page.content,
      providesTags: ['Availability'],
    }),
    getAvailability: build.query<Availability, string>({
      query: (tripCode) => ({ url: `/inventory/availability/${encodeURIComponent(tripCode)}` }),
      providesTags: ['Availability'],
    }),
  }),
})

export const { useListAvailabilityQuery, useGetAvailabilityQuery } = inventoryApi
