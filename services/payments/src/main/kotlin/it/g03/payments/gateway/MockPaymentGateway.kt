package it.g03.payments.gateway

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Deterministic, config-driven PSP used for local dev and tests (the default provider). Amounts at/above the
 * threshold are DECLINED ("insufficient funds"); smaller amounts get a HOLD. This lets the saga's compensation
 * path be exercised on demand (order a huge qty -> decline). Failure/latency is injected at the chaos/network
 * layer, not by making this decision flaky.
 */
@Component
@ConditionalOnProperty(name = ["payments.provider"], havingValue = "mock", matchIfMissing = true)
class MockPaymentGateway(
    @Value("\${payments.decline-threshold-cents:100000}") private val declineThresholdCents: Long,
) : PaymentGateway {

    // The threshold rule is amount-only; line items are display metadata the mock ignores (PayPal uses them).
    override fun authorize(orderId: String, amountCents: Long, currency: String, items: List<LineItem>): AuthorizationResult =
        if (amountCents < declineThresholdCents) {
            AuthorizationResult.Authorized(pspReference = "AUTH-${UUID.randomUUID()}")
        } else {
            AuthorizationResult.Declined(reason = "AMOUNT_OVER_THRESHOLD")
        }

    override fun capture(orderId: String, pspReference: String): CaptureResult =
        CaptureResult(captureReference = "CAP-${UUID.randomUUID()}")

    override fun void(orderId: String, pspReference: String) = Unit
}
