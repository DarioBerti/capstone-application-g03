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
 * The saga aggregate. Reply-driven async Kafka state machine (Pillar A/B/C):
 *
 *   PENDING --reservation RESERVED--> RESERVED --payment AUTHORIZED--> AUTHORIZED --payment-captured--> CONFIRMED
 *      |                                  |                                |
 *      |-- reservation SOLD_OUT --> REJECTED(SOLD_OUT)                     |
 *                                         |-- payment DECLINED --> REJECTED(PAYMENT_DECLINED) (+release-seats)
 *                                                                          |-- seats EXPIRED --> REJECTED(RESERVATION_EXPIRED) (+cancel-authorization)
 *
 * Each edge is taken by a reply from Inventory/Payments; the order EMITS the next command (via the outbox) and
 * waits. PENDING = "reserve-seats sent, awaiting reservation-result"; AUTHORIZED additionally covers
 * "confirm-seats sent, awaiting seats-confirmed" then "capture-payment sent, awaiting payment-captured" — the
 * capture-last design, so a hold that expires before confirm only cancels the funds hold (no capture, no refund).
 *
 * PENDING/RESERVED/AUTHORIZED are RESUMABLE states: a lost reply is re-driven by [OrderTimeoutSweeper], which
 * re-emits the command for the current state (every command is idempotent downstream). CONFIRMED/REJECTED are
 * terminal: a redelivered reply on a terminal/advanced order is a no-op (the per-transition status guard).
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

    // Optimistic-lock guard (Pillar C): the timeout sweeper adds a SECOND saga driver alongside reply redelivery, so a re-drive and an in-flight reply can race on the same order. @Version makes the loser of a concurrent terminal transition fail at commit (ObjectOptimisticLockingFailureException → rollback) instead of appending a duplicate order-confirmed/notification to the outbox. JPA manages the column; never set it by hand.
    @Version
    @Column(nullable = false)
    var version: Long = 0,
)

// saga states: PENDING/RESERVED/AUTHORIZED are resumable, CONFIRMED/REJECTED are terminal
enum class OrderStatus { PENDING, RESERVED, AUTHORIZED, CONFIRMED, REJECTED }

// mutually-exclusive reasons an order can end up REJECTED
enum class RejectionReason { SOLD_OUT, PAYMENT_DECLINED, RESERVATION_EXPIRED }
