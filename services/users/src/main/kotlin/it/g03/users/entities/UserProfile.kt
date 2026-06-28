package it.g03.users.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * App-side view of a user, keyed by the Keycloak subject (the JWT `sub` claim). Keycloak owns identity
 * and credentials; this service owns only the marketplace's profile data and is provisioned just-in-time
 * from the token on first contact (see UserProfileService).
 *
 * Mutable `var` + a no-arg constructor are the project's pragmatic JPA exception to immutability
 * (see root CLAUDE.md). DTOs stay immutable.
 */
@Entity
@Table(name = "user_profiles")
data class UserProfile(
    @Id
    @Column(name = "keycloak_sub", length = 255)
    val keycloakSub: String,

    @Column(nullable = false, length = 320)
    var email: String,

    @Column(name = "given_name", length = 255)
    var givenName: String? = null,

    @Column(name = "family_name", length = 255)
    var familyName: String? = null,

    @Column(name = "preferred_username", length = 255)
    var preferredUsername: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    // No-arg constructor for JPA
    constructor() : this("", "")
}
