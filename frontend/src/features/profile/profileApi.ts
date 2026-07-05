import { apiSlice } from '../../api/apiSlice'
import type { Profile } from '../../types'

// Editable profile fields (mirror the users UpdateProfileRequest). null/undefined = leave unchanged.
export interface UpdateProfileArgs {
  givenName?: string | null
  familyName?: string | null
  preferredUsername?: string | null
  locale?: string | null
  preferredCurrency?: string | null
}

export const profileApi = apiSlice.injectEndpoints({
  endpoints: (build) => ({
    getMe: build.query<Profile, void>({
      query: () => ({ url: '/users/me' }),
      providesTags: ['Profile'],
    }),
    updateMe: build.mutation<Profile, UpdateProfileArgs>({
      query: (body) => ({ url: '/users/me', method: 'PUT', data: body }),
      invalidatesTags: ['Profile'],
    }),
  }),
})

export const { useGetMeQuery, useUpdateMeMutation } = profileApi
