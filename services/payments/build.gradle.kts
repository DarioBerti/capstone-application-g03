plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.3"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.2.21"
}

group = "it.g03"
version = "0.0.1-SNAPSHOT"
description = "EuroTransit Payments service — synchronous authorization with strict idempotency (no double-charge)"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

// See docs/agent-log.md: Resilience4j starter targets Spring Boot 3; verify on Boot 4.
val resilience4jVersion = "2.3.0"

dependencies {
	// Synchronous authorize API + idempotency store (key + persisted result)
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	// Security: validate Keycloak-issued JWTs as an OAuth2 resource server (lazy JWKS, see application.yml)
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

	// Emits payment-authorized; coroutines for any async confirmation work. The starter pulls in
	// spring-kafka AND Boot 4's Kafka auto-configuration (module spring-boot-kafka) that builds the
	// KafkaTemplate bean — bare spring-kafka does not auto-configure it under Boot 4's modularized config.
	implementation("org.springframework.boot:spring-boot-starter-kafka")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

	// Resilience (Pillar C): protect the outbound call to the (mock) external PSP
	implementation("io.github.resilience4j:resilience4j-spring-boot3:$resilience4jVersion")
	implementation("io.github.resilience4j:resilience4j-kotlin:$resilience4jVersion")
	implementation("io.github.resilience4j:resilience4j-micrometer:$resilience4jVersion")

	// Observability (Pillar D)
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("io.micrometer:micrometer-registry-prometheus")
	implementation("io.micrometer:micrometer-tracing-bridge-otel")
	implementation("io.opentelemetry:opentelemetry-exporter-otlp")

	// Schema migrations (cluster profile only): Flyway owns the schema, Hibernate validates. The starter
	// pulls in Flyway core AND Spring Boot 4's Flyway auto-configuration (module spring-boot-flyway) that
	// actually runs migrations on startup — bare flyway-core does not auto-migrate under Boot 4's modules.
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")

	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.kafka:spring-kafka-test")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	// Tests run against a real PostgreSQL via Testcontainers (no H2) — versions from the Spring Boot BOM.
	// The "must not double-charge" UNIQUE(order_id) race is materially more faithful on real Postgres than H2.
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

// Spring Boot's `jar` task also emits a *-plain.jar; disable it so the Dockerfile's
// `COPY build/libs/*.jar app.jar` glob matches exactly one file (the runnable bootJar).
tasks.named("jar") {
	enabled = false
}
