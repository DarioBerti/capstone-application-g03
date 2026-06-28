package it.g03.users.service

import it.g03.users.dto.UpdateProfileRequest
import it.g03.users.dto.UserClaims
import it.g03.users.entities.UserProfile
import it.g03.users.repository.UserProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Business layer for user profiles (Controller -> Service -> Repository, mandatory for every service).
 *
 * Just-in-time provisioning: Keycloak owns identity, so the first time we see an authenticated user we
 * create their app-side profile from the token claims; on later calls we refresh the identity fields the
 * token still owns (email/names) so the local copy doesn't drift. This keeps `users` decoupled from
 * Keycloak's admin API — no sync job, no webhook.
 */
@Service
class UserProfileService(private val profiles: UserProfileRepository) {

    // Returns the caller's profile, creating it on first contact and keeping token-owned fields current.
    // Idempotent: repeated calls with the same claims converge to the same row.
    @Transactional
    fun getOrProvision(claims: UserClaims): UserProfile {
        val existing = profiles.findById(claims.sub).orElse(null)
        if (existing == null) {
            return profiles.save(
                UserProfile(
                    keycloakSub = claims.sub,
                    email = claims.email,
                    givenName = claims.givenName,
                    familyName = claims.familyName,
                    preferredUsername = claims.preferredUsername,
                ),
            )
        }
        return existing.refreshedFrom(claims)
    }

    // Updates the caller's editable display fields. Null request fields are left unchanged.
    // The profile must already exist (getOrProvision runs on /me first); a missing one is a not-found.
    @Transactional
    fun update(sub: String, request: UpdateProfileRequest): UserProfile {
        val profile = profiles.findById(sub)
            .orElseThrow { NoSuchElementException("no profile for subject: $sub") }
        request.givenName?.let { profile.givenName = it }
        request.familyName?.let { profile.familyName = it }
        request.preferredUsername?.let { profile.preferredUsername = it }
        profile.updatedAt = Instant.now()
        return profiles.save(profile)
    }

    // Re-syncs the identity fields Keycloak owns when the token shows a change; touches updated_at only then.
    private fun UserProfile.refreshedFrom(claims: UserClaims): UserProfile {
        val changed = email != claims.email ||
            givenName != claims.givenName ||
            familyName != claims.familyName ||
            preferredUsername != claims.preferredUsername
        if (!changed) return this
        email = claims.email
        givenName = claims.givenName
        familyName = claims.familyName
        preferredUsername = claims.preferredUsername
        updatedAt = Instant.now()
        return profiles.save(this)
    }
}
