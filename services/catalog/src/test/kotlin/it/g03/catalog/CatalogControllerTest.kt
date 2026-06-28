package it.g03.catalog

import it.g03.catalog.controller.CatalogController
import it.g03.catalog.controller.ProductFilter
import it.g03.catalog.entities.Product
import it.g03.catalog.repository.ProductRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest  // boot the full Spring app context to test controller -> service -> repository wiring
@Import(TestcontainersConfiguration::class)
class CatalogControllerTest {

    // Injects the controller under test
    @Autowired lateinit var controller: CatalogController
    // Injects the repository so the test can prepare and clean the database state directly
    @Autowired lateinit var products: ProductRepository

    @BeforeEach  // runs before each test method to start from a known database state
    fun seed() {
        products.deleteAll()
        products.save(Product("TRENO-TEST", "Test departure", 1500))
    }

    // Verifies the two "happy-path" controller endpoints: paginated list and fetch one by id
    @Test
    fun `lists and fetches products`() {
        val page = controller.list(ProductFilter(), PageRequest.of(0, 20))
        assertEquals(1, page.totalElements.toInt())
        assertEquals("TRENO-TEST", page.content.single().productId)
        assertEquals(1500, controller.byId("TRENO-TEST").priceCents)
    }

    // Verifies the "error-path" controller endpoint: propagates not-found error for an unknown id
    @Test
    fun `unknown product is a domain not-found error`() {
        assertFailsWith<NoSuchElementException> { controller.byId("NO-SUCH-TRAIN") }
    }
}
