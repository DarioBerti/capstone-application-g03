package it.g03.catalog.events

/**
 * Kafka topics this service consumes. Catalog is CONSUME-ONLY (a read-side availability projection) — it never
 * produces domain events and never sits on the money path. Each service keeps its OWN copy of the topic names
 * (the canonical cross-service contract; topics created by Strimzi in the config repo).
 */
object Topics {
    // consumed: Inventory's best-effort seat-count changes, projected into the local availability read-model
    const val SEAT_AVAILABILITY = "seat-availability"

    // dead-letter sink for availability events that exhaust the bounded retry (operational, not a domain event)
    const val CATALOG_DLT = "catalog-dlt"
}
