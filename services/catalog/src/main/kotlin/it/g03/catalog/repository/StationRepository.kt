package it.g03.catalog.repository

import it.g03.catalog.entities.Station
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

// JpaRepository<Station, String> = CRUD keyed by the station code; JpaSpecificationExecutor included for
// consistency with TripRepository (paginated/filterable reads). Spring Data generates the implementation.
interface StationRepository :
    JpaRepository<Station, String>,
    JpaSpecificationExecutor<Station>
