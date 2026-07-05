package it.g03.catalog

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The catalog is the pre-login storefront window: browsing trips and stations must be public (no token),
 * while a token is still accepted when present. Verifies the public-GET rule in SecurityConfig.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CatalogSecurityTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `trips are publicly readable without a token`() {
        mockMvc.perform(get("/api/catalog/trips")).andExpect(status().isOk)
    }

    @Test
    fun `stations are publicly readable without a token`() {
        mockMvc.perform(get("/api/catalog/stations")).andExpect(status().isOk)
    }

    @Test
    fun `a valid token is still accepted on a public read`() {
        mockMvc.perform(get("/api/catalog/trips").with(jwt().jwt { it.subject("kc-sub-1") }))
            .andExpect(status().isOk)
    }
}
