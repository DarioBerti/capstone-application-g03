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
    fun listStations(pageable: Pageable): Page<StationResponse>
}
