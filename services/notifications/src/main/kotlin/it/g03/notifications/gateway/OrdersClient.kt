package it.g03.notifications.gateway

import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Ownership check against Orders for the SSE stream: a caller may open a stream only for THEIR OWN order.
 * Notifications owns no order data, so it asks Orders — forwarding the CALLER's bearer token (not a service
 * token) — and treats a 2xx as "the caller owns this order" (Orders' GET is buyer-scoped: a foreign order
 * answers 404). A deliberate, read-only exception to this service's "sink, not participant" rule: it never
 * touches the money path and stays off the critical path (SSE is best-effort — if this check can't complete
 * the SPA simply degrades to polling the buyer-scoped order).
 */
interface OrdersClient {
    fun isOwner(orderId: String, bearerToken: String): Boolean
}

@Component
class RestClientOrdersClient(private val ordersRestClient: RestClient) : OrdersClient {
    // exchange() (not retrieve()) so a 403/404 is a plain false rather than a thrown error — no exception-as-flow.
    override fun isOwner(orderId: String, bearerToken: String): Boolean =
        ordersRestClient.get()
            .uri("/api/orders/{orderId}", orderId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $bearerToken")
            .exchange { _, response -> response.statusCode.is2xxSuccessful }
}
