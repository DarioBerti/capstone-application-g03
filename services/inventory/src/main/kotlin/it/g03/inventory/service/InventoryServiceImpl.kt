package it.g03.inventory.service

import it.g03.inventory.entities.Reservation
import it.g03.inventory.entities.ReservationStatus
import it.g03.inventory.events.EventPublisher
import it.g03.inventory.events.InventoryReleasedEvent
import it.g03.inventory.events.InventoryReservedEvent
import it.g03.inventory.repository.ReservationRepository
import it.g03.inventory.repository.TripSeatsRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant

@Service  // marks this as a Spring service (business-logic layer, behind a transactional proxy)
class InventoryServiceImpl(

    private val items: TripSeatsRepository,
    private val reservations: ReservationRepository,
    private val events: EventPublisher,
    private val meterRegistry: MeterRegistry,

    // reads the reservation time-to-live from configuration and injects it as a Duration
    @Value("\${inventory.reservation.ttl}") private val reservationTtl: Duration,
) : InventoryService {
    private val log = LoggerFactory.getLogger(javaClass)

    // Business metric for the money-path SLIs (Pillar D): outcome-tagged counter
    private fun outcomes(outcome: String) =
        meterRegistry.counter("inventory_reservations_total", "outcome", outcome)

    /**
     * Idempotent atomic reservation -> dedup and decrement in the SAME transaction.
     *
     * Why this is duplicate-proof (chaos #2/#4, see docs/design.md):
     * - Fast path: a previous attempt for (orderId, tripCode) exists → return its stored outcome (replay). A duplicated `order-placed` delivery is a no-op
     * - Race path: two duplicates run concurrently, both miss the pre-check and both decrement. The UNIQUE(order_id, trip_code) insert then fails for exactly one of them, rolling back THAT transaction including its decrement: only one commit, one decrement. The thrown duplicate-key error is self-healing -> the at-least-once redelivery replays and hits the fast path
     * - Concurrency between DIFFERENT orders is decided by the conditional UPDATE (`available >= qty`): the row lock serialises them; the loser gets 0 rows = sold out
     */
    @Transactional  // dedup + decrement + insert all commit (or roll back) as ONE atomic unit
    override fun reserve(orderId: String, tripCode: String, qty: Int): ReserveOutcome {
        require(qty > 0) { "qty must be positive" }

        // Fast path: a prior outcome for this (orderId, tripCode) exists -> replay it (idempotent)
        reservations.findByOrderIdAndTripCode(orderId, tripCode)?.let {
            outcomes("replayed").increment()
            log.info("reserve replay for order={} trip={} -> {}", orderId, tripCode, it.status)
            return ReserveOutcome(it, replayed = true)
        }

        // Reject unknown trips as a domain not-found (becomes a 404 at the controller)
        if (!items.existsById(tripCode)) {
            throw NoSuchElementException("unknown trip: $tripCode")
        }

        // Atomic conditional decrement: 1 = seats taken, 0 = sold out (never oversell)
        val reserved = items.tryDecrement(tripCode, qty) == 1
        val status = if (reserved) ReservationStatus.RESERVED else ReservationStatus.REJECTED
        val reservation = reservations.save(
            Reservation(
                orderId = orderId,
                tripCode = tripCode,
                qty = qty,
                status = status,
                createdAt = Instant.now(),
                expiresAt = Instant.now().plus(reservationTtl),
            ),
        )
        if (reserved) {
            publishAfterCommit(
                "inventory-reserved",
                orderId,
                InventoryReservedEvent(orderId = orderId, tripCode = tripCode, qty = qty),
            )
        }
        outcomes(if (reserved) "reserved" else "rejected").increment()
        log.info("reserve order={} trip={} qty={} -> {}", orderId, tripCode, qty, status)
        return ReserveOutcome(reservation, replayed = false)
    }

    /**
     * RESERVED → CONFIRMED. Guards the confirm-vs-expiry race (design refinement #3): if the TTL elapsed
     * before the (slow) payment came back, the hold is no longer valid — we expire it here (restocking the
     * seats) and return EXPIRED so the caller (Orders) runs its compensation instead of confirming a seat we
     * no longer hold. Idempotent: confirming a CONFIRMED reservation replays the stored outcome.
     */
    @Transactional  // state transition (and any restock) commit atomically
    override fun confirm(orderId: String, tripCode: String): Reservation {
        val r = reservations.findByOrderIdAndTripCode(orderId, tripCode)
            ?: throw NoSuchElementException("no reservation for order=$orderId trip=$tripCode")
        if (r.status == ReservationStatus.RESERVED) {
            if (r.expiresAt.isBefore(Instant.now())) {
                r.status = ReservationStatus.EXPIRED
                r.releasedAt = Instant.now()
                reservations.save(r) // explicit save: restock() flushes+clears the context
                items.restock(r.tripCode, r.qty)
                publishReleasedAfterCommit(r, reason = "expired")
                log.warn("confirm arrived after TTL for order={} -> EXPIRED (restocked)", orderId)
            } else {
                r.status = ReservationStatus.CONFIRMED
                r.confirmedAt = Instant.now()
                reservations.save(r)
            }
        }
        return r
    }

    /**
     * Saga compensation (payment failed/aborted): RESERVED → RELEASED + restock.
     * Idempotent: releasing an already-terminal reservation replays the stored outcome.
     */
    @Transactional  // status flip + restock commit atomically
    override fun release(orderId: String, tripCode: String): Reservation {
        val r = reservations.findByOrderIdAndTripCode(orderId, tripCode)
            ?: throw NoSuchElementException("no reservation for order=$orderId trip=$tripCode")
        if (r.status == ReservationStatus.RESERVED) {
            r.status = ReservationStatus.RELEASED
            r.releasedAt = Instant.now()
            reservations.save(r) // explicit save: restock() flushes+clears the context
            items.restock(r.tripCode, r.qty)
            publishReleasedAfterCommit(r, reason = "released")
            log.info("released reservation for order={} trip={} (restocked {})", orderId, tripCode, r.qty)
        }
        return r
    }

    /**
     * Reconciler sweep (invoked by [ReservationReconciler]): expire overdue holds and give the seats back,
     * so a crashed/never-finished saga cannot strand inventory forever.
     */
    @Transactional  // all sweep transitions and restocks commit atomically
    override fun expireOverdue(now: Instant): Int {
        val overdue = reservations.findByStatusAndExpiresAtBefore(ReservationStatus.RESERVED, now)
        // Persist ALL status transitions before the first restock: restock() clears the persistence context, which would detach the remaining entities mid-loop
        val releasedAt = Instant.now()
        overdue.forEach {
            it.status = ReservationStatus.EXPIRED
            it.releasedAt = releasedAt
        }
        reservations.saveAll(overdue)

        overdue.forEach { items.restock(it.tripCode, it.qty) }
        overdue.forEach { publishReleasedAfterCommit(it, reason = "expired") }

        if (overdue.isNotEmpty()) {
            outcomes("expired").increment()
        }
        return overdue.size
    }

    // Re-read the (now-terminal) reservation after the controller lost an optimistic-lock race to the reconciler.
    @Transactional(readOnly = true)
    override fun find(orderId: String, tripCode: String): Reservation =
        reservations.findByOrderIdAndTripCode(orderId, tripCode)
            ?: throw NoSuchElementException("no reservation for order=$orderId trip=$tripCode")

    // Convenience wrapper: emit the inventory-released event for a reservation (released | expired)
    private fun publishReleasedAfterCommit(r: Reservation, reason: String) =
        publishAfterCommit(
            "inventory-released",
            r.orderId,
            InventoryReleasedEvent(orderId = r.orderId, tripCode = r.tripCode, qty = r.qty, reason = reason),
        )

    /**
     * Publish only AFTER the surrounding transaction commits: publishing inside it could announce a state
     * change that then rolls back (ghost event). Best-effort by design -> these events are informational
     * (see EventPublishing.kt).
     */
    private fun publishAfterCommit(topic: String, key: String, payload: Any) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = events.publish(topic, key, payload)
            },
        )
    }
}
