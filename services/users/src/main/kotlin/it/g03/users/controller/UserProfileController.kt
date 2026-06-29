package it.g03.users.controller

import it.g03.users.dto.ProfileResponse
import it.g03.users.dto.UpdateProfileRequest
import it.g03.users.dto.UserClaims
import it.g03.users.dto.toResponse
import it.g03.users.service.UserProfileService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Self-service profile API (exposed through the ingress at /api/users). Every endpoint operates on the
 * *caller's own* profile, identified by the validated JWT's subject — there is no way to address another
 * user's profile, so authorization is implicit in the token. HTTP-only: it reads the principal, maps to
 * the service's value types, and returns DTOs (Controller -> Service -> Repository).
 */
@RestController
@RequestMapping("/api/users")
class UserProfileController(private val service: UserProfileService) {

    // GET /api/users/me -> the caller's profile, provisioned just-in-time on first contact.
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal jwt: Jwt): ProfileResponse =
        service.getOrProvision(jwt.toUserClaims()).toResponse()

    // PUT /api/users/me -> update the caller's editable fields.
    @PutMapping("/me")
    fun updateMe(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: UpdateProfileRequest,
    ): ProfileResponse =
        service.update(jwt.requireSubject(), request).toResponse()
}

// The Keycloak `sub` is the user's identity here. A token without it is malformed (the realm-import gotcha:
// a mis-exported realm can drop `sub`); guard with a 400 (IllegalArgumentException) instead of an NPE.
private fun Jwt.requireSubject(): String =
    subject ?: throw IllegalArgumentException("token is missing the subject (sub) claim")

// Maps Keycloak's standard OIDC claims onto our value type. Email can be absent on a token (scope
// dependent); we fall back to a stable, unique placeholder derived from the subject so the NOT NULL /
// UNIQUE(email) invariant always holds without inventing a real-looking address.
private fun Jwt.toUserClaims() = UserClaims(
    sub = requireSubject(),
    email = getClaimAsString("email") ?: "${requireSubject()}@no-email.eurotransit.local",
    givenName = getClaimAsString("given_name"),
    familyName = getClaimAsString("family_name"),
    preferredUsername = getClaimAsString("preferred_username"),
)
