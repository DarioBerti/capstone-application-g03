package it.g03.users.repository

import it.g03.users.entities.UserProfile
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// JpaRepository<UserProfile, UUID> — the primary key is the surrogate `id` UUID. Profiles are looked up by
// their Keycloak `sub` (the IdP natural key) via findByKeycloakSub for just-in-time provisioning; they are
// never listed in bulk, so no Specification executor is needed.
interface UserProfileRepository : JpaRepository<UserProfile, UUID> {

    fun findByKeycloakSub(keycloakSub: String): UserProfile?
}
