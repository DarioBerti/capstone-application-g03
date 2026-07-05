import axios, { type AxiosError } from 'axios'
import { getToken, login } from '../auth'

// Single Axios instance for every REST call. Same-origin `/api/*` — proxied by Vite in dev, routed by the
// Traefik ingress in prod. The services are OAuth2 resource servers, so each call carries the Keycloak token.
export const httpClient = axios.create({ baseURL: '/api' })

// Attach the bearer token to every request. getToken() transparently refreshes a near-expiry token and, when
// the SSO session is gone, triggers a fresh login and returns undefined — we then send no Authorization header
// (public reads still succeed; protected calls get a 401 handled below).
httpClient.interceptors.request.use(async (config) => {
  const token = await getToken()
  if (token) config.headers.set('Authorization', `Bearer ${token}`)
  return config
})

// A 401 means the token was missing/rejected server-side — bounce to Keycloak. Everything else propagates so
// the UI can render the failure (degrade, don't crash).
httpClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    if (error.response?.status === 401) login()
    return Promise.reject(error)
  },
)
