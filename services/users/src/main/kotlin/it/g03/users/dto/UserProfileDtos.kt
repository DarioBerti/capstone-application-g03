package it.g03.users.dto

import it.g03.users.entities.UserProfile
import java.time.Instant
import java.util.UUID

/**
 * Boundary DTOs for the users API. JPA entities are never exposed directly (root convention);
 * the controller returns immutable value types and maps from the entity explicitly.
 */

// What a client receives for their profile.
data class ProfileResponse(
    val id: UUID,
    val keycloakSub: String,
    val email: String,
    val givenName: String?,
    val familyName: String?,
    val preferredUsername: String?,
    val locale: String?,
    val preferredCurrency: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

// Editable fields the user may change about themselves. Identity fields (sub, email) come from Keycloak and
// are not editable here; null fields are left unchanged.
data class UpdateProfileRequest(
    val givenName: String? = null,
    val familyName: String? = null,
    val preferredUsername: String? = null,
    val locale: String? = null,
    val preferredCurrency: String? = null,
)

// Identity extracted from a validated Keycloak JWT — the input to just-in-time provisioning.
// Kept as an immutable value type so the service never depends on Spring Security's Jwt directly.
data class UserClaims(
    val sub: String,
    val email: String,
    val givenName: String?,
    val familyName: String?,
    val preferredUsername: String?,
)

// Entity -> response mapping, kept at the boundary.
fun UserProfile.toResponse() = ProfileResponse(
    id = id,
    keycloakSub = keycloakSub,
    email = email,
    givenName = givenName,
    familyName = familyName,
    preferredUsername = preferredUsername,
    locale = locale,
    preferredCurrency = preferredCurrency,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
