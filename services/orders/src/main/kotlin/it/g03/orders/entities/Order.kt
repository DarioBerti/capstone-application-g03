package it.g03.orders.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * The saga aggregate. Order state machine (Pillar A/B/C):
 *
 *   PENDING -- reserve ok --> RESERVED -- authorize ok + confirm ok --> CONFIRMED
 *      |                        |
 *      |-- reserve sold-out --> REJECTED(SOLD_OUT)
 *                               |-- authorize declined --> REJECTED(PAYMENT_DECLINED) (+release)
 *                               |-- authorize UNAVAILABLE (breaker open) --> PAYMENT_PENDING -- retry ok --> CONFIRMED
 *                               |-- hold expired before confirm --> REJECTED(RESERVATION_EXPIRED) (+void)
 *
 * PENDING/RESERVED/PAYMENT_PENDING are RESUMABLE states: an at-least-once redelivery of order-placed (or the
 * recovery sweep) re-enters the saga from where it crashed/parked (every downstream step is idempotent).
 * CONFIRMED/REJECTED are terminal: replays become no-ops.
 * PAYMENT_PENDING is the circuit-breaker fallback (Pillar C): Payments is unavailable, so the order is PARKED —
 * the seats stay held within their TTL — instead of rejecting a customer whose card may be fine;
 * [OrderRecoverySweeper] retries it once the breaker closes.
 *
 * Identity: surrogate `id` UUID (app-assigned, no shared sequence). `buyer_sub` records WHO placed the
 * order (the Keycloak subject); `buyer_email` is a snapshot of the buyer's email from the JWT at placement
 * time (used as the notification recipient — no money-path call to Users). `idempotency_key` is the
 * client-supplied key that makes a retried POST replay instead of creating a duplicate.
 *
 * Plain `class`, not `data class` — a JPA-managed entity has identity, not value, semantics (root CLAUDE.md).
 */
@Entity
@Table(
    name = "orders",
    uniqueConstraints = [UniqueConstraint(name = "uq_orders_idempotency_key", columnNames = ["idempotency_key"])],
)
class Order(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "trip_code", nullable = false, length = 64)
    val tripCode: String = "",

    @Column(nullable = false)
    val qty: Int = 0,

    @Column(name = "amount_cents", nullable = false)
    val amountCents: Long = 0,

    @Column(nullable = false, length = 3)
    val currency: String = "EUR",

    @Column(name = "buyer_sub", nullable = false, length = 255)
    val buyerSub: String = "",

    @Column(name = "buyer_email", length = 320)
    val buyerEmail: String? = null,

    @Column(name = "idempotency_key", length = 64)
    val idempotencyKey: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: OrderStatus = OrderStatus.PENDING,

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason", length = 32)
    var rejectionReason: RejectionReason? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    // Optimistic-lock guard (Pillar C): the recovery sweep adds a SECOND saga driver alongside Kafka redelivery, so two handle() runs can race on the same order. @Version makes the loser of a concurrent terminal transition fail at commit (ObjectOptimisticLockingFailureException → rollback) instead of appending a duplicate order-confirmed/notification to the outbox. JPA manages the column; never set it by hand.
    @Version
    @Column(nullable = false)
    var version: Long = 0,
)

// saga states: PENDING/RESERVED/PAYMENT_PENDING are resumable, CONFIRMED/REJECTED are terminal
enum class OrderStatus { PENDING, RESERVED, PAYMENT_PENDING, CONFIRMED, REJECTED }

// mutually-exclusive reasons an order can end up REJECTED
enum class RejectionReason { SOLD_OUT, PAYMENT_DECLINED, RESERVATION_EXPIRED }
