package it.g03.catalog.service

import it.g03.catalog.dto.StationResponse
import it.g03.catalog.dto.TripFilter
import it.g03.catalog.dto.TripResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

/**
 * Catalog business-layer contract (Controller -> Service -> Repository). The controller depends on this
 * interface; [CatalogServiceImpl] is the single implementation.
 */
interface CatalogService {
    fun search(filter: TripFilter, pageable: Pageable): Page<TripResponse>
    fun byCode(code: String): TripResponse

    /** Paginated stations for the origin/destination pickers; an optional `q` typeahead matches name/city/code. */
    fun listStations(q: String?, pageable: Pageable): Page<StationResponse>
}
