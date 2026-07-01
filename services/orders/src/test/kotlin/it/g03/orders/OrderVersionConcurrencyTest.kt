package it.g03.orders

import it.g03.orders.entities.OrderStatus
import it.g03.orders.events.Topics
import it.g03.orders.repository.OrderRepository
import it.g03.orders.repository.OutboxEventRepository
import it.g03.orders.service.OrderService
import it.g03.orders.service.OrderStateMachine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals

/**
 * Proves the @Version optimistic lock is LOAD-BEARING: when several drivers (the timeout sweeper's re-drive racing an
 * in-flight reply, or two pods) race to apply the SAME terminal transition, exactly ONE order-confirmed + one
 * notification reach the outbox — the losers' commits roll back instead of appending duplicates. Drop @Version (or the
 * V2 column) and this test fails. Mirrors InventoryConcurrencyTest's multi-thread style.
 */
@SpringBootTest(
    properties = [
        "spring.kafka.bootstrap-servers=localhost:9092",
        "events.publish.enabled=false",
        "app.kafka-listeners-enabled=false",
        "app.outbox.enabled=false",
        "app.timeout-sweeper.enabled=false",
    ],
)
@Tag("chaos-03") // anticipates chaos #3 (node-disruption): two pods racing a terminal transition emit exactly one event
@Import(TestcontainersConfiguration::class)
class OrderVersionConcurrencyTest {

    @Autowired lateinit var stateMachine: OrderStateMachine
    @Autowired lateinit var orderService: OrderService
    @Autowired lateinit var orders: OrderRepository
    @Autowired lateinit var outbox: OutboxEventRepository

    // Thread pool that lets several confirm() calls truly run at the same time
    private val pool = Executors.newFixedThreadPool(8)

    // Tears down the thread pool after each test so threads don't leak between runs
    @AfterEach
    fun shutdownPool() {
        pool.shutdownNow()
    }

    @Test
    fun `concurrent confirm emits exactly one order-confirmed (no duplicate terminal events)`() {
        val order = orderService.placeOrder("FR9592-20260629", 1, buyerSub = "buyer-1", buyerEmail = "buyer@example.org")
        val id = order.id.toString()
        // confirm() only fires the AUTHORIZED -> CONFIRMED edge, so drive the order to AUTHORIZED first
        stateMachine.onReservationResult(order.id, "RESERVED")
        stateMachine.onPaymentResult(order.id, "AUTHORIZED")
        val start = CountDownLatch(1)

        val futures = (1..8).map {
            pool.submit(
                Callable {
                    start.await()
                    // each call runs in its own transaction; the loser of the @Version race throws ObjectOptimisticLockingFailureException
                    runCatching { stateMachine.confirm(order.id) }
                },
            )
        }
        start.countDown()
        futures.forEach { it.get() }

        assertEquals(OrderStatus.CONFIRMED, orders.findById(order.id).orElseThrow().status)
        // filter by THIS order's aggregateId so a shared in-memory DB across cached contexts can't skew the count
        val confirmed = outbox.findAll().count { it.topic == Topics.ORDER_CONFIRMED && it.aggregateId == id }
        val notifications = outbox.findAll().count { it.topic == Topics.NOTIFICATION_REQUESTED && it.aggregateId == id }
        assertEquals(1, confirmed, "exactly one order-confirmed event despite concurrent confirms")
        assertEquals(1, notifications, "exactly one confirmation notification")
    }
}
