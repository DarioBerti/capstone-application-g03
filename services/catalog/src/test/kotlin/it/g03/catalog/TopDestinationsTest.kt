package it.g03.catalog

import it.g03.catalog.controller.CatalogController
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
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behaviour of the "popular destinations" aggregation (GET /api/catalog/destinations/top) against real
 * PostgreSQL: per-destination trip count + cheapest-from price, future-only, origin filter, ordering, limit
 * clamping, and the public cache header. Seeds a controlled dataset through the repositories.
 */
@SpringBootTest(properties = ["app.kafka-listeners-enabled=false"]) // no broker in tests -> keep the availability listener off
@Import(TestcontainersConfiguration::class)
class TopDestinationsTest {

    @Autowired lateinit var service: CatalogService
    @Autowired lateinit var controller: CatalogController
    @Autowired lateinit var trips: TripRepository
    @Autowired lateinit var stations: StationRepository

    private val today: LocalDate = LocalDate.now()
    private val now: OffsetDateTime = OffsetDateTime.now()

    @BeforeEach
    fun seed() {
        // FK order: trips reference stations, so clear trips first, then (re)seed stations then trips.
        trips.deleteAll()
        stations.deleteAll()
        stations.saveAll(
            listOf(
                Station("TOR", "Torino Porta Nuova", "Torino", "IT"),
                Station("MOR", "Milano Origin", "Milano", "IT"),
                Station("ROMA", "Roma Termini", "Roma", "IT"),
                Station("MILA", "Milano Centrale", "Milano", "IT"),
                Station("NAPO", "Napoli Centrale", "Napoli", "IT"),
            ),
        )
        trips.saveAll(
            listOf(
                // -> ROMA: 3 future departures, cheapest 4550 (one leaves from MOR, the rest from TOR)
                trip("TRIP-R1", "TOR", "ROMA", now.plusDays(1), 5000),
                trip("TRIP-R2", "TOR", "ROMA", now.plusDays(2), 4550),
                trip("TRIP-R3", "MOR", "ROMA", now.plusDays(1), 6000),
                // -> MILA: 2 future departures, cheapest 2000
                trip("TRIP-M1", "TOR", "MILA", now.plusDays(1), 3000),
                trip("TRIP-M2", "TOR", "MILA", now.plusDays(2), 2000),
                // -> NAPO: 1 future departure
                trip("TRIP-N1", "TOR", "NAPO", now.plusDays(1), 7000),
                // past departure to ROMA: must be ignored (would otherwise be the cheapest + bump the count)
                trip("TRIP-PAST", "TOR", "ROMA", now.minusDays(1), 100),
            ),
        )
    }

    @Test
    fun `aggregates trip count and cheapest-from price per destination`() {
        val top = service.topDestinations(origin = null, limit = 10)

        assertEquals(3, top.size, "three distinct future destinations")
        val roma = top.single { it.code == "ROMA" }
        assertEquals("Roma Termini", roma.name)
        assertEquals("Roma", roma.city)
        assertEquals("IT", roma.country)
        assertEquals("EUR", roma.currency)
        assertEquals(3L, roma.tripCount)
        assertEquals(4550L, roma.fromPriceCents)

        val napo = top.single { it.code == "NAPO" }
        assertEquals(1L, napo.tripCount)
        assertEquals(7000L, napo.fromPriceCents)
    }

    @Test
    fun `excludes past departures from the count and the cheapest price`() {
        val roma = service.topDestinations(origin = null, limit = 10).single { it.code == "ROMA" }

        // The past 100-cent departure would make count 4 and price 100 if it were counted.
        assertEquals(3L, roma.tripCount)
        assertEquals(4550L, roma.fromPriceCents)
    }

    @Test
    fun `filters by origin station`() {
        val top = service.topDestinations(origin = "MOR", limit = 10)

        assertEquals(1, top.size, "only the single MOR -> ROMA departure qualifies")
        val roma = top.single()
        assertEquals("ROMA", roma.code)
        assertEquals(1L, roma.tripCount)
        assertEquals(6000L, roma.fromPriceCents)
    }

    @Test
    fun `orders by trip count descending and respects the limit`() {
        val top = service.topDestinations(origin = null, limit = 2)

        assertEquals(listOf("ROMA", "MILA"), top.map { it.code }, "most-served first; NAPO dropped by limit")
    }

    @Test
    fun `breaks a trip-count tie by cheapest price ascending`() {
        // From TOR: ROMA and MILA both have 2 future departures; MILA is cheaper (2000 < 4550) so it wins.
        val top = service.topDestinations(origin = "TOR", limit = 10)

        assertEquals(listOf("MILA", "ROMA", "NAPO"), top.map { it.code })
    }

    @Test
    fun `clamps the limit into the range 1 to 24`() {
        assertEquals(1, service.topDestinations(origin = null, limit = 0).size, "limit 0 clamps up to 1")
        assertEquals(3, service.topDestinations(origin = null, limit = 1000).size, "limit clamps down to 24")
    }

    @Test
    fun `top destinations are served with a public 10-minute cache header`() {
        val response = controller.topDestinations(origin = null, limit = 6)

        assertEquals(200, response.statusCode.value())
        val cacheControl = response.headers.cacheControl ?: error("expected a Cache-Control header")
        assertTrue(cacheControl.contains("max-age=600"), "10-minute max-age: was '$cacheControl'")
        assertTrue(cacheControl.contains("public"), "public cache: was '$cacheControl'")
        assertEquals(3, response.body?.size)
    }

    private fun trip(code: String, origin: String, destination: String, departure: OffsetDateTime, priceCents: Long) =
        Trip(
            code = code,
            carrierCode = "FR",
            serviceNumber = code.takeLast(4),
            serviceDate = today,
            originCode = origin,
            destinationCode = destination,
            departureTime = departure,
            arrivalTime = departure.plusHours(1),
            priceCents = priceCents,
        )
}
