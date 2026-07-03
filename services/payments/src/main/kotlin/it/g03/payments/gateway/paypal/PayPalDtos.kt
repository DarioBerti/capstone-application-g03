package it.g03.payments.gateway.paypal

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Minimal PayPal Orders v2 request/response shapes — only the fields we send/read. PayPal uses snake_case JSON,
 * so multi-word fields carry an explicit @JsonProperty. Responses ignore unknown properties (PayPal returns far
 * more than we consume).
 */

// --- create-order (intent=AUTHORIZE) request, server-to-server with a card payment source -------------------

data class CreateOrderRequest(
    val intent: String,
    @JsonProperty("purchase_units") val purchaseUnits: List<PurchaseUnit>,
    @JsonProperty("payment_source") val paymentSource: PaymentSource,
)

data class PurchaseUnit(
    val amount: Amount,
    // WHAT the buyer is purchasing (booking legs). Null = authorize the total alone. When present, PayPal requires
    // amount.breakdown.item_total to equal the sum of items (name/quantity/unit_amount) — the gateway sets both.
    val items: List<Item>? = null,
)

data class Amount(
    @JsonProperty("currency_code") val currencyCode: String,
    val value: String,
    val breakdown: Breakdown? = null,
)

data class Breakdown(
    @JsonProperty("item_total") val itemTotal: Amount,
)

data class Item(
    val name: String,
    val quantity: String,
    @JsonProperty("unit_amount") val unitAmount: Amount,
)

data class PaymentSource(val card: Card)

data class Card(
    val number: String,
    val expiry: String,
    @JsonProperty("security_code") val securityCode: String,
)

// --- create-order response (the authorization id lives under purchase_units[].payments.authorizations[]) -----

@JsonIgnoreProperties(ignoreUnknown = true)
data class OrderResponse(
    val id: String?,
    val status: String?,
    @JsonProperty("purchase_units") val purchaseUnits: List<PurchaseUnitResponse>?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PurchaseUnitResponse(val payments: Payments?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Payments(val authorizations: List<AuthorizationResponse>?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AuthorizationResponse(val id: String?, val status: String?)

// --- capture response (the capture resource is returned directly) -------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class CaptureResponse(val id: String?, val status: String?)

// --- oauth2 client-credentials token ------------------------------------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class TokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("expires_in") val expiresIn: Long,
)

// --- error / decline body (422 carries details[].issue) -----------------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class PayPalError(
    val name: String?,
    val message: String?,
    val details: List<ErrorDetail>?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ErrorDetail(val issue: String?, val description: String?)
