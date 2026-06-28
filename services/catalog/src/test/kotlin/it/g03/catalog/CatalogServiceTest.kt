package it.g03.catalog

import it.g03.catalog.dto.ProductFilter
import it.g03.catalog.entities.Product
import it.g03.catalog.repository.ProductRepository
import it.g03.catalog.service.CatalogService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Behaviour of the service layer: pagination + Specification filtering, against real PostgreSQL.
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CatalogServiceTest {

    @Autowired lateinit var service: CatalogService
    @Autowired lateinit var products: ProductRepository

    @BeforeEach
    fun seed() {
        products.deleteAll()
        products.saveAll(
            listOf(
                Product("TRENO-TO-MI", "Torino -> Milano", 1500),
                Product("TRENO-TO-RM", "Torino -> Roma", 4900),
                Product("TRENO-MI-NA", "Milano -> Napoli", 6900),
            ),
        )
    }

    @Test
    fun `search paginates results`() {
        // Arrange
        val firstPage = PageRequest.of(0, 2)

        // Act
        val page = service.search(ProductFilter(), firstPage)

        // Assert
        assertEquals(3, page.totalElements.toInt())
        assertEquals(2, page.content.size)
        assertEquals(2, page.totalPages)
    }

    @Test
    fun `search filters by name case-insensitively`() {
        val page = service.search(ProductFilter(name = "torino"), PageRequest.of(0, 20))

        assertEquals(2, page.totalElements.toInt())
        assertTrue(page.content.all { it.name.contains("Torino") })
    }

    @Test
    fun `search filters by max price`() {
        val page = service.search(ProductFilter(maxPriceCents = 5000), PageRequest.of(0, 20))

        assertEquals(2, page.totalElements.toInt())
        assertTrue(page.content.all { it.priceCents <= 5000 })
    }

    @Test
    fun `byId throws for an unknown id`() {
        assertFailsWith<NoSuchElementException> { service.byId("NO-SUCH-TRAIN") }
    }
}
