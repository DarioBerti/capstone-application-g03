package it.g03.payments.repository

import it.g03.payments.entities.OutboxEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// JpaRepository<Entity, IdType> for the OutboxEvent entity; the IdType is UUID because the outbox event id is a UUID. Spring Data generates the implementation at runtime.
// The outbox is now drained externally by Debezium CDC (see the KafkaConnector in the config repo), so this repository only serves the write side (Outbox.append -> save) and test assertions.
interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID>
