package it.g03.payments.entities

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
 * The idempotency record of "must not double-charge" (Pillar B).
 *
 * UNIQUE(order_id) means there can be AT MOST ONE authorization outcome per order: a retried/duplicated
 * authorize either finds this row (replay → return the stored outcome) or loses the insert race and has its
 * transaction rolled back (same pattern as Inventory's reservation record).
 *
 * Two-phase money path: AUTHORIZED is a HOLD (psp_reference, no captured_at) → CAPTURED (captured_at) when the
 * funds are actually taken, or → VOIDED (voided_at) when the hold is released on saga compensation; DECLINED
 * (with decline_reason) is a business outcome. Plain `class`, not `data class` — a JPA-managed entity has
 * identity, not value, semantics (root CLAUDE.md).
 */
@Entity
@Table(
    name = "payment_authorizations",
    uniqueConstraints = [UniqueConstraint(name = "uq_payment_auth_order", columnNames = ["order_id"])],
)
class PaymentAuthorization(
    @Id
    val id: UUID = UUID.randomUUID(),

    // idempotency key of the money path (1 order → 1 payment)
    @Column(name = "order_id", nullable = false, length = 64)
    val orderId: String = "",

    @Column(name = "amount_cents", nullable = false)
    val amountCents: Long = 0,

    @Column(nullable = false, length = 3)
    val currency: String = "EUR",

    @Enumerated(EnumType.STRING)   // store enum by its name (AUTHORIZED/DECLINED/CAPTURED/VOIDED), not its ordinal
    @Column(nullable = false, length = 16)
    var status: AuthorizationStatus = AuthorizationStatus.AUTHORIZED,

    @Column(name = "psp_reference", length = 64)   // PSP authorization id, set on AUTHORIZED (the hold)
    var pspReference: String? = null,

    @Column(name = "capture_reference", length = 64)   // PSP capture id, set on CAPTURED (for reconciliation)
    var captureReference: String? = null,

    @Column(name = "decline_reason", length = 64)  // why a DECLINED authorization was declined
    var declineReason: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "captured_at")   // when the held funds were captured (the second phase); null while only AUTHORIZED
    var capturedAt: Instant? = null,

    @Column(name = "voided_at")     // when the charge was voided/refunded (saga compensation)
    var voidedAt: Instant? = null,
)

enum class AuthorizationStatus {
    /** PSP authorized a HOLD on the funds (auth only). Not yet captured — capture is a separate command */
    AUTHORIZED,

    /** Terminal: PSP declined -> a BUSINESS outcome (a valid value), not a transport failure */
    DECLINED,

    /**
     * Terminal: the held funds were CAPTURED (the second phase, capture-last). Reached only from AUTHORIZED;
     * re-capturing a CAPTURED row is an idempotent replay. Fits the existing status VARCHAR(16) with no CHECK,
     * so it needs no migration (same reasoning as VOIDED below)
     */
    CAPTURED,

    /**
     * Terminal: a previously AUTHORIZED hold was released (cancelled) — the saga compensation when the inventory
     * hold expired AFTER authorize (Pillar B/C). No money moved (the hold was never captured). Reached only from
     * AUTHORIZED; re-cancelling a VOIDED row is an idempotent replay. Fits the existing status VARCHAR(16) with no
     * CHECK, so it needs no migration
     */
    VOIDED,
}
