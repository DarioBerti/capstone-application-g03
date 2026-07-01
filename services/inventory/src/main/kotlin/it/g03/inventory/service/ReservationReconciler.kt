package it.g03.inventory.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Periodically expires reservations that have gone past their deadline. Without this, a saga that crashes after reserve but before confirm/release would keep seats locked forever!
 *
 * Async lifecycle, kept simple and safe:
 * - the [CoroutineScope] uses SupervisorJob, so one failed sweep does not stop the loop;
 * - on shutdown, Spring calls [destroy] and "scope.cancel()" stops the next "delay", so the loop ends cleanly and no task is left running. If a sweep is already running, it finishes, then the loop stops at the next suspension point
 */

@Component  // auto-detected by component scanning by Spring
class ReservationReconciler(
    private val service: InventoryService,
    @Value("\${inventory.reservation.sweep-interval}") private val sweepInterval: Duration,
    // Property-gate (mirror of the orders timeout sweeper's enabled flag): lets @SpringBootTest turn the
    // background sweep OFF so it never races the assertions (Fase 2 #5.5). Default true in production
    @Value("\${inventory.reservation.enabled:true}") private val enabled: Boolean,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    // Dispatchers.IO: the sweep does BLOCKING work (JPA via the @Transactional service), which belongs on the elastic
    // IO pool, not the CPU-bound Default pool (deck 06 blocking-vs-suspending). Matches OrderRecoverySweeper (orders)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // starts the sweep loop once the app context is fully up (not before beans are ready); skipped entirely when disabled
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!enabled) {
            log.info("reservation reconciler disabled (inventory.reservation.enabled=false)")
            return
        }
        scope.launch {
            log.info("reservation reconciler started (sweep every {})", sweepInterval)
            while (isActive) {
                delay(sweepInterval.toMillis())
                try {
                    val expired = service.expireOverdue(Instant.now())
                    if (expired > 0) log.info("expired {} overdue reservations (seats restocked)", expired)
                } catch (e: Exception) {
                    // Supervisor semantics: log and keep sweeping; transient DB errors must not kill the reconciler. Note: the only suspension point (delay) sits OUTSIDE this try, so a CancellationException from cooperative shutdown is never swallowed here — it ends the loop as intended
                    log.warn("reservation sweep failed, will retry: {}", e.message)
                }
            }
        }
    }

    // Called by Spring on context shutdown (SIGTERM): cancels the scope for a clean, cooperative stop
    override fun destroy() {
        log.info("cancelling reservation reconciler (cooperative shutdown)")
        scope.cancel()
    }
}
