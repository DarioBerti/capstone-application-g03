package it.g03.orders

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * §7.5 proof — "log strutturati (con traceId) per il perché". Proves the BEHAVIOUR (not "the config key is set"):
 * with Spring Boot 4's native structured logging engaged, a log line is valid ECS JSON AND carries the trace id
 * sitting in the MDC — i.e. the log<->trace correlation that lets you jump from a metric/alert to the exact
 * request's logs on the money path (deck 10, slide 9).
 *
 * Why MDC directly: at runtime the micrometer-tracing bridge puts `traceId`/`spanId` into the SLF4J MDC whenever a
 * span is active (that part is framework-guaranteed and is what you SEE live in `kubectl logs`, §6). The part that
 * is config-dependent — and therefore the part worth a build-breaking test — is that the active structured
 * formatter actually EMITS those MDC fields into the JSON. So the test seeds the MDC exactly as the bridge would
 * and asserts the formatter carries the value through. `logging.structured.format.console=ecs` is set inline so the
 * test exercises the SAME native formatter `application-postgres.yml` turns on in-cluster, profile-independently
 * (orders tests boot on H2, no `postgres` profile needed). No `logback-spring.xml`, no extra dependency.
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",       // no broker in tests
        "app.kafka-listeners-enabled=false",  // no listener (no broker)
        "app.outbox.enabled=false",           // no relay racing the assertions
        "app.recovery.enabled=false",         // no recovery sweep racing the assertions
        // engage the in-cluster ECS console formatter here, profile-independent
        "logging.structured.format.console=ecs",
    ],
)
@ExtendWith(OutputCaptureExtension::class)
class OrdersStructuredLoggingTest {

    private val log = LoggerFactory.getLogger(javaClass)

    @Test
    fun `in-cluster console logs are ECS JSON carrying the trace id from the MDC`(output: CapturedOutput) {
        // the keys micrometer-tracing populates for an active span; seeded explicitly for determinism
        val traceId = "0af7651916cd43dd8448eb211c80319c"
        val spanId = "b7ad6b7169203331"
        MDC.put("traceId", traceId)
        MDC.put("spanId", spanId)
        try {
            log.info("PROBE_STRUCTURED_LOGGING marker")
        } finally {
            MDC.remove("traceId")
            MDC.remove("spanId")
        }

        // isolate our probe line from the (also-JSON) startup/framework output
        val line = output.all.lineSequence().firstOrNull { it.contains("PROBE_STRUCTURED_LOGGING") }
        assertNotNull(line, "probe log line was not captured on the console")

        // 1) structured format engaged: the console line is a valid JSON object (ECS), not the plain pattern
        val node = JsonMapper.builder().build().readTree(line)
        assertTrue(node.isObject, "log line must be a JSON object (native structured logging did not engage)")

        // 2) log<->trace correlation: the trace id from the MDC is carried into the line (ECS field name is
        // version-specific — trace.id / traceId — so we assert on the VALUE, robust to the key mapping)
        assertTrue(
            line.contains(traceId),
            "structured log line must carry the MDC trace id ($traceId) so logs correlate to the trace",
        )
    }
}
