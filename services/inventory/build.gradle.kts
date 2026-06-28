plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.3"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.2.21"
}

group = "it.g03"
version = "0.0.1-SNAPSHOT"
description = "EuroTransit Inventory service — the contended resource; atomic conditional reservation (never oversell)"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// Synchronous reservation API + the transactional store where the 'never oversell' invariant lives
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	// Security: validate Keycloak-issued JWTs as an OAuth2 resource server (lazy JWKS, see application.yml)
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

	// Async events (inventory-reserved / inventory-released) + coroutines for the consumer. The starter
	// pulls in spring-kafka AND Boot 4's Kafka auto-configuration (module spring-boot-kafka) that builds the
	// KafkaTemplate bean — bare spring-kafka does not auto-configure it under Boot 4's modularized config.
	implementation("org.springframework.boot:spring-boot-starter-kafka")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

	// Observability (Pillar D)
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("io.micrometer:micrometer-registry-prometheus")
	implementation("io.micrometer:micrometer-tracing-bridge-otel")
	implementation("io.opentelemetry:opentelemetry-exporter-otlp")

	// Schema migrations (postgres profile only): Flyway owns the schema, Hibernate validates. The starter
	// pulls in Flyway core AND Spring Boot 4's Flyway auto-configuration (module spring-boot-flyway) that
	// actually runs migrations on startup — bare flyway-core does not auto-migrate under Boot 4's modules.
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")

	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("com.h2database:h2")

	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.kafka:spring-kafka-test")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
