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
	// Minimal web surface: only to expose Actuator probes/metrics. No business REST API
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

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
