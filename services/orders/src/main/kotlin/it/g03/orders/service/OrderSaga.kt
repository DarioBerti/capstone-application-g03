package it.g03.orders.service

import org.slf4j.LoggerFactory
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * THE ORCHESTRATED SAGA (Pillar A): Orders is the coordinator of a reply-driven async Kafka command/reply
 * pipeline. It emits commands (via the outbox) and reacts to replies; it no longer calls Inventory/Payments
 * over HTTP. This bean is a THIN DISPATCHER — one method per saga entry point — that delegates to the
 * @Transactional [OrderStateMachine] (which loads the order, guards on its status, transitions, and appends
 * the next command/terminal events atomically).
 *
 * The only thing handled here is the lost-@Version race: the timeout sweeper's re-drive and an in-flight
 * reply can race on the same order; the loser's commit rolls back as [ObjectOptimisticLockingFailureException]
 * and is absorbed as a benign no-op (the other driver already advanced the order — at-least-once is safe
 * because the transitions are idempotent). Any OTHER exception propagates to the Kafka error handler (bounded
 * retry -> DLT).
 */
@Component // registers this class as a Spring-managed bean
class OrderSaga(
    private val stateMachine: OrderStateMachine,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // reservation-result (per leg): RESERVED -> the leg is held; SOLD_OUT -> the leg drops out (one-way fallback)
    fun onReservationResult(orderId: UUID, tripCode: String, status: String) = dispatch(orderId) { stateMachine.onReservationResult(orderId, tripCode, status) }

    // payment-result: AUTHORIZED -> fan out confirm-seats to every reserved leg; DECLINED -> release legs + reject
    fun onPaymentResult(orderId: UUID, status: String) = dispatch(orderId) { stateMachine.onPaymentResult(orderId, status) }

    // seats-confirmed (fan-in per leg): all legs CONFIRMED -> capture-payment once; a leg EXPIRED -> compensate + reject
    fun onSeatsConfirmed(orderId: UUID, tripCode: String, status: String) = dispatch(orderId) { stateMachine.onSeatsConfirmed(orderId, tripCode, status) }

    // payment-captured: AUTHORIZED -> CONFIRMED (terminal happy end)
    fun onPaymentCaptured(orderId: UUID) = dispatch(orderId) { stateMachine.onPaymentCaptured(orderId) }

    private fun dispatch(orderId: UUID, transition: () -> Unit) {
        try {
            transition()
        } catch (e: ObjectOptimisticLockingFailureException) {
            log.info("order {} advanced concurrently (optimistic-lock race lost) — no-op", orderId)
        }
    }
}
