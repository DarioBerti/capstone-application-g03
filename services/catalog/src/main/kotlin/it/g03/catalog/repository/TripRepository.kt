package it.g03.catalog.repository

import it.g03.catalog.entities.Trip
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID

// JpaRepository<Trip, UUID> = CRUD keyed by the surrogate UUID id; JpaSpecificationExecutor adds
// findAll(Specification, Pageable) so list reads are paginated and filterable via composable Specifications.
// findByCode looks a trip up by its public business key (the cross-service vocabulary, e.g. FR9592-20260629).
interface TripRepository :
    JpaRepository<Trip, UUID>,
    JpaSpecificationExecutor<Trip> {

    fun findByCode(code: String): Trip?
}
