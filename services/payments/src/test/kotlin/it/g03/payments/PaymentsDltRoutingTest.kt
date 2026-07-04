package it.g03.payments

import it.g03.payments.events.Topics
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import java.time.Duration
import kotlin.test.assertNotNull

/**
 * Tier-2 integration proof of the DEAD-LETTER routing (Pillar C) that the direct-invocation listener tests
 * cannot exercise: it stands up a REAL broker (embedded Kafka) and the REAL @KafkaListener container + the
 * [KafkaErrorHandler]'s DefaultErrorHandler/DeadLetterPublishingRecoverer, produces a POISON message to
 * `authorize-payment`, and asserts it is parked on `payments-dlt` (bounded retry, then recover — the partition
 * is never blocked forever). This is the one path that only holds end-to-end through a broker.
 *
 * Poison = a malformed JSON payload: the listener's objectMapper.readValue throws, the record is retried the
 * bounded number of times, then the recoverer publishes it to the DLT. Explicit String (de)serializers keep the
 * test independent of Boot's default and faithful to the String payloads the pipeline uses.
 */
@SpringBootTest(
    properties = [
        "app.kafka-listeners-enabled=true",   // the WHOLE point: the real listener container must run
        "events.publish.enabled=false",       // no outbox relay needed; the poison never yields a reply
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
    ],
)
@EmbeddedKafka(partitions = 1, topics = [Topics.AUTHORIZE_PAYMENT, Topics.PAYMENTS_DLT])
@Import(TestcontainersConfiguration::class)
@Tag("chaos-04") // DLT routing is the "poison/exhausted message is parked, partition unblocked" resilience proof
class PaymentsDltRoutingTest {

    @Autowired lateinit var broker: EmbeddedKafkaBroker

    private var producer: org.apache.kafka.clients.producer.Producer<String, String>? = null
    private var consumer: org.apache.kafka.clients.consumer.Consumer<String, String>? = null

    @AfterEach
    fun close() {
        producer?.close()
        consumer?.close()
    }

    @Test
    fun `a poison authorize-payment is retried then parked on payments-dlt`() {
        // A consumer on the DLT, ready before we send so we can't miss the recovered record.
        val consumerProps = KafkaTestUtils.consumerProps("dlt-probe", "true", broker).apply {
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        }
        val dltConsumer = DefaultKafkaConsumerFactory<String, String>(consumerProps).createConsumer()
        consumer = dltConsumer
        dltConsumer.subscribe(listOf(Topics.PAYMENTS_DLT))

        // Produce a malformed command to authorize-payment: the listener's deserialize throws -> retry -> DLT.
        val producerProps = KafkaTestUtils.producerProps(broker).apply {
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        }
        val poison = """{ this is not valid json """
        DefaultKafkaProducerFactory<String, String>(producerProps).createProducer().also { producer = it }
            .send(ProducerRecord(Topics.AUTHORIZE_PAYMENT, "order-poison", poison)).get()

        // Wait for the recovered record on the DLT (3 x 500ms retries + broker round-trips -> allow generous time).
        val parked = pollForDltRecord(deadlineMillis = 30_000)

        assertNotNull(parked, "the poison message must be parked on payments-dlt (bounded retry -> recover)")
        assertNotNull(parked.value(), "the DLT record carries the original payload for inspection/replay")
    }

    private fun pollForDltRecord(deadlineMillis: Long): ConsumerRecord<String, String>? {
        val c = consumer ?: return null
        val end = System.currentTimeMillis() + deadlineMillis
        while (System.currentTimeMillis() < end) {
            val records = c.poll(Duration.ofMillis(500))
            val hit = records.records(Topics.PAYMENTS_DLT).firstOrNull()
            if (hit != null) return hit
        }
        return null
    }
}
