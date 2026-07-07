package it.g03.inventory.service

import it.g03.inventory.dto.AvailabilityResponse
import it.g03.inventory.dto.toResponse
import it.g03.inventory.entities.Reservation
import it.g03.inventory.entities.ReservationStatus
import it.g03.inventory.events.AvailabilityChangedEvent
import it.g03.inventory.events.ReservationResultEvent
import it.g03.inventory.events.SeatsConfirmedEvent
import it.g03.inventory.events.Topics
import it.g03.inventory.repository.ReservationRepository
import it.g03.inventory.repository.TripSeatsRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Service  // marks this as a Spring service (business-logic layer, behind a transactional proxy)
class InventoryServiceImpl(

    private val items: TripSeatsRepository,
    private val reservations: ReservationRepository,
    private val outbox: Outbox,
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
     * Why this is duplicate-proof (chaos #2/#4, see the config repo README / docs/design.md):
     * - Fast path: a previous attempt for (orderId, tripCode) exists → return its stored outcome (replay) and RE-EMIT the reply (at-least-once reply recovery). A duplicated `reserve-seats` delivery is a no-op on the seat count but still answers Orders
     * - Race path: two duplicates run concurrently, both miss the pre-check and both decrement. The UNIQUE(order_id, trip_code) insert then fails for exactly one of them, rolling back THAT transaction including its decrement: only one commit, one decrement. The thrown duplicate-key error is self-healing -> the at-least-once redelivery replays and hits the fast path
     * - Concurrency between DIFFERENT orders is decided by the conditional UPDATE (`available >= qty`): the row lock serialises them; the loser gets 0 rows = sold out
     */
    @Transactional  // dedup + decrement + insert + reply all commit (or roll back) as ONE atomic unit
    override fun reserve(orderId: String, tripCode: String, qty: Int): ReserveOutcome {
        require(qty > 0) { "qty must be positive" }

        // Fast path: a prior outcome for this (orderId, tripCode) exists -> replay it (idempotent) and re-emit the reply
        reservations.findByOrderIdAndTripCode(orderId, tripCode)?.let {
            outcomes("replayed").increment()
            appendReservationResult(it)
            log.info("reserve replay for order={} trip={} -> {}", orderId, tripCode, it.status)
            return ReserveOutcome(it, replayed = true)
        }

        // Reject unknown trips as a domain not-found (becomes a 404 at the controller / a DLT-bound poison message on the listener)
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
        appendReservationResult(reservation)
        if (reserved) appendAvailabilityChanged(tripCode) // seats moved (a reject leaves the count unchanged)
        outcomes(if (reserved) "reserved" else "rejected").increment()
        log.info("reserve order={} trip={} qty={} -> {}", orderId, tripCode, qty, status)
        return ReserveOutcome(reservation, replayed = false)
    }

    /**
     * RESERVED → CONFIRMED. Guards the confirm-vs-expiry race (design refinement #3): if the TTL elapsed
     * before the (slow) payment came back, the hold is no longer valid — we expire it here (restocking the
     * seats) and reply EXPIRED so the caller (Orders) runs its compensation instead of confirming a seat we
     * no longer hold. Idempotent: confirming a CONFIRMED reservation replays the stored outcome and re-emits the reply.
     */
    @Transactional  // state transition, any restock, and the reply commit atomically
    override fun confirm(orderId: String, tripCode: String): Reservation {
        val r = reservations.findByOrderIdAndTripCode(orderId, tripCode)
            ?: throw NoSuchElementException("no reservation for order=$orderId trip=$tripCode")
        if (r.status == ReservationStatus.RESERVED) {
            if (r.expiresAt.isBefore(Instant.now())) {
                r.status = ReservationStatus.EXPIRED
                r.releasedAt = Instant.now()
                reservations.save(r) // explicit save: restock() flushes+clears the context
                items.restock(r.tripCode, r.qty)
                appendAvailabilityChanged(r.tripCode)
                log.warn("confirm arrived after TTL for order={} -> EXPIRED (restocked)", orderId)
            } else {
                r.status = ReservationStatus.CONFIRMED
                r.confirmedAt = Instant.now()
                reservations.save(r)
            }
        }
        appendSeatsConfirmed(r)
        return r
    }

    /**
     * Saga compensation (payment failed/aborted, or a round-trip booking partially expired): RESERVED → RELEASED
     * OR CONFIRMED → RELEASED, restocking the seats. The CONFIRMED path exists for the multi-leg all-or-nothing
     * case — if one leg of a booking expires at confirm, Orders releases the sibling leg(s) it had already
     * confirmed, un-selling them. No reply — Orders is already rejecting when it sends release-seats.
     * Idempotent: releasing an already-terminal (RELEASED/EXPIRED/REJECTED) reservation is a no-op replay; the
     * additive restock happens after the status flip and is @Version-guarded against a concurrent second release.
     */
    @Transactional  // status flip + restock commit atomically
    override fun release(orderId: String, tripCode: String): Reservation {
        val r = reservations.findByOrderIdAndTripCode(orderId, tripCode)
            ?: throw NoSuchElementException("no reservation for order=$orderId trip=$tripCode")
        if (r.status == ReservationStatus.RESERVED || r.status == ReservationStatus.CONFIRMED) {
            val wasConfirmed = r.status == ReservationStatus.CONFIRMED
            r.status = ReservationStatus.RELEASED
            r.releasedAt = Instant.now()
            reservations.save(r) // explicit save: restock() flushes+clears the context
            items.restock(r.tripCode, r.qty)
            appendAvailabilityChanged(r.tripCode)
            log.info("released {} reservation for order={} trip={} (restocked {})", if (wasConfirmed) "CONFIRMED" else "RESERVED", orderId, tripCode, r.qty)
        }
        return r
    }

    /**
     * Reconciler sweep (invoked by [ReservationReconciler]): expire overdue holds and give the seats back,
     * so a crashed/never-finished saga cannot strand inventory forever. Restock only — no reply, since no order
     * is waiting on a sweep (the saga's own timeout sweeper handles a stuck order).
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
        overdue.map { it.tripCode }.distinct().forEach { appendAvailabilityChanged(it) }

        if (overdue.isNotEmpty()) {
            outcomes("expired").increment()
        }
        return overdue.size
    }

    // Read-only availability projection for the public storefront; maps the entity to a DTO so the raw entity never leaks.
    @Transactional(readOnly = true)
    override fun availability(pageable: Pageable): Page<AvailabilityResponse> =
        items.findAll(pageable).map { it.toResponse() }

    @Transactional(readOnly = true)
    override fun availabilityFor(tripCode: String): AvailabilityResponse =
        items.findById(tripCode).map { it.toResponse() }
            .orElseThrow { NoSuchElementException("unknown trip: $tripCode") }

    // Seeds read-side consumers without a booking: appends one seat-availability event per trip to the outbox,
    // in a single tx, reusing the same authoritative emit path as reserve/release. Idempotent downstream —
    // Catalog's projection is last-writer-by-event-time — so re-running only refreshes, never corrupts.
    @Transactional
    override fun republishAllAvailability(): Int {
        val codes = items.findAllTripCodes()
        codes.forEach { appendAvailabilityChanged(it) }
        log.info("republished seat-availability snapshot for {} trips", codes.size)
        return codes.size
    }

    // Reply to a reserve-seats command via the outbox (REJECTED -> SOLD_OUT, every other outcome means seats were held -> RESERVED)
    private fun appendReservationResult(r: Reservation) {
        val status = if (r.status == ReservationStatus.REJECTED) STATUS_SOLD_OUT else STATUS_RESERVED
        outbox.append(Topics.RESERVATION_RESULT, r.orderId, ReservationResultEvent(orderId = r.orderId, tripCode = r.tripCode, status = status))
    }

    // Reply to a confirm-seats command via the outbox (CONFIRMED -> seats sold, anything else means the hold lapsed -> EXPIRED)
    private fun appendSeatsConfirmed(r: Reservation) {
        val status = if (r.status == ReservationStatus.CONFIRMED) STATUS_CONFIRMED else STATUS_EXPIRED
        outbox.append(Topics.SEATS_CONFIRMED, r.orderId, SeatsConfirmedEvent(orderId = r.orderId, tripCode = r.tripCode, status = status))
    }

    // Best-effort seat-count signal for read-side consumers. Reads the row back AFTER the mutation (the
    // decrement/restock flushes+clears) so the emitted count is authoritative rather than arithmetic; keyed by
    // tripCode for per-trip ordering. Appended in the caller's tx via the same outbox as the saga replies.
    private fun appendAvailabilityChanged(tripCode: String) {
        val row = items.findById(tripCode).orElse(null) ?: return
        outbox.append(
            Topics.SEAT_AVAILABILITY,
            tripCode,
            AvailabilityChangedEvent(tripCode = tripCode, available = row.available, totalSeats = row.totalSeats),
        )
    }

    private companion object {
        const val STATUS_RESERVED = "RESERVED"
        const val STATUS_SOLD_OUT = "SOLD_OUT"
        const val STATUS_CONFIRMED = "CONFIRMED"
        const val STATUS_EXPIRED = "EXPIRED"
    }
}
