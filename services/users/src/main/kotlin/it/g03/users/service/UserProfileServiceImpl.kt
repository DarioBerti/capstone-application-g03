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
 * token still owns (email/names) so the local copy doesn't drift. Profiles are found by their Keycloak `sub`
 * (the natural key); the row's own primary key is a surrogate UUID. This keeps `users` decoupled from
 * Keycloak's admin API — no sync job, no webhook.
 */
@Service
class UserProfileServiceImpl(private val profiles: UserProfileRepository) : UserProfileService {

    // Returns the caller's profile, creating it on first contact and keeping token-owned fields current.
    // Idempotent: repeated calls with the same claims converge to the same row.
    @Transactional
    override fun getOrProvision(claims: UserClaims): UserProfile {
        val existing = profiles.findByKeycloakSub(claims.sub)
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

    // Updates the caller's editable fields (display fields + locale/currency preferences). Null request
    // fields are left unchanged. The profile must already exist (getOrProvision runs on /me first).
    @Transactional
    override fun update(sub: String, request: UpdateProfileRequest): UserProfile {
        val profile = profiles.findByKeycloakSub(sub)
            ?: throw NoSuchElementException("no profile for subject: $sub")
        request.givenName?.let { profile.givenName = it }
        request.familyName?.let { profile.familyName = it }
        request.preferredUsername?.let { profile.preferredUsername = it }
        request.locale?.let { profile.locale = it }
        request.preferredCurrency?.let {
            // Validate at the boundary: a 3-letter ISO 4217 alpha code (e.g. EUR/CHF), stored uppercase.
            require(it.length == 3 && it.all(Char::isLetter)) { "preferredCurrency must be a 3-letter ISO 4217 code" }
            profile.preferredCurrency = it.uppercase()
        }
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
