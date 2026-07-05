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

// The storefront browses BEFORE sign-in: a 401 on a read (GET) must degrade (render empty/error), never force a
// login redirect — otherwise the public homepage bounces to Keycloak. Only a failed *mutation* (an
// unauthenticated user trying to book/pay) bounces to sign-in. An authed user whose token expired is already
// re-logged-in by getToken() (updateToken) before the request goes out, so GETs never need a redirect here.
httpClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    const method = error.config?.method?.toLowerCase()
    if (error.response?.status === 401 && method && method !== 'get') login()
    return Promise.reject(error)
  },
)
