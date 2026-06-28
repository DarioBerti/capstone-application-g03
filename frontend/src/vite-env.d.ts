/// <reference types="vite/client" />

// Build-time configuration for the Keycloak SPA client (see auth.ts). All optional — local-dev
// defaults are baked into auth.ts.
interface ImportMetaEnv {
  readonly VITE_KEYCLOAK_URL?: string
  readonly VITE_KEYCLOAK_REALM?: string
  readonly VITE_KEYCLOAK_CLIENT_ID?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
