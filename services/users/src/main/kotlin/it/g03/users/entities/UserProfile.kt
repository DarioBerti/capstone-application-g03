package it.g03.users.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * App-side view of a user. Identity is a surrogate `id` UUID (app-assigned, no shared sequence); the Keycloak
 * subject (`keycloakSub`, the JWT `sub` claim) is kept as a NOT NULL UNIQUE natural key — the IdP linkage,
 * used for the just-in-time find-or-create. Keycloak owns identity/credentials; this service owns only the
 * marketplace's profile data (contact + display fields + locale/currency preferences), provisioned just-in-time
 * from the token on first contact (see UserProfileService).
 *
 * Plain `class`, not `data class` — a JPA-managed entity has identity, not value, semantics (root CLAUDE.md).
 * Mutable `var`s are the project's pragmatic JPA exception to immutability; DTOs stay immutable.
 */
@Entity
@Table(name = "user_profiles")
class UserProfile(
    @Id
    @Column(name = "id")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "keycloak_sub", nullable = false, length = 255)
    val keycloakSub: String = "",

    @Column(nullable = false, length = 320)
    var email: String = "",

    @Column(name = "given_name", length = 255)
    var givenName: String? = null,

    @Column(name = "family_name", length = 255)
    var familyName: String? = null,

    @Column(name = "preferred_username", length = 255)
    var preferredUsername: String? = null,

    @Column(length = 16)
    var locale: String? = null,

    @Column(name = "preferred_currency", length = 3)
    var preferredCurrency: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
