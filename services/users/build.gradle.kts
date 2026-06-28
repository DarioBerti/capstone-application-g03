plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.3"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.2.21"
}

group = "it.g03"
version = "0.0.1-SNAPSHOT"
description = "EuroTransit Users service — app-side user profiles keyed by the Keycloak subject; OAuth2 resource server"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// Web + persistence (Catalog owns a read-optimised store)
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	// Security: validate Keycloak-issued JWTs as an OAuth2 resource server (lazy JWKS, see application.yml)
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

	// Observability (Pillar D): health probes, Prometheus metrics, OTel tracing
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("io.micrometer:micrometer-registry-prometheus")
	implementation("io.micrometer:micrometer-tracing-bridge-otel")
	implementation("io.opentelemetry:opentelemetry-exporter-otlp")

	// Schema migrations: Flyway owns the schema, Hibernate validates. The starter pulls in Flyway core
	// AND Spring Boot 4's Flyway auto-configuration (in module spring-boot-flyway) that actually runs
	// the migrations on startup — bare flyway-core does not auto-migrate under Boot 4's modularized config.
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")

	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	// Tests run against a real PostgreSQL via Testcontainers (no H2) — versions from the Spring Boot BOM.
	// Testcontainers 2.x renamed the module artifacts (testcontainers-postgresql, not postgresql).
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
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
