import Keycloak from 'keycloak-js'

// Single Keycloak client for the SPA. Values come from build-time env (VITE_*) with local-dev defaults
// that match the dev Keycloak realm (the kind ingress host, also the token issuer). The client is
// PUBLIC (no secret) and uses Authorization Code + PKCE.
const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL ?? 'https://auth.localhost',
  realm: import.meta.env.VITE_KEYCLOAK_REALM ?? 'eurotransit',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'eurotransit-frontend',
})

let initialized = false

// Initializes Keycloak once. `check-sso` restores an existing SSO session silently (via the hidden
// iframe page) without forcing a login redirect on first load — the user clicks "Sign in" explicitly.
export async function initAuth(): Promise<boolean> {
  if (initialized) return keycloak.authenticated ?? false
  initialized = true
  return keycloak.init({
    onLoad: 'check-sso',
    pkceMethod: 'S256',
    silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
    checkLoginIframe: false,
  })
}

export function login(): void {
  keycloak.login()
}

export function logout(): void {
  keycloak.logout({ redirectUri: window.location.origin })
}

export function isAuthenticated(): boolean {
  return keycloak.authenticated ?? false
}

// Display name for the header, best-effort from the token claims.
export function currentUser(): string | undefined {
  return keycloak.tokenParsed?.preferred_username ?? keycloak.tokenParsed?.email
}

// Returns a valid access token, refreshing it if it expires within 30s. On a failed refresh (expired
// SSO session) it triggers a fresh login and returns undefined so the caller aborts the request.
export async function getToken(): Promise<string | undefined> {
  if (!keycloak.authenticated) return undefined
  try {
    await keycloak.updateToken(30)
    return keycloak.token
  } catch {
    keycloak.login()
    return undefined
  }
}
