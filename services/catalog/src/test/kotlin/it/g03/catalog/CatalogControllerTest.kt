package it.g03.catalog

import it.g03.catalog.controller.CatalogController
import it.g03.catalog.dto.TripFilter
import it.g03.catalog.entities.Station
import it.g03.catalog.entities.Trip
import it.g03.catalog.repository.StationRepository
import it.g03.catalog.repository.TripRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest  // boot the full Spring app context to test controller -> service -> repository wiring
@Import(TestcontainersConfiguration::class)
class CatalogControllerTest {

    // Injects the controller under test
    @Autowired lateinit var controller: CatalogController
    // Injects the repositories so the test can prepare and clean the database state directly
    @Autowired lateinit var trips: TripRepository
    @Autowired lateinit var stations: StationRepository

    @BeforeEach  // runs before each test method to start from a known database state
    fun seed() {
        trips.deleteAll()
        stations.deleteAll()
        stations.saveAll(
            listOf(
                Station("TRN", "Torino Porta Nuova", "Torino", "IT"),
                Station("MIL", "Milano Centrale", "Milano", "IT"),
            ),
        )
        trips.save(
            Trip(
                code = "FR0001-20260629", carrierCode = "FR", serviceNumber = "0001",
                serviceDate = LocalDate.of(2026, 6, 29), originCode = "TRN", destinationCode = "MIL",
                departureTime = OffsetDateTime.of(2026, 6, 29, 8, 0, 0, 0, ZoneOffset.ofHours(2)),
                arrivalTime = OffsetDateTime.of(2026, 6, 29, 9, 0, 0, 0, ZoneOffset.ofHours(2)),
                priceCents = 1500,
            ),
        )
    }

    // Verifies the two "happy-path" controller endpoints: paginated list and fetch one by code
    @Test
    fun `lists and fetches trips`() {
        val page = controller.listTrips(TripFilter(), PageRequest.of(0, 20))
        assertEquals(1, page.totalElements.toInt())
        assertEquals("FR0001-20260629", page.content.single().code)
        assertEquals(1500, controller.tripByCode("FR0001-20260629").priceCents)
    }

    // Verifies the "error-path" controller endpoint: propagates not-found error for an unknown code
    @Test
    fun `unknown trip is a domain not-found error`() {
        assertFailsWith<NoSuchElementException> { controller.tripByCode("NO-SUCH-TRIP") }
    }

    // Verifies the stations typeahead: filters by name/city/code, and lists all when q is absent.
    @Test
    fun `stations search filters by name city or code`() {
        assertEquals(2, controller.listStations(null, PageRequest.of(0, 50)).totalElements.toInt())
        val milan = controller.listStations("mil", PageRequest.of(0, 50))
        assertEquals(1, milan.totalElements.toInt())
        assertEquals("MIL", milan.content.single().code)
    }
}
