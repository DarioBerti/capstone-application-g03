-- Users schema: app-side profile data, OWNED by this service (database-per-service).
-- Profiles are keyed by the Keycloak subject (the JWT `sub` claim) — the stable identity Keycloak
-- assigns to a user. Keycloak remains the source of truth for credentials/login; this table only
-- holds the app's view of a user (contact + display fields + preferences), provisioned just-in-time
-- on the first authenticated request. Flyway owns this schema; Hibernate only validates.

CREATE TABLE user_profiles (
    keycloak_sub       VARCHAR(255) PRIMARY KEY,
    email              VARCHAR(320) NOT NULL,
    given_name         VARCHAR(255),
    family_name        VARCHAR(255),
    preferred_username VARCHAR(255),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_profiles_email UNIQUE (email)
);
