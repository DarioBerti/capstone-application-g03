package it.g03.users.service

import it.g03.users.dto.UpdateProfileRequest
import it.g03.users.dto.UserClaims
import it.g03.users.entities.UserProfile

/**
 * User-profile business-layer contract (Controller -> Service -> Repository). The controller depends on
 * this interface; [UserProfileServiceImpl] is the single implementation.
 */
interface UserProfileService {
    fun getOrProvision(claims: UserClaims): UserProfile
    fun update(sub: String, request: UpdateProfileRequest): UserProfile
}
