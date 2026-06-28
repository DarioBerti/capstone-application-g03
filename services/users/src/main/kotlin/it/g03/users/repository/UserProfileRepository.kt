package it.g03.users.repository

import it.g03.users.entities.UserProfile
import org.springframework.data.jpa.repository.JpaRepository

// JpaRepository<Entity, IdType> — the id is the Keycloak subject (String). CRUD is enough here;
// profiles are looked up by their `sub`, never listed in bulk, so no Specification executor is needed.
interface UserProfileRepository : JpaRepository<UserProfile, String>
