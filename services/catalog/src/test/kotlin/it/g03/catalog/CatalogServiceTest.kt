package it.g03.catalog

import it.g03.catalog.dto.TripFilter
import it.g03.catalog.entities.Station
import it.g03.catalog.entities.Trip
import it.g03.catalog.repository.StationRepository
import it.g03.catalog.repository.TripRepository
import it.g03.catalog.service.CatalogService
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
import kotlin.test.assertTrue

// Behaviour of the service layer: pagination + Specification filtering + station resolution, against real PostgreSQL.
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CatalogServiceTest {

    @Autowired lateinit var service: CatalogService
    @Autowired lateinit var trips: TripRepository
    @Autowired lateinit var stations: StationRepository

    private val date: LocalDate = LocalDate.of(2026, 6, 29)
    private fun at(hour: Int) = OffsetDateTime.of(2026, 6, 29, hour, 0, 0, 0, ZoneOffset.ofHours(2))

    @BeforeEach
    fun seed() {
        // FK order: trips reference stations, so clear trips first, then (re)seed stations then trips.
        trips.deleteAll()
        stations.deleteAll()
        stations.saveAll(
            listOf(
                Station("TRN", "Torino Porta Nuova", "Torino", "IT"),
                Station("MIL", "Milano Centrale", "Milano", "IT"),
                Station("ROM", "Roma Termini", "Roma", "IT"),
                Station("NAP", "Napoli Centrale", "Napoli", "IT"),
            ),
        )
        trips.saveAll(
            listOf(
                Trip(code = "FR9592-20260629", carrierCode = "FR", serviceNumber = "9592", serviceDate = date,
                    originCode = "TRN", destinationCode = "MIL", departureTime = at(8), arrivalTime = at(9), priceCents = 1500),
                Trip(code = "FR9612-20260629", carrierCode = "FR", serviceNumber = "9612", serviceDate = date,
                    originCode = "TRN", destinationCode = "ROM", departureTime = at(9), arrivalTime = at(13), priceCents = 4900),
                Trip(code = "IC1015-20260629", carrierCode = "IC", serviceNumber = "1015", serviceDate = date,
                    originCode = "MIL", destinationCode = "NAP", departureTime = at(10), arrivalTime = at(15), priceCents = 6900),
            ),
        )
    }

    @Test
    fun `search paginates results`() {
        // Arrange
        val firstPage = PageRequest.of(0, 2)

        // Act
        val page = service.search(TripFilter(), firstPage)

        // Assert
        assertEquals(3, page.totalElements.toInt())
        assertEquals(2, page.content.size)
        assertEquals(2, page.totalPages)
    }

    @Test
    fun `search filters by origin station`() {
        val page = service.search(TripFilter(origin = "TRN"), PageRequest.of(0, 20))

        assertEquals(2, page.totalElements.toInt())
        assertTrue(page.content.all { it.origin.code == "TRN" })
    }

    @Test
    fun `search filters by carrier`() {
        val page = service.search(TripFilter(carrierCode = "IC"), PageRequest.of(0, 20))

        assertEquals(1, page.totalElements.toInt())
        assertTrue(page.content.all { it.carrierCode == "IC" })
    }

    @Test
    fun `search filters by service date`() {
        val page = service.search(TripFilter(serviceDate = date), PageRequest.of(0, 20))

        assertEquals(3, page.totalElements.toInt())
    }

    @Test
    fun `search filters by max price`() {
        val page = service.search(TripFilter(maxPriceCents = 5000), PageRequest.of(0, 20))

        assertEquals(2, page.totalElements.toInt())
        assertTrue(page.content.all { it.priceCents <= 5000 })
    }

    @Test
    fun `byCode returns the trip with resolved stations`() {
        val trip = service.byCode("IC1015-20260629")

        assertEquals("IC1015-20260629", trip.code)
        assertEquals("Milano", trip.origin.city)
        assertEquals("NAP", trip.destination.code)
    }

    @Test
    fun `byCode throws for an unknown code`() {
        assertFailsWith<NoSuchElementException> { service.byCode("NO-SUCH-TRIP") }
    }
}
