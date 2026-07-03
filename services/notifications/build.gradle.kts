plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.3"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "it.g03"
version = "0.0.1-SNAPSHOT"
description = "EuroTransit Notifications service — fully asynchronous; must fail without failing checkout"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// Minimal web surface: Actuator probes/metrics + the SSE stream that relays saga updates to the browser
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	// The SSE endpoint is per-user, so it validates the caller's Keycloak token like the other resource servers
	// (the only authenticated surface on this otherwise Kafka-only edge service).
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

	// Fully asynchronous: Kafka consumer + coroutines. Notifications owns (almost) no state. The starter
	// pulls in spring-kafka AND Boot 4's Kafka auto-configuration (module spring-boot-kafka) — bare
	// spring-kafka does not auto-configure the Kafka beans under Boot 4's modularized config.
	implementation("org.springframework.boot:spring-boot-starter-kafka")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

	// Observability (Pillar D)
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("io.micrometer:micrometer-registry-prometheus")
	implementation("io.micrometer:micrometer-tracing-bridge-otel")
	implementation("io.opentelemetry:opentelemetry-exporter-otlp")
	// Boot 4 split tracing auto-config out of actuator into its own module: without it NO Tracer/OTLP
	// exporter bean is created and the two tracing deps above are inert (spans silently never exported).
	implementation("org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.kafka:spring-kafka-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Spring Boot's `jar` task also emits a *-plain.jar; disable it so the Dockerfile's
// `COPY build/libs/*.jar app.jar` glob matches exactly one file (the runnable bootJar).
tasks.named("jar") {
	enabled = false
}
