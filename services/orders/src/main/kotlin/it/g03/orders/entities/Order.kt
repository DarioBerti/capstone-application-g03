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
 * The booking aggregate. A booking is one journey — an OUTBOUND leg (required) plus an optional RETURN leg (see
 * [OrderLeg]); the legs, not the order, carry trip/qty. Two-phase, human-gated async Kafka state machine:
 *
 *   DRAFT --add first leg--> HELD --pay (in window)--> PAYING --payment AUTHORIZED--> AUTHORIZED --captured--> CONFIRMED
 *     (empty)  (reserve-on-add, expiresAt stamped)         |                               |
 *                                                          |-- DECLINED --> REJECTED(PAYMENT_DECLINED)
 *                                                                                          |-- a leg EXPIRED --> REJECTED(RESERVATION_EXPIRED)
 *   HELD/PAYING --checkout window elapses--> REJECTED(EXPIRED_NO_PAYMENT | SERVICE_UNAVAILABLE)
 *
 * The hold window is BACKEND-authoritative: `expiresAt` is stamped when the first leg is added, re-checked
 * server-side at pay, and enforced by CheckoutExpirySweeper. `paymentInitiatedAt` (stamped at pay) is the
 * dual-cause key at expiry: null = the user never paid (EXPIRED_NO_PAYMENT); non-null = pay was accepted but the
 * pipeline stalled (SERVICE_UNAVAILABLE).
 *
 * PAYING/AUTHORIZED are the RESUMABLE post-pay saga states re-driven by [OrderTimeoutSweeper] on a lost reply
 * (idempotent commands make a re-send safe); a HELD booking awaiting the USER is not "stuck" and is never
 * re-driven — only the checkout window expires it. CONFIRMED/REJECTED are terminal.
 *
 * `amount_cents` is a derived cache (Σ held legs), recomputed as legs change and at pay; 0 for an empty DRAFT.
 * Identity: surrogate `id` UUID. `buyer_sub`/`buyer_email` snapshot WHO placed the order (Keycloak sub + email
 * from the JWT). `idempotency_key` makes a retried create replay instead of duplicating.
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

    // Derived cache of the booking total: Σ (qty × unitPriceCents) over the HELD legs; 0 while the order is an empty DRAFT
    @Column(name = "amount_cents", nullable = false)
    var amountCents: Long = 0,

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
    var status: OrderStatus = OrderStatus.DRAFT,

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason", length = 32)
    var rejectionReason: RejectionReason? = null,

    // Hold-window deadline: stamped now+window when the FIRST leg is added; null for an empty DRAFT. Backend owns expiry.
    @Column(name = "expires_at")
    var expiresAt: Instant? = null,

    // When the user triggered pay (authorize-payment emitted); the dual-cause key at window expiry. Null until pay.
    @Column(name = "payment_initiated_at")
    var paymentInitiatedAt: Instant? = null,

    // The PayPal order the browser created + approved via Card Fields at pay. Persisted so a timeout-sweeper
    // re-drive of authorize-payment re-sends the SAME id (the async authorize acts on this approved order).
    // Null for the mock/legacy server-side-card path.
    @Column(name = "paypal_order_id", length = 64)
    var payPalOrderId: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    // Optimistic-lock guard (Pillar C): the timeout/expiry sweepers add a SECOND driver alongside reply redelivery, so a re-drive and an in-flight reply can race on the same order. @Version makes the loser of a concurrent transition fail at commit (ObjectOptimisticLockingFailureException → rollback) instead of duplicating outbox events. JPA manages the column; never set it by hand.
    @Version
    @Column(nullable = false)
    var version: Long = 0,
)

// Booking states: DRAFT (empty) + HELD (legs reserved, awaiting the user) are pre-pay; PAYING/AUTHORIZED are the
// resumable post-pay saga states; CONFIRMED/REJECTED are terminal.
enum class OrderStatus { DRAFT, HELD, PAYING, AUTHORIZED, CONFIRMED, REJECTED }

// mutually-exclusive reasons an order can end up REJECTED
enum class RejectionReason {
    SOLD_OUT,            // the outbound leg could not be held at reserve time (a sold-out RETURN is a one-way fallback, not a rejection)
    PAYMENT_DECLINED,    // the PSP declined the charge
    RESERVATION_EXPIRED, // a held leg's hold lapsed before confirm (mid-saga)
    EXPIRED_NO_PAYMENT,  // the checkout window elapsed and the user never paid (user inaction)
    SERVICE_UNAVAILABLE, // pay was accepted but the pipeline stayed stuck until the window elapsed (system fault)
}
