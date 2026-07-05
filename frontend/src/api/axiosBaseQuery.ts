import type { BaseQueryFn } from '@reduxjs/toolkit/query'
import type { AxiosError, AxiosRequestConfig } from 'axios'
import { httpClient } from './httpClient'

// RTK Query base query backed by our shared Axios instance, so all data-fetching flows through the same
// Keycloak-authenticated transport (interceptors in httpClient.ts) instead of RTK Query's default fetch.
export interface AxiosBaseQueryArgs {
  url: string
  method?: AxiosRequestConfig['method']
  data?: AxiosRequestConfig['data']
  params?: AxiosRequestConfig['params']
  headers?: AxiosRequestConfig['headers']
}

export interface AxiosBaseQueryError {
  status?: number
  data?: unknown
  message: string
}

export const axiosBaseQuery =
  (): BaseQueryFn<AxiosBaseQueryArgs, unknown, AxiosBaseQueryError> =>
  async ({ url, method = 'GET', data, params, headers }) => {
    try {
      const result = await httpClient({ url, method, data, params, headers })
      return { data: result.data }
    } catch (e) {
      const err = e as AxiosError
      return {
        error: {
          status: err.response?.status,
          data: err.response?.data ?? err.message,
          message: err.message,
        },
      }
    }
  }
