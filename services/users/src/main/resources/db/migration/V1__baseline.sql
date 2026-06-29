-- Users schema: app-side profile data, OWNED by this service (database-per-service).
-- Identity surrogate key is a UUID `id`; the Keycloak subject (`sub` claim) is kept as a NOT NULL UNIQUE
-- natural key (the IdP linkage, stable per user, survives a future multi-IdP setup). Keycloak remains the
-- source of truth for credentials/login; this table holds the app's view of a user (contact + display
-- fields + preferences), provisioned just-in-time on the first authenticated request. Flyway owns this
-- schema; Hibernate only validates.

CREATE TABLE user_profiles (
    id                 UUID         PRIMARY KEY,
    keycloak_sub       VARCHAR(255) NOT NULL,           -- the JWT `sub` (IdP linkage / natural key)
    email              VARCHAR(320) NOT NULL,
    given_name         VARCHAR(255),
    family_name        VARCHAR(255),
    preferred_username VARCHAR(255),
    locale             VARCHAR(16),                     -- BCP-47, e.g. 'it-IT' (optional preference)
    preferred_currency VARCHAR(3),                      -- ISO 4217, e.g. 'EUR' (optional preference)
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_profiles_keycloak_sub UNIQUE (keycloak_sub),
    CONSTRAINT uq_user_profiles_email        UNIQUE (email)
);
