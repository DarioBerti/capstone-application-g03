package it.g03.payments.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * Warm-before-ready (Pillars C/D). A freshly started JVM serves its first requests slowly: the JIT is cold and
 * the very first DB call pays the whole HikariCP connection setup (TCP + TLS + auth to the remote Postgres),
 * which alone is most of a ~1s cold-start p99. Holding READINESS down until the hot path is primed means
 * Kubernetes routes no traffic to a cold pod, so no user (and no dashboard) ever sees that cold spike on a
 * rollout or an HPA scale-up. It matters most on the canary track (orders): there the intake-ack p99 of a cold
 * scale-up pod is judged by the `canary-intake-ack-p99` analysis gate and would abort an otherwise healthy
 * release on a false signal — warm-before-ready removes that class of false abort entirely.
 *
 * [WarmupHealthIndicator] is in the `readiness` health group (application.yml) and reports DOWN until
 * [WarmupRunner] finishes priming. Priming is best-effort: any failure still flips readiness UP, because a
 * warm-up hiccup must never wedge a pod out of rotation forever — worst case the pod is only as cold as it would
 * have been without this at all.
 */
@Component("warmup")
class WarmupHealthIndicator : HealthIndicator {
    @Volatile
    private var warm = false

    fun markWarm() {
        warm = true
    }

    override fun health(): Health =
        if (warm) Health.up().build() else Health.down().withDetail("state", "warming").build()
}

@Component
class WarmupRunner(
    private val indicator: WarmupHealthIndicator,
    private val dataSource: DataSource,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun warmUp() {
        val startNanos = System.nanoTime()
        try {
            // Prime the pool + JDBC driver + JIT the DB round-trip so the first real request skips the
            // TCP+TLS+auth handshake that dominates cold latency. A trivial no-side-effect query is enough.
            repeat(WARMUP_QUERIES) {
                dataSource.connection.use { conn ->
                    conn.createStatement().use { it.execute("SELECT 1") }
                }
            }
            log.info("readiness warm-up complete in {} ms", (System.nanoTime() - startNanos) / 1_000_000)
        } catch (e: Exception) {
            log.warn("readiness warm-up failed, marking ready anyway: {}", e.message)
        } finally {
            indicator.markWarm()
        }
    }

    private companion object {
        const val WARMUP_QUERIES = 5
    }
}
