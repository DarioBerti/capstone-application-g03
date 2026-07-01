package it.g03.orders.repository

import it.g03.orders.entities.OutboxEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// JpaRepository<Entity, IdType> is a Spring Data interface that provides CRUD operations and query methods (for the OutboxEvent entity in this case); the IdType is UUID because the outbox event id is a UUID. Spring Data will generate the implementation at runtime.
// The outbox is now drained externally by Debezium CDC (see the KafkaConnector in the config repo), so this repository only serves the write side (Outbox.append -> save) and test assertions.
interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID>
