package it.g03.catalog

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Spins up an ephemeral real PostgreSQL for tests (no H2). @ServiceConnection wires the container's
 * JDBC URL/credentials into the context, overriding the local defaults in application.yml, so Flyway
 * migrates into a real Postgres — faithful to the production datasource. Import this from tests.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
}
