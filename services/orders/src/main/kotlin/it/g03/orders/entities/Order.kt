package it.g03.orders.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * The saga aggregate. Order state machine (Pillar A/B):
 *
 *   PENDING -- reserve ok --> RESERVED -- authorize ok + confirm ok --> CONFIRMED
 *      |                        |
 *      |-- reserve sold-out --> REJECTED(SOLD_OUT)
 *                               |-- authorize declined --> REJECTED(PAYMENT_DECLINED) (+release)
 *                               |-- hold expired before confirm --> REJECTED(RESERVATION_EXPIRED) (+void)
 *
 * PENDING/RESERVED are RESUMABLE states; CONFIRMED/REJECTED are terminal (replays become no-ops).
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
)

// saga states: PENDING/RESERVED are resumable, CONFIRMED/REJECTED are terminal
enum class OrderStatus { PENDING, RESERVED, CONFIRMED, REJECTED }

// mutually-exclusive reasons an order can end up REJECTED
enum class RejectionReason { SOLD_OUT, PAYMENT_DECLINED, RESERVATION_EXPIRED }
