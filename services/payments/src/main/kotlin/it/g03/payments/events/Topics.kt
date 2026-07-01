package it.g03.payments.events

/** Topics this service consumes (commands from Orders) and produces to (replies, via the outbox). Created by Strimzi in the config repo. */
object Topics {
    // Commands consumed from Orders (the saga orchestrator)
    const val AUTHORIZE_PAYMENT = "authorize-payment"         // place a funds HOLD (auth only)
    const val CAPTURE_PAYMENT = "capture-payment"             // capture a previously held authorization
    const val CANCEL_AUTHORIZATION = "cancel-authorization"   // release a hold (compensation, no money moved)

    // Replies produced back to Orders via the transactional outbox (key = orderId)
    const val PAYMENT_RESULT = "payment-result"               // AUTHORIZED | DECLINED outcome of an authorize
    const val PAYMENT_CAPTURED = "payment-captured"           // confirms a captured charge

    // Dead-letter sink: records that exhaust the bounded consumer retry land here (Pillar C)
    const val PAYMENTS_DLT = "payments-dlt"
}
