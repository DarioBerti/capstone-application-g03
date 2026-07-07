package it.g03.catalog

import it.g03.catalog.entities.Station
import it.g03.catalog.repository.StationRepository
import it.g03.catalog.repository.TripRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.domain.Specification
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Asserts the V6 dedupe against the PRISTINE Flyway-seeded database: the V2/V3 demo trips are repointed from
 * the sigla station codes onto the RFI codes, and the duplicate sigla station rows are gone.
 *
 * The other DB-backed tests share one Testcontainers context and wipe the seed data in `@BeforeEach`
 * (`deleteAll`). The `test.marker` property forces a *distinct* Spring context — hence its own container with a
 * freshly migrated, untouched database — so this test observes the seeds exactly as Flyway left them. It only
 * reads; it must never mutate.
 */
@SpringBootTest(properties = ["test.marker=v6-seed", "app.kafka-listeners-enabled=false"])
@Import(TestcontainersConfiguration::class)
class SeedMigrationTest {

    @Autowired lateinit var trips: TripRepository
    @Autowired lateinit var stations: StationRepository

    @Test
    fun `V2 demo trip is repointed from sigla codes to RFI codes`() {
        val trip = trips.findByCode("FR9592-20260629")

        assertNotNull(trip, "the V2 demo trip must still exist (its code is untouched)")
        assertEquals("8300219", trip.originCode, "origin TRN remapped to Torino Porta Nuova (RFI)")
        assertEquals("8301700", trip.destinationCode, "destination MIL remapped to Milano Centrale (RFI)")
    }

    @Test
    fun `V3 round-trip demo trip is repointed too`() {
        val trip = trips.findByCode("IT8001-20260706")

        assertNotNull(trip, "the V3 round-trip demo trip must still exist")
        assertEquals("8301700", trip.originCode, "origin MIL remapped to Milano Centrale (RFI)")
        assertEquals("8308409", trip.destinationCode, "destination ROM remapped to Roma Termini (RFI)")
    }

    @Test
    fun `the duplicate sigla station rows are removed`() {
        listOf("TRN", "MIL", "ROM", "NAP").forEach { code ->
            assertFalse(stations.existsById(code), "sigla station $code must be deleted after dedupe")
        }
    }

    @Test
    fun `exactly one station is named Milano Centrale after the dedupe`() {
        val milano = stations.count(nameEquals("Milano Centrale"))

        assertEquals(1L, milano, "only the RFI Milano Centrale (8301700) should remain")
    }

    private fun nameEquals(name: String): Specification<Station> =
        Specification { root, _, cb -> cb.equal(root.get<String>("name"), name) }
}
