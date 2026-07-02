package it.g03.payments

import it.g03.payments.entities.AuthorizationStatus
import it.g03.payments.events.AuthorizePaymentCommand
import it.g03.payments.events.CancelAuthorizationCommand
import it.g03.payments.events.CapturePaymentCommand
import it.g03.payments.events.PaymentCommandListeners
import it.g03.payments.events.Topics
import it.g03.payments.repository.OutboxEventRepository
import it.g03.payments.repository.PaymentAuthorizationRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Drives the @KafkaListener entry points DIRECTLY (no broker): builds the JSON command Orders would send,
 * deserialises through the real ObjectMapper, and asserts the consumer is idempotent under duplicate
 * redelivery (one hold / one capture) and that each handler appends its reply to the outbox.
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",   // we invoke the listeners directly, not via a container
    ],
)
@Import(TestcontainersConfiguration::class)
@Tag("chaos-02")
class PaymentCommandListenersTest {

    @Autowired lateinit var listeners: PaymentCommandListeners
    @Autowired lateinit var repository: PaymentAuthorizationRepository
    @Autowired lateinit var outbox: OutboxEventRepository
    @Autowired lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun cleanSlate() {
        repository.deleteAll()
        outbox.deleteAll()
    }

    private fun outboxCount(topic: String) = outbox.findAll().count { it.topic == topic }

    // Verifies a duplicated authorize-payment command holds exactly once and re-emits its reply on redelivery
    @Test
    fun `duplicate authorize-payment command holds once and re-emits the reply`() {
        val command = objectMapper.writeValueAsString(AuthorizePaymentCommand(orderId = "order-1", amountCents = 5_000))

        listeners.onAuthorizePayment(command)
        listeners.onAuthorizePayment(command)   // redelivery (at-least-once)

        assertEquals(1, repository.count(), "a redelivered command must not create a second hold")
        val auth = assertNotNull(repository.findByOrderId("order-1"))
        assertEquals(AuthorizationStatus.AUTHORIZED, auth.status)
        assertNull(auth.capturedAt, "authorize is a HOLD only")
        assertEquals(2, outboxCount(Topics.PAYMENT_RESULT), "both deliveries append a payment-result")
    }

    // Verifies a duplicated capture-payment command captures exactly once and re-emits its reply on redelivery
    @Test
    fun `duplicate capture-payment command captures once and re-emits the reply`() {
        val authorize = objectMapper.writeValueAsString(AuthorizePaymentCommand(orderId = "order-1", amountCents = 5_000))
        val capture = objectMapper.writeValueAsString(CapturePaymentCommand(orderId = "order-1"))
        listeners.onAuthorizePayment(authorize)

        listeners.onCapturePayment(capture)
        listeners.onCapturePayment(capture)   // redelivery (at-least-once)

        val auth = assertNotNull(repository.findByOrderId("order-1"))
        assertEquals(AuthorizationStatus.CAPTURED, auth.status)
        assertNotNull(auth.capturedAt, "the funds were captured exactly once")
        assertEquals(2, outboxCount(Topics.PAYMENT_CAPTURED), "both deliveries append a payment-captured")
    }

    // Verifies a cancel-authorization command releases the hold to VOIDED with no money moved and no reply
    @Test
    fun `cancel-authorization command releases the hold with no capture and no reply`() {
        val authorize = objectMapper.writeValueAsString(AuthorizePaymentCommand(orderId = "order-1", amountCents = 5_000))
        val cancel = objectMapper.writeValueAsString(CancelAuthorizationCommand(orderId = "order-1"))
        listeners.onAuthorizePayment(authorize)

        listeners.onCancelAuthorization(cancel)

        val auth = assertNotNull(repository.findByOrderId("order-1"))
        assertEquals(AuthorizationStatus.VOIDED, auth.status)
        assertNull(auth.capturedAt, "a released hold was never captured — no money moved")
        assertEquals(0, outboxCount(Topics.PAYMENT_CAPTURED), "cancel emits no payment-captured")
    }
}
