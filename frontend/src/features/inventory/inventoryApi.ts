import { apiSlice } from '../../api/apiSlice'
import type { Availability, Page } from '../../types'

// Advisory, browse-time seat counts. Only the public bulk read is guaranteed routed; the per-trip lookup
// (backend #5) may not exist yet, so we derive it from the bulk list until it lands.
export const inventoryApi = apiSlice.injectEndpoints({
  endpoints: (build) => ({
    listAvailability: build.query<Availability[], void>({
      query: () => ({ url: '/inventory/availability', params: { size: 200 } }),
      transformResponse: (page: Page<Availability>) => page.content,
      providesTags: ['Availability'],
    }),
    getAvailability: build.query<Availability | undefined, string>({
      query: () => ({ url: '/inventory/availability', params: { size: 200 } }),
      transformResponse: (page: Page<Availability>, _meta, tripCode: string) =>
        page.content.find((a) => a.tripCode === tripCode),
      providesTags: ['Availability'],
    }),
  }),
})

export const { useListAvailabilityQuery, useGetAvailabilityQuery } = inventoryApi
